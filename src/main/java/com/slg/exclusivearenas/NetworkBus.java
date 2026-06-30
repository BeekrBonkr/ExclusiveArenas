package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.event.remote.RemoteCustomMessageReceiveEvent;
import de.marcely.bedwars.api.remote.RemoteAPI;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Cross-server replication of private-match state, built on MBedwars' RemoteAPI
 * (the layer enabled by the EnhancedProxySync add-on).
 *
 * Unlike Bungee plugin messaging, {@link RemoteAPI#broadcastCustomMessage} reaches
 * every server on the network without needing an online player as a carrier, so the
 * session state below is eventually present on the hub and every arena server. Each
 * server enforces gating against its own replicated copy:
 *   - the hub validates join codes and routes players,
 *   - the arena server authorises joins via tickets in its pre-join check.
 *
 * Message types:
 *   CREATE  – a new private session was created; every server registers it
 *   UPDATE  – a session's public/locked flag or code changed
 *   END     – a session ended; every server forgets it
 *   TICKET  – grant a one-shot join token for a player (so the arena server lets them in)
 *
 * All handlers are idempotent, so it is harmless if the network echoes a broadcast
 * back to its originating server.
 */
public final class NetworkBus implements Listener {

    static final String CHANNEL = "exclusivearenas:main";

    private static final String MSG_CREATE = "CREATE";
    private static final String MSG_UPDATE = "UPDATE";
    private static final String MSG_END    = "END";
    private static final String MSG_TICKET = "TICKET";

    private final ExclusiveArenasPlugin plugin;
    private final PrivateSessionService sessions;
    private final JoinTicketService tickets;

    public NetworkBus(ExclusiveArenasPlugin plugin,
                      PrivateSessionService sessions,
                      JoinTicketService tickets) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.tickets = tickets;
    }

    /** True when the RemoteAPI is present and active (i.e. we are on a proxied network). */
    public boolean isNetworkActive() {
        return remote() != null;
    }

    // ── Broadcasting ───────────────────────────────────────────────────────────

    public void broadcastCreate(PrivateSession s) {
        send(out -> {
            out.writeUTF(MSG_CREATE);
            writeSession(out, s);
        });
    }

    public void broadcastUpdate(PrivateSession s) {
        send(out -> {
            out.writeUTF(MSG_UPDATE);
            writeSession(out, s);
        });
    }

    public void broadcastEnd(UUID sessionId) {
        send(out -> {
            out.writeUTF(MSG_END);
            out.writeUTF(sessionId.toString());
        });
    }

    public void broadcastTicket(UUID playerId, UUID sessionId, String arenaName) {
        send(out -> {
            out.writeUTF(MSG_TICKET);
            out.writeUTF(playerId.toString());
            out.writeUTF(sessionId.toString());
            out.writeUTF(arenaName == null ? "" : arenaName);
        });
    }

    // ── Receiving ──────────────────────────────────────────────────────────────

    @EventHandler
    public void onRemoteMessage(RemoteCustomMessageReceiveEvent event) {
        if (!CHANNEL.equalsIgnoreCase(event.getChannel())) return;

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getPayload()))) {
            String type = in.readUTF();
            switch (type) {
                case MSG_CREATE -> handleCreate(in);
                case MSG_UPDATE -> handleUpdate(in);
                case MSG_END    -> handleEnd(in);
                case MSG_TICKET -> handleTicket(in);
                default -> { /* unknown message type; ignore */ }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to read remote message: " + t.getMessage());
        }
    }

    private void handleCreate(DataInputStream in) throws IOException {
        UUID sessionId   = UUID.fromString(in.readUTF());
        UUID owner       = UUID.fromString(in.readUTF());
        String arenaName = in.readUTF();
        JoinPolicy policy = JoinPolicy.valueOf(in.readUTF());
        String code      = blankToNull(in.readUTF());
        boolean isPublic = in.readBoolean();

        PrivateSession session = sessions.createSessionFromNetwork(
                sessionId, owner, arenaName, policy, code, isPublic);

        // If we host this arena, make sure the lobby countdown stays paused until the host starts it.
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(arenaName);
        if (arena != null && arena.exists()) plugin.pauseLobbyCountdownIfNeeded(arena, session);
    }

    private void handleUpdate(DataInputStream in) throws IOException {
        UUID sessionId = UUID.fromString(in.readUTF());
        in.readUTF(); // owner (immutable; skip)
        in.readUTF(); // arenaName (immutable; skip)
        JoinPolicy.valueOf(in.readUTF()); // policy (immutable; skip)
        String code      = blankToNull(in.readUTF());
        boolean isPublic = in.readBoolean();

        PrivateSession session = sessions.getById(sessionId);
        if (session == null) return;
        sessions.applyRemoteUpdate(session, code, isPublic);
    }

    private void handleEnd(DataInputStream in) throws IOException {
        UUID sessionId = UUID.fromString(in.readUTF());
        PrivateSession session = sessions.getById(sessionId);
        if (session != null) sessions.endSession(session);
    }

    private void handleTicket(DataInputStream in) throws IOException {
        UUID playerId  = UUID.fromString(in.readUTF());
        UUID sessionId = UUID.fromString(in.readUTF());
        String arenaName = blankToNull(in.readUTF());

        // Only meaningful on the server hosting the arena; harmless elsewhere.
        tickets.grant(playerId, sessionId, arenaName);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void writeSession(DataOutputStream out, PrivateSession s) throws IOException {
        out.writeUTF(s.getSessionId().toString());
        out.writeUTF(s.getOwner().toString());
        out.writeUTF(s.getArenaName() == null ? "" : s.getArenaName());
        out.writeUTF(s.getJoinPolicy().name());
        out.writeUTF(s.getJoinCode() == null ? "" : s.getJoinCode());
        out.writeBoolean(s.isPublic());
    }

    private RemoteAPI remote() {
        try {
            RemoteAPI r = BedwarsAPI.getRemoteAPI();
            return (r != null && r.isAPIActive() && !r.isLocalOnly()) ? r : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void send(IOConsumer<DataOutputStream> fn) {
        RemoteAPI r = remote();
        if (r == null) return; // single-server / no proxy: nothing to replicate
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            fn.accept(out);
            out.flush();
            r.broadcastCustomMessage(CHANNEL, bos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to broadcast remote message: " + e.getMessage());
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    @FunctionalInterface
    private interface IOConsumer<T> {
        void accept(T t) throws IOException;
    }
}
