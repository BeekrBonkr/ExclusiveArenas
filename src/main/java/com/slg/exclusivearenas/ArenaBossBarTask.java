package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows everyone inside a private match's arena (players and spectators) a boss bar stating
 * that the match is private, its join policy, and whether its timer is currently paused.
 *
 * Bukkit's boss bar API has no true "orange" {@link BarColor} — {@link BarColor#YELLOW} is the
 * closest vanilla-supported colour, so the bar itself renders yellow while the title text uses
 * gold (&6) colour codes.
 */
public final class ArenaBossBarTask extends BukkitRunnable {

    private final PrivateSessionService sessions;
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>(); // sessionId -> bar

    public ArenaBossBarTask(PrivateSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public void run() {
        Set<UUID> active = new HashSet<>();

        for (PrivateSession session : sessions.getAllSessions()) {
            Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
            if (arena == null || !arena.exists()) continue;
            ArenaStatus status = arena.getStatus();
            if (status == ArenaStatus.STOPPED || status == ArenaStatus.RESETTING) continue;

            active.add(session.getSessionId());
            BossBar bar = bars.computeIfAbsent(session.getSessionId(),
                    id -> Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SOLID));
            bar.setTitle(buildTitle(session, arena));

            Set<Player> shouldSee = new HashSet<>(arena.getPlayers());
            shouldSee.addAll(arena.getSpectators());

            for (Player p : new HashSet<>(bar.getPlayers())) {
                if (!shouldSee.contains(p)) bar.removePlayer(p);
            }
            for (Player p : shouldSee) {
                bar.addPlayer(p);
            }
        }

        bars.keySet().removeIf(id -> {
            if (active.contains(id)) return false;
            bars.get(id).removeAll();
            return true;
        });
    }

    private String buildTitle(PrivateSession session, Arena arena) {
        String policy;
        if (session.getJoinPolicy() == JoinPolicy.CODE) {
            if (session.isPublic()) {
                String code = session.getJoinCode() != null ? session.getJoinCode() : "—";
                policy = "Code: " + code;
            } else {
                policy = "Locked"; // hide the code entirely while the match isn't accepting joins
            }
        } else {
            policy = "Party Only";
        }

        String title = "&6&lPRIVATE MATCH &8• &e" + policy;
        if (arena.getStatus() == ArenaStatus.RUNNING && !arena.isIngameTimerTicking()) {
            title += " &8• &c⏸ Timer Paused";
        }
        return ItemUtil.color(title);
    }

    /** Clears every boss bar this task created. Call on plugin disable/reload. */
    public void shutdown() {
        for (BossBar bar : bars.values()) bar.removeAll();
        bars.clear();
    }
}
