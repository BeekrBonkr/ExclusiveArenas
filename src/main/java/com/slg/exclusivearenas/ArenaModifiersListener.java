package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.event.arena.ArenaBedBreakEvent;
import de.marcely.bedwars.api.event.arena.ArenaStatusChangeEvent;
import de.marcely.bedwars.api.event.arena.RoundStartEvent;
import de.marcely.bedwars.api.event.player.PlayerIngamePostRespawnEvent;
import de.marcely.bedwars.api.event.player.PlayerKillPlayerEvent;
import de.marcely.bedwars.api.game.spawner.DropType;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces the standing per-match rules a host sets from Arena Modifiers → Match Rules (see
 * {@link SessionSettings.ArenaModifiers}) — friendly fire, fall/explosion damage, a PvP grace
 * period, kill bounties, an alternate first-to-N-kills win condition, a shrinking world
 * border, bed-survives-once, spawn protection, extra max health, and a bonus starting kit.
 *
 * Unlike {@link QuickActionsService} (one-click, momentary), everything here reads straight
 * from the session's persisted settings at the moment the relevant event fires — nothing to
 * toggle live except world border/health, which need an explicit push since Bukkit holds
 * that state imperatively rather than us just checking it inline.
 */
public final class ArenaModifiersListener implements Listener {

    private final ExclusiveArenasPlugin plugin;
    private final PrivateSessionService sessions;

    /** "<arena>:<TEAM>" entries whose bed has already used its one-time save. */
    private final Set<String> bedSaveUsed = ConcurrentHashMap.newKeySet();
    /** Arena (lower-cased name) -> epoch ms when its PvP grace period ends. */
    private final Map<String, Long> pvpGraceUntil = new ConcurrentHashMap<>();
    /** Player -> epoch ms when their post-respawn spawn protection ends. */
    private final Map<UUID, Long> spawnProtectionUntil = new ConcurrentHashMap<>();
    /** Player -> their max health before a healthMultiplier was applied, so it can be restored. */
    private final Map<UUID, Double> originalMaxHealth = new ConcurrentHashMap<>();

    public ArenaModifiersListener(ExclusiveArenasPlugin plugin, PrivateSessionService sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    // ── Round start: starting kit, world border, health multiplier, PvP grace window ─────

    @EventHandler
    public void onRoundStart(RoundStartEvent event) {
        Arena arena = event.getArena();
        PrivateSession session = sessions.getByArena(arena);
        if (session == null) return;
        SessionSettings.ArenaModifiers mods = session.getSettings().getModifiers();

        if (mods.getPvpGraceSeconds() > 0) {
            pvpGraceUntil.put(key(arena.getName()), System.currentTimeMillis() + mods.getPvpGraceSeconds() * 1000L);
        }

        if (mods.isBonusStartingKit()) {
            for (Player p : arena.getPlayers()) giveStartingKit(p);
        }

        if (mods.getHealthMultiplier() != 1.0) {
            for (Player p : arena.getPlayers()) applyHealthMultiplier(p, mods.getHealthMultiplier());
        }

        applyWorldBorder(arena, mods);
    }

    private void giveStartingKit(Player p) {
        var section = plugin.getEaConfig().section("arena_modifiers.starting_kit");
        if (section == null) return;

        for (String entry : section.getStringList("items")) {
            String[] parts = entry.split(":");
            if (parts.length < 1) continue;
            Material mat = Material.matchMaterial(parts[0].trim().toUpperCase(Locale.ROOT));
            if (mat == null) continue;
            int amount = parts.length > 1 ? parseIntOr(parts[1], 1) : 1;
            p.getInventory().addItem(new ItemStack(mat, Math.max(1, amount)));
        }
    }

    private void applyHealthMultiplier(Player p, double multiplier) {
        var attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;
        originalMaxHealth.putIfAbsent(p.getUniqueId(), attr.getBaseValue());
        double base = originalMaxHealth.get(p.getUniqueId());
        attr.setBaseValue(Math.max(1.0, base * multiplier));
        p.setHealth(Math.min(p.getHealth(), attr.getValue()));
    }

    private void restoreHealth(Player p) {
        Double base = originalMaxHealth.remove(p.getUniqueId());
        if (base == null) return;
        var attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) attr.setBaseValue(base);
    }

    private void applyWorldBorder(Arena arena, SessionSettings.ArenaModifiers mods) {
        World world = arena.getGameWorld();
        if (world == null) return;
        WorldBorder border = world.getWorldBorder();
        if (mods.isWorldBorderShrink()) {
            int target = plugin.getEaConfig().intNum("arena_modifiers.world_border.target_size", 60);
            int seconds = plugin.getEaConfig().intNum("arena_modifiers.world_border.shrink_seconds", 480);

            // Center on the arena's own configured region rather than the world's spawn point —
            // a cloned game world's spawn location doesn't necessarily sit in the middle of the
            // actual play area.
            var min = arena.getMinRegionCorner();
            var max = arena.getMaxRegionCorner();
            double centerX = min != null && max != null ? (min.getX() + max.getX()) / 2 : world.getSpawnLocation().getX();
            double centerZ = min != null && max != null ? (min.getZ() + max.getZ()) / 2 : world.getSpawnLocation().getZ();

            border.setCenter(centerX, centerZ);
            border.setSize(Math.max(target * 4, 200)); // a generous starting size before it shrinks
            border.setSize(Math.max(10, target), Math.max(1, seconds));
        } else {
            border.setSize(60000000); // Bukkit's effective "off" — matches the vanilla default
        }
    }

    // ── Round end: reset everything that doesn't belong on the arena's next match ────────

