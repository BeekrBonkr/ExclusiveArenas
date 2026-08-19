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

    // Mirror of pendingWrite for the opposite race: a session ended locally whose async DELETE
    // hasn't committed yet. Without this, a poll whose SELECT ran before that DELETE lands would
    // see the still-present row and reconcile() would re-index (resurrect) the ended session.
    private final Set<UUID> pendingDelete = ConcurrentHashMap.newKeySet();

    // Timestamp of the most recent not-yet-confirmed local edit to an EXISTING session (join
    // code regen, public/auto-summon toggle, settings save). Unlike pendingWrite (which only
    // guards a brand-new session against early eviction), this guards against the opposite
    // problem: a poll whose SELECT ran before that edit's UPSERT committed would otherwise
    // return the pre-edit row and silently overwrite the fresh local state with it. A row is
    // only trusted to update local state once its own updated_at catches up to this timestamp.
    // Both sides of that comparison are this server's own writes, but a row last written by
    // ANOTHER server carries its clock — the guard assumes NTP-level skew between servers.
    private final Map<UUID, Long> pendingWriteAt = new ConcurrentHashMap<>();

    // Soft "someone's got this picked in their builder" lock — local to this server only
    // (the builder itself is local), so two hosts on it can't both select the same free
    // arena before either actually finishes creating. Self-expires so an abandoned draft
    // (closed the menu, disconnected, crashed, …) can't lock an arena forever.
    private final Map<String, UUID> draftReservations = new ConcurrentHashMap<>();
    private final Map<String, Long> draftReservedAt = new ConcurrentHashMap<>();
    private static final long DRAFT_RESERVATION_TTL_MS = 10 * 60_000L;

    private int codeLength = 6;
    private Database db; // null when running single-server (database.enabled = false)

    public void setDatabase(Database db) {
        this.db = db;
    }

    // Matches the sessions table's join_code VARCHAR(96) — a longer configured length would
    // silently truncate (or fail to insert) once persisted.
    private static final int MAX_CODE_LENGTH = 96;

    public void setCodeLength(int codeLength) {
        if (codeLength >= 4 && codeLength <= MAX_CODE_LENGTH) this.codeLength = codeLength;
    }

    public boolean isArenaReserved(String arenaName) {
        return isArenaReserved(arenaName, null);
    }

    /**
     * @param excludingOwner a draft reservation held by this player doesn't count as
     *                       "reserved" for them — so re-viewing/re-confirming their own pick
     *                       in the arena selector doesn't self-block.
     */
    public boolean isArenaReserved(String arenaName, UUID excludingOwner) {
        if (arenaName == null) return false;
        String key = ArenaNames.canonical(arenaName).toLowerCase(Locale.ROOT);
        if (byArena.containsKey(key)) return true;

        UUID draftOwner = draftReservations.get(key);
        if (draftOwner == null) return false;
        Long at = draftReservedAt.get(key);
        if (at == null || System.currentTimeMillis() - at > DRAFT_RESERVATION_TTL_MS) {
            draftReservations.remove(key);
            draftReservedAt.remove(key);
            return false;
        }
        return excludingOwner == null || !draftOwner.equals(excludingOwner);
    }

    /**
     * Soft-reserves {@code arenaName} for {@code owner}'s in-progress builder draft — released
     * by {@link #releaseDraftArena} once they pick a different map, back out, or actually
     * create the match. Returns false (and reserves nothing) if someone else already has it,
     * whether via a real session or another player's draft.
     */
    public boolean reserveDraftArena(String arenaName, UUID owner) {
        if (arenaName == null || owner == null || isArenaReserved(arenaName, owner)) return false;
        String key = ArenaNames.canonical(arenaName).toLowerCase(Locale.ROOT);
        draftReservations.put(key, owner);
        draftReservedAt.put(key, System.currentTimeMillis());
        return true;
    }

    /** Releases {@code owner}'s draft reservation on {@code arenaName}, if they still hold it. */
    public void releaseDraftArena(String arenaName, UUID owner) {
        if (arenaName == null || owner == null) return;
        String key = ArenaNames.canonical(arenaName).toLowerCase(Locale.ROOT);
        if (draftReservations.remove(key, owner)) {
            draftReservedAt.remove(key);
        }
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
            code = normalizeDraftCode(draft.getJoinCode());
            if (code == null || byCode.containsKey(code.toLowerCase(Locale.ROOT))) {
                code = generateCode();
            }
            draft.setJoinCode(code);
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
            writeThrough(session);
        }
        return session;
    }

    /**
     * Converts a PARTY-gated session to a CODE-gated one — used when the host leaves (or
     * loses leadership of) their party, so the match cleanly keeps running instead of being
     * stuck behind a party that no longer authorises anyone. A fresh code is generated but
     * the session stays LOCKED (non-public): opening it up is a privacy downgrade only the
     * host may choose (via /ea public or the menu) — otherwise the players just removed from
     * the party could grab the code and share it. The change is persisted network-wide.
     * Returns the new code, or the existing one when already CODE-gated.
     */
    public String convertToCodePolicy(PrivateSession session) {
        if (session == null) return null;
        if (session.getJoinPolicy() == JoinPolicy.CODE) return session.getJoinCode();

        String code = generateCode();
        session.setJoinPolicy(JoinPolicy.CODE);
        session.setJoinCode(code);
        session.setPublic(false);
        session.setAutoSummon(false); // only meaningful for Party policy
        byCode.put(code.toLowerCase(Locale.ROOT), session.getSessionId());

        writeThrough(session);
        return code;
    }

    public String regenerateJoinCode(PrivateSession session) {
        if (session == null || session.getJoinPolicy() != JoinPolicy.CODE) return null;

        String old = session.getJoinCode();
        if (old != null) byCode.remove(old.toLowerCase(Locale.ROOT), session.getSessionId());

        String fresh = generateCode();
        session.setJoinCode(fresh);
        byCode.put(fresh.toLowerCase(Locale.ROOT), session.getSessionId());

        writeThrough(session);
        return fresh;
    }

    /** Toggles the public flag and persists it so join-by-code resolves correctly network-wide. */
    public void setSessionPublic(PrivateSession session, boolean isPublic) {
        if (session == null) return;
        session.setPublic(isPublic);
        writeThrough(session);
    }

    /** Toggles auto-summon of new party members and persists it network-wide. */
    public void setSessionAutoSummon(PrivateSession session, boolean autoSummon) {
        if (session == null) return;
        session.setAutoSummon(autoSummon);
        writeThrough(session);
    }

    /**
     * Persists the session's current {@link SessionSettings} (timeline + shop overrides)
     * network-wide. Call after mutating them via the Arena Modifiers menus.
     */
    public void saveSettings(PrivateSession session) {
        if (session == null) return;
        writeThrough(session);
    }

    /**
     * Applies a saved configuration to a live session and persists it network-wide.
     *
     * The teams lock is deliberately carried over rather than taken from the preset: it is
     * live lobby state ("nobody may switch right now"), not part of the setup a preset
     * describes, so applying a configuration must never quietly unlock — or lock — a lobby
     * whose players are standing in it.
     */
    public void applyPresetSettings(PrivateSession session, String settingsJson) {
        if (session == null) return;
        boolean lockedNow = session.getSettings().isTeamsLocked();
        SessionSettings applied = SessionSettings.fromJson(settingsJson);
        applied.setTeamsLocked(lockedNow);
        session.setSettings(applied);
        saveSettings(session);
    }

    /**
     * Re-writes one session to the DB under this server's own {@code server_id} — used by the
     * dead-server sweep to adopt a row whose writing server died while the arena is actually
     * running HERE, so the session gets a live owner instead of being purged out from under a
     * live match. No-op when the session isn't in the local cache (a later sweep retries).
     */
    public void adoptSession(UUID sessionId) {
        PrivateSession session = sessionId == null ? null : byId.get(sessionId);
        if (session != null) writeThrough(session);
    }

    /** Re-writes every in-memory session to the DB. Used after a reconnect on reload. */
    public void resyncAll() {
        if (db == null) return;
        for (PrivateSession s : byId.values()) {
            pendingWrite.add(s.getSessionId());
            writeThrough(s);
        }
    }

    /**
     * Fire-and-forget write-through with a staleness guard: records the write's timestamp
     * before sending it, so {@link #reconcile} can tell a poll's row that predates this write
     * apart from one that reflects it (or a later edit), and skip applying the stale one.
     */
    private void writeThrough(PrivateSession session) {
        if (db == null) return;
        long now = System.currentTimeMillis();
        pendingWriteAt.put(session.getSessionId(), now);
        db.upsertSession(toRow(session, now));
    }

    public void endSession(PrivateSession session) {
        if (session == null) return;
        removeLocal(session);
        if (db != null) {
            pendingDelete.add(session.getSessionId());
            db.deleteSession(session.getSessionId());
        }
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
     * never evicted early. Symmetrically, an id pending a local delete is never re-added, so a
     * poll that raced ahead of that delete's commit can't resurrect a session we just ended.
     */
    public void reconcile(List<Database.SessionRow> rows) {
        Set<UUID> present = new HashSet<>();
        for (Database.SessionRow row : rows) present.add(row.sessionId());
        pendingDelete.removeIf(id -> !present.contains(id)); // confirmed gone from the DB

        for (Database.SessionRow row : rows) {
            UUID id = row.sessionId();
            if (pendingDelete.contains(id)) continue; // ended locally; the DELETE just hasn't landed yet
            pendingWrite.remove(id); // confirmed in DB

            Long pendingAt = pendingWriteAt.get(id);
            if (pendingAt != null) {
                if (row.updatedAt() < pendingAt) continue; // stale row predates our last local edit
                pendingWriteAt.remove(id, pendingAt); // caught up — don't clear a newer pending write
            }

            try {
                PrivateSession existing = byId.get(id);
                if (existing == null) {
                    indexLocal(fromRow(row));
                } else {
                    updateLocalFromRow(existing, row);
                }
            } catch (Exception e) {
                // One malformed/unexpected row must not abort reconciliation for every other
                // session in this poll batch — skip it; the next poll gets another chance.
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
        // Re-assert the arena mapping: arena_name is UNIQUE in the DB, so the session matching
        // this row is the rightful owner. When two sessions briefly existed for one arena (a
        // cross-server create race), ending the loser may have unmapped the winner — without
        // this, the join gate for the arena would stay dropped until the session churned.
        byArena.put(session.getArenaName().toLowerCase(Locale.ROOT), session);
        // The policy can genuinely change mid-session (PARTY → CODE conversion on the host's
        // server) — mirror it here so every other backend's join gate agrees.
        try {
            JoinPolicy rowPolicy = JoinPolicy.valueOf(row.policy());
            if (rowPolicy != session.getJoinPolicy()) session.setJoinPolicy(rowPolicy);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // unknown/missing policy in the row — keep the local value
        }
        String old = session.getJoinCode();
        String fresh = row.joinCode();
        if (old != null && (fresh == null || !old.equalsIgnoreCase(fresh))) {
            byCode.remove(old.toLowerCase(Locale.ROOT), session.getSessionId());
        }
        session.setJoinCode(fresh);
        if (fresh != null) byCode.put(fresh.toLowerCase(Locale.ROOT), session.getSessionId());
        session.setPublic(row.isPublic());
        session.setAutoSummon(row.autoSummon());
        // Only rebuild the settings object when the blob actually changed, so an in-flight
        // local edit isn't churned by every poll.
        if (!session.getSettings().sameAs(row.settings())) {
            session.setSettings(SessionSettings.fromJson(row.settings()));
        }
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
        session.setSettings(SessionSettings.fromJson(row.settings()));
        return session;
    }

    private Database.SessionRow toRow(PrivateSession s, long updatedAt) {
        return new Database.SessionRow(
                s.getSessionId(), s.getOwner(), s.getArenaName(), s.getJoinPolicy().name(),
                s.getJoinCode(), s.isPublic(), s.isAutoSummon(), s.getSettings().toJson(),
                db != null ? db.serverId() : null, s.getCreatedAt().toEpochMilli(), updatedAt);
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
        // Value-checked removals: when two sessions briefly exist for one arena (or one code —
        // a cross-server create race), ending the loser must not unmap the winner's entry, or
        // the arena's join gate would drop for good.
        byArena.remove(session.getArenaName().toLowerCase(Locale.ROOT), session);
        byId.remove(session.getSessionId());

        UUID owner = session.getOwner();
        if (owner != null) {
            byOwner.computeIfPresent(owner, (k, ids) -> {
                ids.remove(session.getSessionId());
                return ids.isEmpty() ? null : ids;
            });
        }

        String code = session.getJoinCode();
        if (code != null) byCode.remove(code.toLowerCase(Locale.ROOT), session.getSessionId());

        pendingWrite.remove(session.getSessionId());
        pendingDelete.remove(session.getSessionId());
        pendingWriteAt.remove(session.getSessionId());
    }

    /**
     * Validates a draft-supplied join code against the same rules generated codes obey:
     * 4..{@link #MAX_CODE_LENGTH} chars, all from {@link #ALPHABET} (after uppercasing).
     * Returns the normalized code, or null when it doesn't qualify — the caller then
     * generates a fresh one, so a crafted draft can't smuggle in a code the rest of the
     * plugin (display, DB column width, charset assumptions) was never built for.
     */
    private static String normalizeDraftCode(String code) {
        if (code == null || code.isBlank()) return null;
        String upper = code.trim().toUpperCase(Locale.ROOT);
        if (upper.length() < 4 || upper.length() > MAX_CODE_LENGTH) return null;
        for (int i = 0; i < upper.length(); i++) {
            if (ALPHABET.indexOf(upper.charAt(i)) < 0) return null;
        }
        return upper;
    }

    private static String randomChunk(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
