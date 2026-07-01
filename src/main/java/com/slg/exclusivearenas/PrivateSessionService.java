package com.slg.exclusivearenas;

import de.marcely.bedwars.api.arena.Arena;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PrivateSessionService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I

    private final Map<String, PrivateSession> byArena = new ConcurrentHashMap<>();
    private final Map<UUID, PrivateSession> byId = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> byOwner = new ConcurrentHashMap<>(); // owner -> session ids
    private final Map<String, UUID> byCode = new ConcurrentHashMap<>(); // code lower -> sessionId

    // Sessions created locally whose async write-through a poll hasn't confirmed yet.
    // A session id stays here until a reconcile sees it in the DB; while pending it is
    // never reconcile-removed, so a fresh local create can't be evicted by an early poll.
    private final Set<UUID> pendingWrite = ConcurrentHashMap.newKeySet();

    private int codeLength = 6;
    private Database db; // null when running single-server (database.enabled = false)

    public void setDatabase(Database db) {
        this.db = db;
    }

    public void setCodeLength(int codeLength) {
        if (codeLength >= 4) this.codeLength = codeLength;
    }

    public boolean isArenaReserved(String arenaName) {
        if (arenaName == null) return false;
        return byArena.containsKey(ArenaNames.canonical(arenaName).toLowerCase(Locale.ROOT));
    }

    public PrivateSession getByArena(Arena arena) {
        if (arena == null) return null;
        return getByArenaName(arena.getName());
    }

    public PrivateSession getByArenaName(String arenaName) {
        if (arenaName == null) return null;
        return byArena.get(ArenaNames.canonical(arenaName).toLowerCase(Locale.ROOT));
    }

    public PrivateSession getById(UUID sessionId) {
        if (sessionId == null) return null;
        return byId.get(sessionId);
    }

    /** Any one session owned by the player (prefers a PARTY-policy one for auto-summon). */
    public PrivateSession getByOwner(UUID owner) {
        PrivateSession partyMatch = null;
        for (PrivateSession s : getSessionsByOwner(owner)) {
            if (s.getJoinPolicy() == JoinPolicy.PARTY) return s;
            if (partyMatch == null) partyMatch = s;
        }
        return partyMatch;
    }

    /** All active sessions this player is hosting. */
    public List<PrivateSession> getSessionsByOwner(UUID owner) {
        if (owner == null) return Collections.emptyList();
        Set<UUID> ids = byOwner.get(owner);
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        List<PrivateSession> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            PrivateSession s = byId.get(id);
            if (s != null) out.add(s);
        }
        out.sort(Comparator.comparing(PrivateSession::getArenaName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Number of active matches this player is currently hosting. */
    public int countByOwner(UUID owner) {
        if (owner == null) return 0;
        Set<UUID> ids = byOwner.get(owner);
        return ids == null ? 0 : ids.size();
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

        // Store the canonical (bare) arena name so it matches the arena on its host server,
        // regardless of the '@' remote marker the hub's arena list may carry.
        String arena = ArenaNames.canonical(draft.getArenaName());
        if (arena == null || arena.isBlank()) throw new IllegalArgumentException("Draft missing arenaName");

        JoinPolicy policy = draft.getJoinPolicy();
        if (policy == null) policy = JoinPolicy.PARTY;

        String code = null;
        if (policy == JoinPolicy.CODE) {
            code = draft.getJoinCode();
            if (code == null || code.isBlank() || byCode.containsKey(code.toLowerCase(Locale.ROOT))) {
                code = generateCode();
                draft.setJoinCode(code);
            }
        } else {
            draft.setJoinCode(null);
        }

        boolean isPublic = (policy == JoinPolicy.CODE) && draft.isPublic();

        PrivateSession session = new PrivateSession(
                UUID.randomUUID(), draft.getOwner(), arena, policy, code, isPublic);
        session.setAutoSummon(draft.isAutoSummon());

        indexLocal(session);

        // Write-through: mark pending so a poll can't evict it before it lands in the DB.
        if (db != null) {
            pendingWrite.add(session.getSessionId());
            db.upsertSession(toRow(session));
        }
        return session;
    }

    public String regenerateJoinCode(PrivateSession session) {
        if (session == null || session.getJoinPolicy() != JoinPolicy.CODE) return null;

        String old = session.getJoinCode();
        if (old != null) byCode.remove(old.toLowerCase(Locale.ROOT));

        String fresh = generateCode();
        session.setJoinCode(fresh);
        byCode.put(fresh.toLowerCase(Locale.ROOT), session.getSessionId());

        if (db != null) db.upsertSession(toRow(session));
        return fresh;
    }

    /**
     * Switches a live session between Party and Code gating (host-triggered, lobby only —
     * enforced by the caller). Regenerates a fresh code when switching to Code, drops the old
     * one when switching away, and persists the change network-wide.
     */
    public void setSessionJoinPolicy(PrivateSession session, JoinPolicy policy) {
        if (session == null || policy == null || session.getJoinPolicy() == policy) return;

        String oldCode = session.getJoinCode();
        if (oldCode != null) byCode.remove(oldCode.toLowerCase(Locale.ROOT));

        session.setJoinPolicy(policy);
        if (policy == JoinPolicy.CODE) {
            String fresh = generateCode();
            session.setJoinCode(fresh);
            byCode.put(fresh.toLowerCase(Locale.ROOT), session.getSessionId());
            session.setPublic(true);
            session.setAutoSummon(false); // auto-summon only applies to Party-policy matches
        } else {
            session.setJoinCode(null);
            session.setPublic(false);
        }

        if (db != null) db.upsertSession(toRow(session));
    }

    /** Toggles the public flag and persists it so join-by-code resolves correctly network-wide. */
    public void setSessionPublic(PrivateSession session, boolean isPublic) {
        if (session == null) return;
        session.setPublic(isPublic);
        if (db != null) db.upsertSession(toRow(session));
    }

    /** Toggles auto-summon of new party members and persists it network-wide. */
    public void setSessionAutoSummon(PrivateSession session, boolean autoSummon) {
        if (session == null) return;
        session.setAutoSummon(autoSummon);
        if (db != null) db.upsertSession(toRow(session));
    }

    /** Re-writes every in-memory session to the DB. Used after a reconnect on reload. */
    public void resyncAll() {
        if (db == null) return;
        for (PrivateSession s : byId.values()) {
            pendingWrite.add(s.getSessionId());
            db.upsertSession(toRow(s));
        }
    }

    public void endSession(PrivateSession session) {
        if (session == null) return;
        removeLocal(session);
        if (db != null) db.deleteSession(session.getSessionId());
    }

    /** Generates a short join code that is not currently in use. */
    public String generateCode() {
        for (int attempt = 0; attempt < 50; attempt++) {
            String code = randomChunk(codeLength);
            if (!byCode.containsKey(code.toLowerCase(Locale.ROOT))) return code;
        }
        // Extremely unlikely; widen the space to guarantee a unique value.
        return randomChunk(codeLength + 2);
    }

    // ── Reconciliation (called on the main thread by SyncService) ───────────────────

    /**
     * Reconciles the session cache against the authoritative DB rows: refreshes existing
     * sessions, adds rows this server has not seen yet, and drops sessions no longer present
     * — except ids still pending a write confirmation, so a freshly created local session is
     * never evicted early.
     */
    public void reconcile(List<Database.SessionRow> rows) {
        Set<UUID> present = new HashSet<>();
        for (Database.SessionRow row : rows) {
            present.add(row.sessionId());
            pendingWrite.remove(row.sessionId()); // confirmed in DB

            PrivateSession existing = byId.get(row.sessionId());
            if (existing == null) {
                indexLocal(fromRow(row));
            } else {
                updateLocalFromRow(existing, row);
            }
        }

        for (PrivateSession s : new ArrayList<>(byId.values())) {
            UUID id = s.getSessionId();
            if (present.contains(id)) continue;
            if (pendingWrite.contains(id)) continue; // local create not yet observed by a poll
            removeLocal(s);
        }
    }

    private void updateLocalFromRow(PrivateSession session, Database.SessionRow row) {
        String old = session.getJoinCode();
        String fresh = row.joinCode();
        if (old != null && (fresh == null || !old.equalsIgnoreCase(fresh))) {
            byCode.remove(old.toLowerCase(Locale.ROOT));
        }
        session.setJoinCode(fresh);
        if (fresh != null) byCode.put(fresh.toLowerCase(Locale.ROOT), session.getSessionId());
        session.setPublic(row.isPublic());
        session.setAutoSummon(row.autoSummon());
    }

    private PrivateSession fromRow(Database.SessionRow row) {
        JoinPolicy policy;
        try {
            policy = JoinPolicy.valueOf(row.policy());
        } catch (IllegalArgumentException | NullPointerException e) {
            policy = JoinPolicy.PARTY;
        }
        PrivateSession session = new PrivateSession(
                row.sessionId(), row.owner(), row.arenaName(), policy, row.joinCode(), row.isPublic());
        session.setAutoSummon(row.autoSummon());
        return session;
    }

    private Database.SessionRow toRow(PrivateSession s) {
        return new Database.SessionRow(
                s.getSessionId(), s.getOwner(), s.getArenaName(), s.getJoinPolicy().name(),
                s.getJoinCode(), s.isPublic(), s.isAutoSummon(),
                db != null ? db.serverId() : null, s.getCreatedAt().toEpochMilli());
    }

    // ── Cache index helpers (no DB side effects) ────────────────────────────────────

    private void indexLocal(PrivateSession session) {
        byArena.put(session.getArenaName().toLowerCase(Locale.ROOT), session);
        byId.put(session.getSessionId(), session);
        if (session.getOwner() != null) {
            byOwner.computeIfAbsent(session.getOwner(), k -> ConcurrentHashMap.newKeySet())
                    .add(session.getSessionId());
        }
        if (session.getJoinCode() != null) {
            byCode.put(session.getJoinCode().toLowerCase(Locale.ROOT), session.getSessionId());
        }
    }

    private void removeLocal(PrivateSession session) {
        byArena.remove(session.getArenaName().toLowerCase(Locale.ROOT));
        byId.remove(session.getSessionId());

        UUID owner = session.getOwner();
        if (owner != null) {
            byOwner.computeIfPresent(owner, (k, ids) -> {
                ids.remove(session.getSessionId());
                return ids.isEmpty() ? null : ids;
            });
        }

        String code = session.getJoinCode();
        if (code != null) byCode.remove(code.toLowerCase(Locale.ROOT));

        pendingWrite.remove(session.getSessionId());
    }

    private static String randomChunk(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