    @EventHandler
    public void onStatusChange(ArenaStatusChangeEvent event) {
        if (event.getNewStatus() == ArenaStatus.RUNNING) return;
        Arena arena = event.getArena();
        String k = key(arena.getName());
        pvpGraceUntil.remove(k);
        bedSaveUsed.removeIf(entry -> entry.startsWith(k + ":"));

        for (Player p : arena.getPlayers()) {
            restoreHealth(p);
            spawnProtectionUntil.remove(p.getUniqueId());
        }

        World world = arena.getGameWorld();
        if (world != null) world.getWorldBorder().setSize(60000000);
    }

    // ── Friendly fire / PvP grace / spawn protection ──────────────────────────────

    // NOT ignoreCancelled: friendly fire needs to see (and un-cancel) MBedwars' own same-team
    // block, which fires at a priority we don't control — HIGHEST runs after it either way.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;

        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(victim);
        PrivateSession session = sessions.getByArena(arena);
        if (session == null) return;
        SessionSettings.ArenaModifiers mods = session.getSettings().getModifiers();

        // Friendly fire: MBedwars already blocks same-team damage on its own — only intervene
        // to explicitly ALLOW it back when a host has turned it on.
        Team attackerTeam = arena.getPlayerTeam(attacker);
        Team victimTeam = arena.getPlayerTeam(victim);
        if (mods.isFriendlyFire() && attackerTeam != null && attackerTeam.equals(victimTeam)) {
            event.setCancelled(false);
            return;
        }

        Long graceUntil = pvpGraceUntil.get(key(arena.getName()));
        if (graceUntil != null && System.currentTimeMillis() < graceUntil) {
            event.setCancelled(true);
            return;
        }

        Long protectedUntil = spawnProtectionUntil.get(victim.getUniqueId());
        if (protectedUntil != null && System.currentTimeMillis() < protectedUntil) {
            int radius = plugin.getEaConfig().intNum("arena_modifiers.spawn_protection.radius", 8);
            var spawn = victimTeam != null ? arena.getTeamSpawn(victimTeam) : null;
            if (spawn != null && spawn.distance(victim.getLocation()) <= radius) {
                event.setCancelled(true);
            }
        }
    }

    private static Player resolveAttacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler
    public void onPostRespawn(PlayerIngamePostRespawnEvent event) {
        Arena arena = event.getArena();
        PrivateSession session = sessions.getByArena(arena);
        if (session == null) return;
        int seconds = session.getSettings().getModifiers().getSpawnProtectionSeconds();
        if (seconds <= 0) return;
        spawnProtectionUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
    }

    // ── Fall / explosion damage ────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player p)) return;
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(p);
        PrivateSession session = sessions.getByArena(arena);
        if (session != null && session.getSettings().getModifiers().isNoFallDamage()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        World world = event.getLocation().getWorld();
        if (world == null || event.blockList().isEmpty()) return;
        Arena arena = arenaForWorld(world);
        PrivateSession session = sessions.getByArena(arena);
        if (session != null && session.getSettings().getModifiers().isNoExplosionBlockDamage()) {
            event.blockList().clear();
        }
    }

    private static Arena arenaForWorld(World world) {
        for (Arena arena : BedwarsAPI.getGameAPI().getArenas()) {
            if (world.equals(arena.getGameWorld())) return arena;
        }
        return null;
    }

    // ── Bed respawn once ───────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onBedBreak(ArenaBedBreakEvent event) {
        Arena arena = event.getArena();
        Team team = event.getTeam();
        PrivateSession session = sessions.getByArena(arena);
        if (session == null || team == null || !session.getSettings().getModifiers().isBedRespawnOnce()) return;

        String bedKey = key(arena.getName()) + ":" + team.name();
        if (!bedSaveUsed.add(bedKey)) return; // already used its one save — let this one through

        event.setResult(ArenaBedBreakEvent.Result.CANCEL);
        arena.broadcast(Lang.msg("modifiers.bed-saved", "%team%", team.getDisplayName(null)));
    }

    // ── Kill bounty / alternate win condition ─────────────────────────────────────

    @EventHandler
    public void onKill(PlayerKillPlayerEvent event) {
        Player killer = event.getKiller();
        if (killer == null) return;
        Arena arena = event.getArena();
        PrivateSession session = sessions.getByArena(arena);
        if (session == null) return;
        SessionSettings.ArenaModifiers mods = session.getSettings().getModifiers();

        if (mods.getKillBountyMultiplier() > 0) {
            giveBounty(killer, mods.getKillBountyMultiplier());
        }

        if (mods.getKillGoal() > 0) {
            Team team = arena.getPlayerTeam(killer);
            if (team == null) return;
            int teamKills = 0;
            for (Player p : arena.getPlayers()) {
                if (team.equals(arena.getPlayerTeam(p))) teamKills += arena.getPlayerKillHistory(p).size();
            }
            if (teamKills >= mods.getKillGoal()) {
                arena.broadcast(Lang.msg("modifiers.kill-goal-reached",
                        "%team%", team.getDisplayName(null), "%goal%", String.valueOf(mods.getKillGoal())));
                plugin.getQuickActions().forceWin(null, session, arena, team);
            }
        }
    }

    private void giveBounty(Player killer, int multiplier) {
        String id = plugin.getEaConfig().str("arena_modifiers.kill_bounty.drop_type", "iron");
        int base = plugin.getEaConfig().intNum("arena_modifiers.kill_bounty.base_amount", 4);
        DropType type = BedwarsAPI.getGameAPI().getDropTypeById(id);
        if (type == null || type.getDroppingMaterials().length == 0) return;
        ItemStack stack = type.getDroppingMaterials()[0].clone();
        stack.setAmount(Math.max(1, base * multiplier));
        killer.getInventory().addItem(stack);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static int parseIntOr(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String key(String arenaName) {
        return arenaName.toLowerCase(Locale.ROOT);
    }
}
