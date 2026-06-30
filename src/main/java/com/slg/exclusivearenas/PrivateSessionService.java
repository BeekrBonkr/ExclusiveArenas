package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.Arena;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PrivateSessionService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I

    private final Map<String, PrivateSession> byArena = new ConcurrentHashMap<>();
    private final Map<UUID, PrivateSession> byId = new ConcurrentHashMap<>();
    private final Map<UUID, PrivateSession> byOwner = new ConcurrentHashMap<>();
    private final Map<String, UUID> byCode = new ConcurrentHashMap<>(); // code lower -> sessionId

    public boolean isArenaReserved(String arenaName) {
        if (arenaName == null) return false;
        return byArena.containsKey(arenaName.toLowerCase(Locale.ROOT));
    }

    public PrivateSession getByArena(Arena arena) {
        if (arena == null) return null;
        return getByArenaName(arena.getName());
    }

    public PrivateSession getByArenaName(String arenaName) {
        if (arenaName == null) return null;
        return byArena.get(arenaName.toLowerCase(Locale.ROOT));
    }

    public PrivateSession getById(UUID sessionId) {
        if (sessionId == null) return null;
        return byId.get(sessionId);
    }

    public PrivateSession getByOwner(UUID owner) {
        if (owner == null) return null;
        return byOwner.get(owner);
    }

    public PrivateSession getByJoinCode(String code) {
        if (code == null) return null;
        UUID id = byCode.get(code.toLowerCase(Locale.ROOT));
        return id == null ? null : getById(id);
    }

    public Collection<PrivateSession> getAllSessions() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public PrivateSession createSession(DraftPrivateMatch draft) {
        if (draft == null) throw new IllegalArgumentException("draft is null");

        String arena = draft.getArenaName();
        if (arena == null || arena.isBlank()) throw new IllegalArgumentException("Draft missing arenaName");

        JoinPolicy policy = draft.getJoinPolicy();
        if (policy == null) policy = JoinPolicy.PARTY;

        String code = null;
        if (policy == JoinPolicy.CODE) {
            code = draft.getJoinCode();
            if (code == null || code.isBlank() || !code.startsWith(arena + "::")) {
                code = generateCodeForArena(arena);
                draft.setJoinCode(code);
            }
        } else {
            draft.setJoinCode(null);
        }

        boolean isPublic = (policy == JoinPolicy.CODE) && draft.isPublic();

        PrivateSession session = new PrivateSession(
                UUID.randomUUID(), draft.getOwner(), arena, policy, code, isPublic);

        register(session);
        return session;
    }

    public PrivateSession createSessionFromNetwork(UUID sessionId, UUID owner, String arenaName,
                                                   JoinPolicy policy, String code, boolean isPublic) {
        PrivateSession existing = getById(sessionId);
        if (existing != null) return existing;

        if (policy == null) policy = JoinPolicy.PARTY;
        if (policy == JoinPolicy.CODE) {
            if (code == null || code.isBlank() || !code.startsWith(arenaName + "::")) {
                code = generateCodeForArena(arenaName);
            }
        } else {
            code = null;
            isPublic = false;
        }

        PrivateSession session = new PrivateSession(sessionId, owner, arenaName, policy, code, isPublic);
        register(session);
        return session;
    }

    /** Applies a replicated public/code change from another server, keeping the code index in sync. */
    public void applyRemoteUpdate(PrivateSession session, String code, boolean isPublic) {
        if (session == null) return;

        String old = session.getJoinCode();
        if (old != null && (code == null || !old.equalsIgnoreCase(code))) {
            byCode.remove(old.toLowerCase(Locale.ROOT));
        }
        session.setJoinCode(code);
        if (code != null) byCode.put(code.toLowerCase(Locale.ROOT), session.getSessionId());

        session.setPublic(isPublic);
    }

    public String regenerateJoinCode(PrivateSession session) {
        if (session == null || session.getJoinPolicy() != JoinPolicy.CODE) return null;

        String old = session.getJoinCode();
        if (old != null) byCode.remove(old.toLowerCase(Locale.ROOT));

        String fresh = generateCodeForArena(session.getArenaName());
        session.setJoinCode(fresh);
        byCode.put(fresh.toLowerCase(Locale.ROOT), session.getSessionId());
        return fresh;
    }

    public void endSession(PrivateSession session) {
        if (session == null) return;

        byArena.remove(session.getArenaName().toLowerCase(Locale.ROOT));
        byId.remove(session.getSessionId());

        UUID owner = session.getOwner();
        if (owner != null) byOwner.remove(owner);

        String code = session.getJoinCode();
        if (code != null) byCode.remove(code.toLowerCase(Locale.ROOT));
    }

    public String generateCodeForArena(String arenaName) {
        return arenaName + "::" + randomChunk(4);
    }

    private void register(PrivateSession session) {
        String key = session.getArenaName().toLowerCase(Locale.ROOT);
        byArena.put(key, session);
        byId.put(session.getSessionId(), session);
        if (session.getOwner() != null) byOwner.put(session.getOwner(), session);
        if (session.getJoinCode() != null) {
            byCode.put(session.getJoinCode().toLowerCase(Locale.ROOT), session.getSessionId());
        }
    }

    private static String randomChunk(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
