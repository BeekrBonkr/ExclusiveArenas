package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;

import java.util.List;
import java.util.UUID;

/**
 * Lets a host control a private match's arena from a server that doesn't host it — another
 * arena server, or a hub — by relaying one-shot actions through the shared database.
 *
 * A host issues a command from wherever they are; it's written to {@code <prefix>commands}.
 * Every server polls that table (see {@link SyncService}) and, for each row, checks whether
 * IT is the one that actually has that arena loaded locally. Only the true host of the arena
 * will ever find a match, so there's no cross-server race to claim a row — whichever server
 * finds it deletes it and executes it there and then.
 */
public final class RemoteCommandService {

    public enum Type {
        START_MATCH, END_MATCH, KICK_ALL,
        QUICK_REGEN, QUICK_HEAL, QUICK_DROP, QUICK_BEDS, QUICK_CLEAR, QUICK_SKIP_EVENT
    }

    /** KICK_ALL payload marking the shift-click variant that spares the host. */
    public static final String PAYLOAD_KEEP_HOST = "keep-host";

    private final ExclusiveArenasPlugin plugin;
    private final PrivateSessionService sessions;
    private Database db;

    public RemoteCommandService(ExclusiveArenasPlugin plugin, PrivateSessionService sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    public void setDatabase(Database db) {
        this.db = db;
    }

    /** True when a network database is available to relay commands through. */
    public boolean isAvailable() {
        return db != null;
    }

    public void enqueue(Type type, PrivateSession session) {
        enqueue(type, session, null);
    }

    public void enqueue(Type type, PrivateSession session, String payload) {
        if (db == null || session == null) return;
        db.insertCommand(new Database.CommandRow(UUID.randomUUID(), session.getSessionId(),
                session.getArenaName(), type.name(), payload, System.currentTimeMillis()));
    }

    /** Called on the main thread by {@link SyncService} once rows are loaded off-thread. */
    public void reconcile(List<Database.CommandRow> rows) {
        for (Database.CommandRow row : rows) {
            Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(row.arenaName());
            if (arena == null || !arena.exists()) continue; // not hosted on this server

            db.deleteCommand(row.id()); // claim it first so a slow handler can't double-run it
            execute(row, arena);
        }
    }

    private void execute(Database.CommandRow row, Arena arena) {
        PrivateSession session = sessions.getById(row.sessionId());
        if (session == null) return;

        Type type;
        try {
            type = Type.valueOf(row.type());
        } catch (IllegalArgumentException e) {
            return;
        }

        QuickActionsService quick = plugin.getQuickActions();
        switch (type) {
            case START_MATCH -> plugin.startMatchNow(null, arena, session);
            case END_MATCH -> plugin.endMatch(session);
            case KICK_ALL -> quick.kickAll(null, session, arena,
                    PAYLOAD_KEEP_HOST.equals(row.payload()));
            case QUICK_REGEN -> quick.regenerateKeepingPlayers(null, session, arena);
            case QUICK_HEAL -> quick.healAll(null, session, arena);
            case QUICK_DROP -> quick.dropAllSpawners(null, session, arena);
            case QUICK_BEDS -> quick.destroyAllBeds(null, session, arena);
            case QUICK_CLEAR -> quick.clearGroundItems(null, session, arena);
            case QUICK_SKIP_EVENT -> quick.skipToNextEvent(null, session, arena);
        }
    }
}
