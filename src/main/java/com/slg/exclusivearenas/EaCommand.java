package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaTimeType;
import de.marcely.bedwars.api.arena.ArenaWeatherType;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.game.shop.ShopItem;
import de.marcely.bedwars.api.game.shop.ShopPage;
import de.marcely.bedwars.api.game.spawner.DropType;
import de.marcely.bedwars.api.hook.PartiesHook;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The /ea command. Every action a host can click in the menus has a subcommand equivalent
 * here — same guards, same messages, same {@link ExclusiveArenasPlugin#runArenaAction
 * local-or-relayed} execution — so power users and command blocks never need the GUI.
 */
public final class EaCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_USE = "exclusivearenas.command";

    /** Join-code brute-force throttle: after this many failed code attempts within the
     *  window, further attempts are refused for a short cooldown. A code at the minimum
     *  allowed length (4 chars, ~1M combinations) is otherwise guessable by an unthrottled
     *  bot. Entries are pruned lazily (on a successful join, or once fully lapsed). */
    private static final int MAX_FAILED_JOINS = 3;
    private static final long FAIL_WINDOW_MILLIS = 60_000L;
    private static final long JOIN_COOLDOWN_MILLIS = 10_000L;

    private static final class JoinAttempts {
        int failures;
        long windowStart;
        long cooldownUntil;
    }

    /** Only touched on the main thread (see {@link #handleJoin}'s runTask hop). */
    private final Map<java.util.UUID, JoinAttempts> joinAttempts = new java.util.HashMap<>();

    private final ExclusiveArenasPlugin plugin;
    private final DraftService drafts;
    private final PrivateSessionService sessions;
    private final JoinTicketService tickets;
    private final GuiManager gui;

    public EaCommand(ExclusiveArenasPlugin plugin,
                     DraftService drafts,
                     PrivateSessionService sessions,
                     JoinTicketService tickets,
                     GuiManager gui) {
        this.plugin = plugin;
        this.drafts = drafts;
        this.sessions = sessions;
        this.tickets = tickets;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("general.players-only"));
            return true;
        }
        if (!p.hasPermission(PERM_USE)) {
            p.sendMessage(Lang.msg("general.no-permission"));
            return true;
        }

        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "menu" -> gui.openMainMenu(p);

            case "arena", "create", "builder" -> {
                if (args.length >= 2) {
                    boolean join = args.length < 3 || !args[2].equalsIgnoreCase("nojoin");
                    plugin.createAndJoinByMapName(p, args[1], join);
                } else {
                    plugin.openBuilderMenu(p);
                }
            }

            case "list", "arenas" -> gui.openArenaList(p, 0);

            case "help" -> gui.openHelp(p);

            case "admin" -> {
                if (!p.hasPermission(GuiManager.ADMIN_PERM)) {
                    p.sendMessage(Lang.msg("general.no-permission-admin"));
                    return true;
                }
                gui.openAdminList(p, 0);
            }

            case "reload" -> {
                if (!p.hasPermission(GuiManager.ADMIN_PERM)) {
                    p.sendMessage(Lang.msg("general.no-permission-admin"));
                    return true;
                }
                plugin.reload();
                p.sendMessage(Lang.msg("general.reloaded"));
            }

            case "lobby", "controls" -> {
                Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(p);
                if (arena == null) { p.sendMessage(Lang.msg("general.not-in-arena")); return true; }
                PrivateSession session = sessions.getByArena(arena);
                if (session == null) { p.sendMessage(Lang.msg("general.not-private-match")); return true; }
                // Owner-or-admin only, like every other host command — Match Controls' status
                // card shows the live join code, which must never leak to a mere occupant.
                boolean admin = p.hasPermission(GuiManager.ADMIN_PERM);
                if (!p.getUniqueId().equals(session.getOwner()) && !admin) {
                    p.sendMessage(Lang.msg("host.only-host-menu"));
                    return true;
                }
                gui.openControls(p, session, admin);
            }

            case "join" -> handleJoin(p, args.length >= 2 ? args[1].trim() : null);

            case "start" -> {
                PrivateSession session = requireHostedSession(p);
                if (session == null) return true;
                plugin.requestStartMatch(p, session);
            }

            case "end" -> {
                PrivateSession session = requireHostedSession(p);
                if (session == null) return true;
                plugin.requestEndMatch(p, session);
            }

            case "summon" -> {
                PrivateSession session = requireHostedSession(p);
                if (session == null) return true;
                if (session.getJoinPolicy() != JoinPolicy.PARTY) {
                    p.sendMessage(Lang.msg("host.summon-party-only"));
                    return true;
                }
                plugin.summonPartyToArena(p, session);
            }

            // ── Match Controls buttons ──────────────────────────────────────────

            case "goto" -> {
                PrivateSession session = requireHostedSession(p);
                if (session == null) return true;
                tickets.grant(p.getUniqueId(), session.getSessionId(), session.getArenaName());
                plugin.sendPlayerToArena(p, session.getArenaName());
            }

            case "kick" -> {
                PrivateSession session = requireHostedSession(p);
                if (session == null) return true;
                boolean keepHost = args.length >= 2 && args[1].equalsIgnoreCase("keephost");
                plugin.runArenaAction(p, session, RemoteCommandService.Type.KICK_ALL,
                        keepHost ? RemoteCommandService.PAYLOAD_KEEP_HOST : null);
            }

            case "code" -> {
                PrivateSession session = requireHostedSession(p);
                if (session == null) return true;
                if (session.getJoinPolicy() != JoinPolicy.CODE) {
                    p.sendMessage(Lang.msg("cmd.code-only"));
                    return true;
                }
                plugin.regenerateJoinCode(session);
                // The regen only broadcasts inside the arena — the host may be elsewhere.
                p.sendMessage(Lang.msg("match.code-regenerated", "%code%", session.getJoinCode()));
            }

            case "public" -> {
                PrivateSession session = requireHostedSession(p);
                if (session == null) return true;
                if (session.getJoinPolicy() != JoinPolicy.CODE) {
                    p.sendMessage(Lang.msg("cmd.code-only"));
                    return true;
                }
                boolean target;
                if (args.length < 2) {
                    target = !session.isPublic(); // bare /ea public toggles
                } else if (args[1].equalsIgnoreCase("on")) {
                    target = true;
                } else if (args[1].equalsIgnoreCase("off")) {
                    target = false;
                } else {
                    p.sendMessage(Lang.msg("cmd.public-usage"));
                    return true;
                }
                sessions.setSessionPublic(session, target);
                p.sendMessage(Lang.msg(target ? "match.public-opened" : "match.public-locked"));
            }

            case "team" -> handleTeam(p, args);

            // ── Quick actions ───────────────────────────────────────────────────

            case "regen" -> runQuick(p, RemoteCommandService.Type.QUICK_REGEN);
            case "heal" -> runQuick(p, RemoteCommandService.Type.QUICK_HEAL);
            case "drop" -> runQuick(p, RemoteCommandService.Type.QUICK_DROP);
            case "beds" -> runQuick(p, RemoteCommandService.Type.QUICK_BEDS);
            case "clearitems" -> runQuick(p, RemoteCommandService.Type.QUICK_CLEAR);
            case "skipevent" -> runQuick(p, RemoteCommandService.Type.QUICK_SKIP_EVENT);
            case "balance" -> runQuick(p, RemoteCommandService.Type.QUICK_BALANCE_TEAMS);
            case "trigtrap" -> runQuick(p, RemoteCommandService.Type.QUICK_TRIGGER_TRAP);
            case "cleartraps" -> runQuick(p, RemoteCommandService.Type.QUICK_CLEAR_TRAPS);
            case "resetupgrades" -> runQuick(p, RemoteCommandService.Type.QUICK_RESET_UPGRADES);
            case "freeze" -> runQuick(p, RemoteCommandService.Type.QUICK_TOGGLE_FREEZE);
            case "rejoinall" -> runQuick(p, RemoteCommandService.Type.QUICK_FORCE_REJOIN);
            case "forcewin" -> handleForceWin(p, args);
            case "swapteams" -> handleSwapTeams(p, args);
            case "buff" -> handleBuff(p, args);
            case "border" -> handleRevealBorder(p);

            // ── Arena Modifiers editors ──────────────────────────────────────────

            case "timeline" -> handleTimeline(p, args);
            case "shop" -> handleShop(p, args);
            case "preset", "presets" -> handlePreset(p, args);
            case "weather" -> handleWeather(p, args);
            case "time" -> handleTime(p, args);
            case "teamsize" -> handleTeamSize(p, args);

            default -> p.sendMessage(Lang.msg("general.unknown-subcommand"));
        }
        return true;
    }

    private void runQuick(Player p, RemoteCommandService.Type type) {
        PrivateSession session = requireHostedSession(p);
        if (session == null) return;
        plugin.runArenaAction(p, session, type);
    }

    private void handleForceWin(Player p, String[] args) {
        PrivateSession session = requireHostedSession(p);
        if (session == null) return;
        if (args.length < 2) {
            p.sendMessage(Lang.msg("cmd.forcewin-usage"));
            return;
        }
        // Best-effort: only checkable when the arena happens to be loaded on this server — the
        // relay path (arena hosted elsewhere) can't validate before sending, same as every other
        // quick action's relay path.
        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (local != null && local.exists() && teamByLocalName(local, args[1]) == null) {
            p.sendMessage(Lang.msg("cmd.team-unknown", "%team%", args[1]));
            return;
        }
        plugin.runArenaAction(p, session, RemoteCommandService.Type.QUICK_FORCE_WIN, args[1]);
    }

    private void handleSwapTeams(Player p, String[] args) {
        PrivateSession session = requireHostedSession(p);
        if (session == null) return;
        if (args.length < 3) {
            p.sendMessage(Lang.msg("cmd.swapteams-usage"));
            return;
        }
        Arena local = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (local != null && local.exists()) {
            String bad = teamByLocalName(local, args[1]) == null ? args[1]
                    : teamByLocalName(local, args[2]) == null ? args[2] : null;
            if (bad != null) {
                p.sendMessage(Lang.msg("cmd.team-unknown", "%team%", bad));
                return;
            }
        }
        plugin.runArenaAction(p, session, RemoteCommandService.Type.QUICK_SWAP_TEAMS, args[1] + ":" + args[2]);
    }

    private static Team teamByLocalName(Arena arena, String name) {
        for (Team team : arena.getEnabledTeams()) {
            if (team.name().equalsIgnoreCase(name)) return team;
        }
        return null;
    }

    private static final Map<String, String> BUFF_SHORTHANDS = Map.of(
            "speed", "SPEED:1:60", "jump", "JUMP:1:60",
            "regen", "REGENERATION:1:60", "strength", "STRENGTH:1:60");

    private void handleBuff(Player p, String[] args) {
        PrivateSession session = requireHostedSession(p);
        if (session == null) return;
        if (args.length < 2) {
            p.sendMessage(Lang.msg("cmd.buff-usage"));
            return;
        }
        String payload = BUFF_SHORTHANDS.get(args[1].toLowerCase(Locale.ROOT));
        if (payload == null) {
            // Advanced form: /ea buff <potion_type> [amplifier] [seconds]. Validate here —
            // the executor's QUICK_GRANT_EFFECT branch drops a malformed payload silently
            // (it stays as a defensive backstop for relayed payloads), so without this the
            // player would get no feedback at all on a typo.
            String type = args[1].toUpperCase(Locale.ROOT);
            // Same lookup the executor resolves the payload with (see runArenaAction).
            if (org.bukkit.potion.PotionEffectType.getByName(type) == null) {
                p.sendMessage(Lang.msg("cmd.buff-unknown-potion", "%potion%", args[1]));
                return;
            }
            Integer amplifier = args.length >= 3 ? parseIntOrNull(args[2]) : Integer.valueOf(0);
            if (amplifier == null || amplifier < 0 || amplifier > 9) {
                p.sendMessage(Lang.msg("cmd.buff-bad-amplifier", "%value%", args[2]));
                return;
            }
            Integer seconds = args.length >= 4 ? parseIntOrNull(args[3]) : Integer.valueOf(30);
            if (seconds == null || seconds < 1 || seconds > 3600) {
                p.sendMessage(Lang.msg("cmd.buff-bad-seconds", "%value%", args[3]));
                return;
            }
            payload = type + ":" + amplifier + ":" + seconds;
        }
        plugin.runArenaAction(p, session, RemoteCommandService.Type.QUICK_GRANT_EFFECT, payload);
    }

    private void handleRevealBorder(Player p) {
        PrivateSession session = requireHostedSession(p);
        if (session == null) return;
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena == null || !arena.exists()) {
            p.sendMessage(Lang.msg("teams.requires-local"));
            return;
        }
        plugin.getQuickActions().revealBorder(p, arena);
    }

    // ── /ea team <player> <team> ─────────────────────────────────────────────────

    private void handleTeam(Player p, String[] args) {
        PrivateSession session = requireHostedSession(p);
        if (session == null) return;
        if (args.length < 3) {
            p.sendMessage(Lang.msg("cmd.team-usage"));
            return;
        }

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena == null || !arena.exists()) {
            p.sendMessage(Lang.msg("teams.requires-local"));
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null || !arena.getPlayers().contains(target)) {
            p.sendMessage(Lang.msg("cmd.team-player-not-found", "%player%", args[1]));
            return;
        }

        Team team = null;
        for (Team t : arena.getEnabledTeams()) {
            if (t.name().equalsIgnoreCase(args[2])) { team = t; break; }
        }
        if (team == null) {
            p.sendMessage(Lang.msg("cmd.team-unknown", "%team%", args[2]));
            return;
        }

        // Lobby/capacity checks and all feedback live in the shared move path.
        plugin.moveArenaPlayersToTeam(p, session, team, Set.of(target.getUniqueId()));
    }

    // ── /ea teamsize ─────────────────────────────────────────────────────────────

    private void handleTeamSize(Player p, String[] args) {
        PrivateSession session = requireHostedSession(p);
        if (session == null) return;

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena == null || !arena.exists()) {
            p.sendMessage(Lang.msg("teams.requires-local"));
            return;
        }
        if (!plugin.canChangeTeamSize(arena)) {
            p.sendMessage(Lang.msg("teamsize.locked"));
            return;
        }
        if (args.length < 2) {
            p.sendMessage(Lang.msg("cmd.teamsize-usage"));
            return;
        }

        Integer original = session.getOriginalPlayersPerTeam();
        int fallback = original != null ? original : arena.getPlayersPerTeam();

        Integer next;
        if (args[1].equalsIgnoreCase("reset")) {
            next = null;
        } else {
            int parsed;
            try {
                parsed = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                p.sendMessage(Lang.msg("cmd.teamsize-usage"));
                return;
            }
            int clamped = Math.max(GuiManager.MIN_PLAYERS_PER_TEAM, Math.min(GuiManager.MAX_PLAYERS_PER_TEAM, parsed));
            next = clamped != fallback ? clamped : null;
        }

        session.getSettings().setPlayersPerTeam(next);
        sessions.saveSettings(session);
        plugin.applyPlayersPerTeamOverride(arena, session);
        p.sendMessage(Lang.msg("teamsize.changed", "%amount%", String.valueOf(next != null ? next : fallback)));
    }

    // ── /ea weather / /ea time ────────────────────────────────────────────────────

    private void handleWeather(Player p, String[] args) {
        PrivateSession session = requireHostedSession(p);
        if (session == null) return;

        ArenaWeatherType type = switch (args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "") {
            case "clear" -> ArenaWeatherType.CLEAR;
            case "rain" -> ArenaWeatherType.RAINING;
            case "off" -> ArenaWeatherType.UNTOUCHED;
            default -> null;
        };
        if (type == null) {
            p.sendMessage(Lang.msg("cmd.weather-usage"));
            return;
        }

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena == null || !arena.exists()) {
            p.sendMessage(Lang.msg("teams.requires-local"));
            return;
        }
        session.getSettings().setWeatherType(type == ArenaWeatherType.UNTOUCHED ? null : type.name());
        sessions.saveSettings(session);
        plugin.applyEnvironmentOverride(arena, session);
        p.sendMessage(Lang.msg("environment.weather-changed", "%weather%", type.name()));
    }

    private void handleTime(Player p, String[] args) {
        PrivateSession session = requireHostedSession(p);
        if (session == null) return;

        ArenaTimeType type = switch (args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "") {
            case "noon" -> ArenaTimeType.NOON;
            case "sunset" -> ArenaTimeType.SUNSET;
            case "night" -> ArenaTimeType.NIGHT;
            case "off" -> ArenaTimeType.UNTOUCHED;
            default -> null;
        };
        if (type == null) {
            p.sendMessage(Lang.msg("cmd.time-usage"));
            return;
        }

        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena == null || !arena.exists()) {
            p.sendMessage(Lang.msg("teams.requires-local"));
            return;
        }
        session.getSettings().setTimeType(type == ArenaTimeType.UNTOUCHED ? null : type.name());
        sessions.saveSettings(session);
        plugin.applyEnvironmentOverride(arena, session);
        p.sendMessage(Lang.msg("environment.time-changed", "%time%", type.name()));
    }

    // ── /ea timeline … ───────────────────────────────────────────────────────────

    private void handleTimeline(Player p, String[] args) {
        PrivateSession session = requireHostedSession(p);
        if (session == null) return;

        TimelineService timelines = plugin.getTimelineService();
        if (!timelines.isEnabled()) {
            p.sendMessage(Lang.msg("cmd.timeline-disabled"));
            return;
        }

        String op = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (op) {
            case "list" -> {
                p.sendMessage(Lang.msg("cmd.timeline-header", "%arena%", session.getArenaName()));
                for (SessionSettings.TimelineEntry entry : timelines.effectiveTimeline(session.getSettings())) {
                    TimelineService.Definition def = timelines.definitionFor(entry);
                    p.sendMessage(Lang.msg("cmd.timeline-line",
                            "%time%", TimelineService.format(entry.seconds()),
                            "%event%", def != null ? def.name() : entry.id(),
                            "%id%", entry.id()));
                }
            }

            case "add" -> {
                if (!timelineEditable(p, session)) return;
                if (args.length < 3) { p.sendMessage(Lang.msg("cmd.timeline-add-usage")); return; }
                String id = resolveEventId(timelines, session, args[2]);
                if (id == null) {
                    p.sendMessage(Lang.msg("cmd.timeline-unknown-event", "%id%", args[2]));
                    return;
                }
                TimelineService.Definition def = timelines.definition(id);
                // A MISSING time keeps the event's default; a present-but-unparseable one is
                // rejected (same as move/set) rather than silently substituting the default.
                int time;
                if (args.length >= 4) {
                    Integer parsed = parseDuration(args[3]);
                    if (parsed == null) {
                        p.sendMessage(Lang.msg("cmd.bad-time", "%value%", args[3]));
                        return;
                    }
                    time = parsed;
                } else {
                    time = def != null ? def.defaultSeconds() : 60;
                }
                if (!timelines.addEvent(session.getSettings(), id, time)) {
                    p.sendMessage(Lang.msg("timeline.add-failed"));
                    return;
                }
                sessions.saveSettings(session);
                p.sendMessage(Lang.msg("timeline.added", "%event%", def != null ? def.name() : id));
            }

            case "custom" -> {
                if (!timelineEditable(p, session)) return;

                TimelineService.Type type = args.length >= 3 ? parseCustomType(args[2]) : null;
                if (type == null) { p.sendMessage(Lang.msg("cmd.timeline-custom-usage")); return; }

                // Value-less types (heal_all, clear_items, …) skip the value argument entirely —
                // "/ea timeline custom heal_all 10:00" instead of needing a placeholder value.
                int minArgs = TimelineService.requiresValue(type) ? 5 : 4;
                if (args.length < minArgs) { p.sendMessage(Lang.msg("cmd.timeline-custom-usage")); return; }

                Integer time = parseDuration(args[args.length - 1]);
                if (time == null) {
                    p.sendMessage(Lang.msg("cmd.bad-time", "%value%", args[args.length - 1]));
                    return;
                }
                // The value itself may contain spaces (an announcement message) — everything
                // between the type and the trailing time is joined back together.
                String value = TimelineService.requiresValue(type)
                        ? String.join(" ", Arrays.asList(args).subList(3, args.length - 1))
                        : "";

                SessionSettings.TimelineEntry entry =
                        timelines.addCustomEvent(session.getSettings(), type, value, time);
                if (entry == null) {
                    p.sendMessage(Lang.msg("timeline.add-failed"));
                    return;
                }
                sessions.saveSettings(session);
                TimelineService.Definition def = timelines.definitionFor(entry);
                p.sendMessage(Lang.msg("timeline.added", "%event%", def != null ? def.name() : entry.id()));
            }

            case "move", "set" -> {
                if (!timelineEditable(p, session)) return;
                if (args.length < 4) { p.sendMessage(Lang.msg("cmd.timeline-usage")); return; }
                String id = resolveEventId(timelines, session, args[2]);
                if (id == null) {
                    p.sendMessage(Lang.msg("cmd.timeline-unknown-event", "%id%", args[2]));
                    return;
                }

                int delta;
                if (op.equals("move")) {
                    Integer d = parseDelta(args[3]);
                    if (d == null || d == 0) {
                        p.sendMessage(Lang.msg("cmd.bad-delta", "%value%", args[3]));
                        return;
                    }
                    delta = d;
                } else {
                    Integer target = parseDuration(args[3]);
                    if (target == null) {
                        p.sendMessage(Lang.msg("cmd.bad-time", "%value%", args[3]));
                        return;
                    }
                    delta = target - currentEventTime(timelines, session, id);
                }

                int newTime = timelines.moveEvent(session.getSettings(), id, delta);
                if (newTime < 0) {
                    p.sendMessage(Lang.msg("cmd.timeline-unknown-event", "%id%", args[2]));
                    return;
                }
                sessions.saveSettings(session);
                TimelineService.Definition def = timelines.definitionFor(session.getSettings(), id);
                boolean isEnd = def != null && def.type() == TimelineService.Type.MATCH_END;
                p.sendMessage(Lang.msg(isEnd ? "timeline.end-moved" : "timeline.moved",
                        "%event%", def != null ? def.name() : id,
                        "%time%", TimelineService.format(newTime)));
            }

            case "delete", "remove" -> {
                if (!timelineEditable(p, session)) return;
                if (args.length < 3) { p.sendMessage(Lang.msg("cmd.timeline-usage")); return; }
                String id = resolveEventId(timelines, session, args[2]);
                if (id == null) {
                    p.sendMessage(Lang.msg("cmd.timeline-unknown-event", "%id%", args[2]));
                    return;
                }
                TimelineService.Definition def = timelines.definitionFor(session.getSettings(), id);
                if (def != null && def.type() == TimelineService.Type.MATCH_END) {
                    p.sendMessage(Lang.msg("timeline.cannot-delete-end"));
                    return;
                }
                if (timelines.deleteEvent(session.getSettings(), id)) {
                    sessions.saveSettings(session);
                    p.sendMessage(Lang.msg("timeline.deleted",
                            "%event%", def != null ? def.name() : id));
                }
            }

            case "reset" -> {
                if (!timelineEditable(p, session)) return;
                timelines.resetTimeline(session.getSettings());
                sessions.saveSettings(session);
                p.sendMessage(Lang.msg("timeline.reset"));
            }

            default -> p.sendMessage(Lang.msg("cmd.timeline-usage"));
        }
    }

    /**
     * The timeline engine snapshots a match's schedule once at round start and never re-reads
     * it, so an edit made while the round is already RUNNING would silently have no effect
     * until the next round despite the command confirming success — same reasoning as
     * team-select being gated to the lobby.
     */
    private boolean timelineEditable(Player p, PrivateSession session) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        if (arena != null && !arena.getStatus().isLobby()) {
            p.sendMessage(Lang.msg("timeline.lobby-only"));
            return false;
        }
        return true;
    }

    /**
     * Case-insensitive event-id lookup against the full catalog, so tab-completed and
     * hand-typed ids both work — plus an exact (case-sensitive) match against the session's own
     * currently-scheduled entries, so a host-authored custom event's generated id (shown by
     * {@code /ea timeline list}, never itself in the catalog) can still be moved/deleted by id.
     */
    private static String resolveEventId(TimelineService timelines, PrivateSession session, String raw) {
        for (String id : timelines.definitionIds()) {
            if (id.equalsIgnoreCase(raw)) return id;
        }
        for (SessionSettings.TimelineEntry entry : timelines.effectiveTimeline(session.getSettings())) {
            if (entry.id().equals(raw)) return entry.id();
        }
        return null;
    }

    private static int currentEventTime(TimelineService timelines, PrivateSession session, String id) {
        for (SessionSettings.TimelineEntry entry : timelines.effectiveTimeline(session.getSettings())) {
            if (entry.id().equals(id)) return entry.seconds();
        }
        return 0;
    }

    // ── /ea shop … ───────────────────────────────────────────────────────────────

    private void handleShop(Player p, String[] args) {
        PrivateSession session = requireHostedSession(p);
        if (session == null) return;

        String op = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (op) {
            case "list" -> {
                var overrides = session.getSettings().getShopOverrides();
                if (overrides.isEmpty()) {
                    p.sendMessage(Lang.msg("cmd.shop-none"));
                    return;
                }
                p.sendMessage(Lang.msg("cmd.shop-header", "%arena%", session.getArenaName()));
                for (var entry : overrides.entrySet()) {
                    SessionSettings.ShopOverride o = entry.getValue();
                    String name = shopItemName(entry.getKey());
                    if (o.isDisabled()) {
                        p.sendMessage(Lang.msg("cmd.shop-line-disabled",
                                "%item%", name, "%id%", entry.getKey()));
                    }
                    if (o.hasPriceOverride()) {
                        p.sendMessage(Lang.msg("cmd.shop-line-price",
                                "%item%", name, "%id%", entry.getKey(),
                                "%amount%", String.valueOf(o.getPrice()),
                                "%currency%", GuiManager.currencyLabel(o.getCurrency())));
                    }
                }
            }

            case "disable", "enable" -> {
                if (args.length < 3) { p.sendMessage(Lang.msg("cmd.shop-usage")); return; }
                ShopItem item = resolveShopItem(args[2]);
                if (item == null) {
                    p.sendMessage(Lang.msg("cmd.shop-unknown-item", "%item%", args[2]));
                    return;
                }
                boolean disable = op.equals("disable");
                SessionSettings.ShopOverride override =
                        session.getSettings().getOrCreateShopOverride(item.getId());
                override.setDisabled(disable);
                session.getSettings().pruneShopOverride(item.getId());
                sessions.saveSettings(session);
                p.sendMessage(Lang.msg(disable ? "shop.item-disabled" : "shop.item-enabled",
                        "%item%", shopItemName(item.getId())));
            }

            case "price" -> {
                if (args.length < 4) { p.sendMessage(Lang.msg("cmd.shop-usage")); return; }
                ShopItem item = resolveShopItem(args[2]);
                if (item == null) {
                    p.sendMessage(Lang.msg("cmd.shop-unknown-item", "%item%", args[2]));
                    return;
                }
                int amount;
                try {
                    amount = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    p.sendMessage(Lang.msg("cmd.bad-amount", "%value%", args[3]));
                    return;
                }
                if (amount < 1 || amount > 64) {
                    p.sendMessage(Lang.msg("cmd.bad-amount", "%value%", args[3]));
                    return;
                }

                String currencyId = args.length >= 5 ? args[4]
                        : GuiManager.defaultPriceCurrency(item);
                DropType currency = null;
                for (DropType type : BedwarsAPI.getGameAPI().getDropTypes()) {
                    if (type.getId().equalsIgnoreCase(currencyId)) { currency = type; break; }
                }
                if (currency == null) {
                    p.sendMessage(Lang.msg("cmd.unknown-currency", "%currency%", currencyId));
                    return;
                }

                SessionSettings.ShopOverride override =
                        session.getSettings().getOrCreateShopOverride(item.getId());
                override.setPrice(amount, currency.getId());
                sessions.saveSettings(session);
                p.sendMessage(Lang.msg("shop.price-set",
                        "%item%", shopItemName(item.getId()),
                        "%amount%", String.valueOf(amount),
                        "%currency%", GuiManager.currencyLabel(currency.getId())));
            }

            case "resetprice" -> {
                if (args.length < 3) { p.sendMessage(Lang.msg("cmd.shop-usage")); return; }
                ShopItem item = resolveShopItem(args[2]);
                if (item == null) {
                    p.sendMessage(Lang.msg("cmd.shop-unknown-item", "%item%", args[2]));
                    return;
                }
                SessionSettings.ShopOverride override =
                        session.getSettings().getShopOverride(item.getId());
                if (override != null) {
                    override.setPrice(null, null);
                    session.getSettings().pruneShopOverride(item.getId());
                    sessions.saveSettings(session);
                }
                p.sendMessage(Lang.msg("shop.price-reset", "%item%", shopItemName(item.getId())));
            }

            case "reset" -> {
                session.getSettings().clearShopOverrides();
                sessions.saveSettings(session);
                p.sendMessage(Lang.msg("shop.reset-all"));
            }

            default -> p.sendMessage(Lang.msg("cmd.shop-usage"));
        }
    }

    /** Exact-id lookup first, then a case-insensitive sweep over every page. */
    private static ShopItem resolveShopItem(String raw) {
        ShopItem exact = BedwarsAPI.getGameAPI().getShopItemById(raw);
        if (exact != null) return exact;
        for (ShopPage page : BedwarsAPI.getGameAPI().getShopPages()) {
            for (ShopItem item : page.getItems()) {
                if (item.getId().equalsIgnoreCase(raw)) return item;
            }
        }
        return null;
    }

    private static String shopItemName(String itemId) {
        ShopItem item = BedwarsAPI.getGameAPI().getShopItemById(itemId);
        if (item == null) return itemId;
        String plain = org.bukkit.ChatColor.stripColor(ItemUtil.color(item.getDisplayName()));
        return plain == null || plain.isBlank() ? itemId : plain;
    }

    // ── /ea preset … ─────────────────────────────────────────────────────────────

    private void handlePreset(Player p, String[] args) {
        String op = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (op) {
            case "list" -> plugin.getPresetService().list(p.getUniqueId(), presets -> {
                if (presets.isEmpty()) {
                    p.sendMessage(Lang.msg("cmd.preset-none"));
                    return;
                }
                p.sendMessage(Lang.msg("cmd.preset-header"));
                for (String name : presets.keySet()) {
                    p.sendMessage(Lang.msg("cmd.preset-line", "%name%", name));
                }
            });

            case "save" -> {
                PrivateSession session = requireHostedSession(p);
                if (session == null) return;
                String requested = args.length >= 3 ? args[2] : null;
                if (requested != null && !PresetService.isValidName(requested)) {
                    p.sendMessage(Lang.msg("cmd.preset-bad-name",
                            "%max%", String.valueOf(PresetService.MAX_NAME_LENGTH)));
                    return;
                }
                // Presets belong to the arena's host, not whoever ran the command — matters
                // when a privileged admin manages another player's session.
                plugin.getPresetService().list(session.getOwner(), presets -> {
                    // Re-fetch by id: a DB sync may have replaced (or ended) the session
                    // while the preset list loaded off-thread.
                    PrivateSession live = sessions.getById(session.getSessionId());
                    if (live == null) {
                        p.sendMessage(Lang.msg("general.match-gone"));
                        return;
                    }
                    String name = requested != null
                            ? PresetService.existingName(presets, requested) : PresetService.nextFreeName(presets);
                    // Overwriting an existing preset is fine; only NEW names count toward the cap.
                    if (name == null && requested != null && presets.size() < PresetService.MAX_PRESETS) {
                        name = requested;
                    }
                    if (name == null) {
                        p.sendMessage(Lang.msg("presets.limit",
                                "%max%", String.valueOf(PresetService.MAX_PRESETS)));
                        return;
                    }
                    String savedName = name;
                    plugin.getPresetService().save(live.getOwner(), savedName, live.getSettings().toJson(), ok -> {
                        if (!p.isOnline()) return;
                        p.sendMessage(ok ? Lang.msg("presets.saved", "%name%", savedName)
                                : Lang.msg("presets.save-failed", "%name%", savedName));
                    });
                });
            }

            case "apply", "load" -> {
                PrivateSession session = requireHostedSession(p);
                if (session == null) return;
                if (args.length < 3) { p.sendMessage(Lang.msg("cmd.preset-usage")); return; }
                String requested = args[2];
                plugin.getPresetService().list(session.getOwner(), presets -> {
                    String name = PresetService.existingName(presets, requested);
                    if (name == null) {
                        p.sendMessage(Lang.msg("cmd.preset-unknown", "%name%", requested));
                        return;
                    }
                    PrivateSession live = sessions.getById(session.getSessionId());
                    if (live == null) {
                        p.sendMessage(Lang.msg("general.match-gone"));
                        return;
                    }
                    sessions.applyPresetSettings(live, presets.get(name));
                    p.sendMessage(Lang.msg("presets.applied",
                            "%name%", name, "%arena%", live.getArenaName()));
                });
            }

            case "delete", "remove" -> {
                if (args.length < 3) { p.sendMessage(Lang.msg("cmd.preset-usage")); return; }
                String requested = args[2];
                plugin.getPresetService().list(p.getUniqueId(), presets -> {
                    String name = PresetService.existingName(presets, requested);
                    if (name == null) {
                        p.sendMessage(Lang.msg("cmd.preset-unknown", "%name%", requested));
                        return;
                    }
                    plugin.getPresetService().delete(p.getUniqueId(), name);
                    p.sendMessage(Lang.msg("presets.deleted", "%name%", name));
                });
            }

            default -> p.sendMessage(Lang.msg("cmd.preset-usage"));
        }
    }

    /** Only a host-creatable timeline type (see {@link TimelineService#isCustomCreatable}) is accepted. */
    private static TimelineService.Type parseCustomType(String raw) {
        try {
            TimelineService.Type type = TimelineService.Type.valueOf(raw.toUpperCase(Locale.ROOT));
            return TimelineService.isCustomCreatable(type) ? type : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── Time parsing ─────────────────────────────────────────────────────────────

    /** Plain integer parse; null (not a fallback value) on bad input. */
    private static Integer parseIntOrNull(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Strict "m:ss" or plain-seconds parse; null (not a fallback value) on bad input. */
    private static Integer parseDuration(String raw) {
        if (raw == null || raw.isBlank()) return null;
        raw = raw.trim();
        try {
            int colon = raw.indexOf(':');
            if (colon < 0) {
                int v = Integer.parseInt(raw);
                return v < 0 ? null : v;
            }
            int minutes = Integer.parseInt(raw.substring(0, colon));
            int seconds = Integer.parseInt(raw.substring(colon + 1));
            if (minutes < 0 || seconds < 0 || seconds > 59) return null;
            long total = (long) minutes * 60 + seconds; // long math so a huge minutes can't wrap the int
            if (total > Integer.MAX_VALUE) return null;
            return (int) total;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@link #parseDuration} with an optional +/- sign, for relative moves. */
    private static Integer parseDelta(String raw) {
        if (raw == null || raw.isBlank()) return null;
        raw = raw.trim();
        int sign = 1;
        if (raw.startsWith("+")) {
            raw = raw.substring(1);
        } else if (raw.startsWith("-")) {
            sign = -1;
            raw = raw.substring(1);
        }
        Integer value = parseDuration(raw);
        return value == null ? null : sign * value;
    }

    // ── Join ─────────────────────────────────────────────────────────────────────

    /**
     * If the player is in a party whose leader hosts an active match, that always takes
     * priority over whatever code was typed (or lets {@code /ea join} work with no code at
     * all) — a party member's place is in their leader's match, not a different one they
     * happened to type a code for.
     */
    private void handleJoin(Player p, String code) {
        PartyResolver.getPartyMember(p, opt -> {
            PrivateSession hostSession = null;
            if (opt.isPresent()) {
                for (PartiesHook.Member leader : opt.get().getParty().getLeaders()) {
                    PrivateSession s = sessions.getByOwner(leader.getUniqueId());
                    if (s != null && s.getJoinPolicy() == JoinPolicy.PARTY) {
                        hostSession = s;
                        break;
                    }
                }
            }
            PrivateSession finalHostSession = hostSession;

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (finalHostSession != null) {
                    joinSession(p, finalHostSession);
                    return;
                }
                if (code == null || code.isBlank()) {
                    p.sendMessage(Lang.msg("join.usage"));
                    return;
                }
                long waitMillis = joinThrottleRemaining(p);
                if (waitMillis > 0) {
                    p.sendMessage(Lang.msg("join.too-many-attempts",
                            "%seconds%", String.valueOf((waitMillis + 999) / 1000)));
                    return;
                }
                // Session state is replicated to every server, so the code resolves locally
                // even when the arena lives on another server.
                PrivateSession session = sessions.getByJoinCode(code);
                if (session == null) {
                    recordFailedJoin(p);
                    p.sendMessage(Lang.msg("join.invalid-code"));
                    return;
                }
                joinSession(p, session);
            });
        });
    }

    private void joinSession(Player p, PrivateSession session) {
        if (session.getJoinPolicy() == JoinPolicy.CODE && !session.isPublic()) {
            // Deliberately the same message as an unrecognized code (join.invalid-code) rather
            // than a distinct "that one's locked" — otherwise a guesser could tell a code that
            // exists-but-is-locked apart from one that doesn't exist at all, which helps an
            // automated guesser converge faster than blind guessing would. Counts toward the
            // brute-force throttle for the same reason.
            recordFailedJoin(p);
            p.sendMessage(Lang.msg("join.invalid-code"));
            return;
        }
        joinAttempts.remove(p.getUniqueId());

        // Authorise this player (write-through to the shared DB) then route them to the arena.
        tickets.grant(p.getUniqueId(), session.getSessionId(), session.getArenaName());
        plugin.sendPlayerToArena(p, session.getArenaName());
    }

    /** Milliseconds this player must still wait before another code attempt; 0 when unthrottled. */
    private long joinThrottleRemaining(Player p) {
        JoinAttempts attempts = joinAttempts.get(p.getUniqueId());
        if (attempts == null) return 0;
        long now = System.currentTimeMillis();
        if (now < attempts.cooldownUntil) return attempts.cooldownUntil - now;
        // Lazy prune once both the failure window and any cooldown have fully lapsed.
        if (now - attempts.windowStart > FAIL_WINDOW_MILLIS) joinAttempts.remove(p.getUniqueId());
        return 0;
    }

    private void recordFailedJoin(Player p) {
        long now = System.currentTimeMillis();
        JoinAttempts attempts = joinAttempts.computeIfAbsent(p.getUniqueId(), id -> new JoinAttempts());
        if (now - attempts.windowStart > FAIL_WINDOW_MILLIS) {
            attempts.windowStart = now;
            attempts.failures = 0;
        }
        if (++attempts.failures >= MAX_FAILED_JOINS) {
            attempts.cooldownUntil = now + JOIN_COOLDOWN_MILLIS;
            // Start a fresh window once the cooldown expires.
            attempts.windowStart = now;
            attempts.failures = 0;
        }
    }

    /**
     * Returns the private match this player should be controlling: whatever they're physically
     * standing in, if it's a private match, otherwise whichever match they host — so these
     * commands also work when controlling the arena remotely, from elsewhere on the network.
     */
    private PrivateSession requireHostedSession(Player p) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(p);
        PrivateSession session = arena != null ? sessions.getByArena(arena) : null;

        if (session == null) {
            // Not physically standing in one of their own matches — fall back to "the" hosted
            // session, but only when that's unambiguous. A host running more than one match at
            // once (an elevated exclusivearenas.limit.<n>) has no way to say which one they mean
            // here, so guessing (e.g. always the first PARTY-policy match) risks silently acting
            // on the wrong arena — send them to the list instead of picking for them.
            List<PrivateSession> owned = sessions.getSessionsByOwner(p.getUniqueId());
            if (owned.size() > 1) {
                p.sendMessage(Lang.msg("host.ambiguous"));
                return null;
            }
            session = owned.isEmpty() ? null : owned.get(0);
        }
        if (session == null) {
            p.sendMessage(Lang.msg("host.not-hosting"));
            return null;
        }
        // Deliberately NOT exclusivearenas.bypass here — bypass only skips join restrictions
        // and must never grant control over someone else's match.
        boolean privileged = p.hasPermission(GuiManager.ADMIN_PERM);
        if (!p.getUniqueId().equals(session.getOwner()) && !privileged) {
            p.sendMessage(Lang.msg("host.only-host"));
            return null;
        }
        return session;
    }

    // ── Tab completion ───────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(Arrays.asList(
                    "menu", "arena", "list", "help", "lobby", "join", "start", "end", "summon",
                    "goto", "kick", "code", "public", "team", "teamsize", "weather", "time",
                    "regen", "heal", "drop", "beds", "clearitems", "skipevent",
                    "balance", "trigtrap", "cleartraps", "resetupgrades", "freeze", "rejoinall",
                    "forcewin", "swapteams", "buff", "border",
                    "timeline", "shop", "preset"));
            if (sender.hasPermission(GuiManager.ADMIN_PERM)) {
                subs.add("admin");
                subs.add("reload");
            }
            return partial(args[0], subs);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "arena", "create", "builder" -> {
                if (args.length == 2) return partial(args[1], gui.unreservedArenaNames());
                if (args.length == 3) return partial(args[2], List.of("nojoin"));
            }
            case "kick" -> {
                if (args.length == 2) return partial(args[1], List.of("keephost"));
            }
            case "public" -> {
                if (args.length == 2) return partial(args[1], List.of("on", "off"));
            }
            case "weather" -> {
                if (args.length == 2) return partial(args[1], List.of("clear", "rain", "off"));
            }
            case "time" -> {
                if (args.length == 2) return partial(args[1], List.of("noon", "sunset", "night", "off"));
            }
            case "teamsize" -> {
                if (args.length == 2) return partial(args[1], List.of("reset"));
            }
            case "team" -> {
                if (args.length == 2) return partial(args[1], hostedArenaPlayerNames(sender));
                if (args.length == 3) return partial(args[2], hostedArenaTeamNames(sender));
            }
            case "forcewin" -> {
                if (args.length == 2) return partial(args[1], hostedArenaTeamNames(sender));
            }
            case "swapteams" -> {
                if (args.length == 2 || args.length == 3) {
                    return partial(args[args.length - 1], hostedArenaTeamNames(sender));
                }
            }
            case "buff" -> {
                if (args.length == 2) return partial(args[1], List.of("speed", "jump", "regen", "strength"));
            }
            case "timeline" -> {
                if (args.length == 2) {
                    return partial(args[1], List.of("list", "add", "custom", "move", "set", "delete", "reset"));
                }
                if (args.length == 3 && List.of("move", "set", "delete", "add").contains(
                        args[1].toLowerCase(Locale.ROOT))) {
                    return partial(args[2],
                            List.copyOf(plugin.getTimelineService().definitionIds()));
                }
                if (args.length == 3 && args[1].equalsIgnoreCase("custom")) {
                    return partial(args[2], Arrays.stream(TimelineService.Type.values())
                            .filter(TimelineService::isCustomCreatable)
                            .map(t -> t.name().toLowerCase(Locale.ROOT)).toList());
                }
            }
            case "shop" -> {
                if (args.length == 2) {
                    return partial(args[1],
                            List.of("list", "disable", "enable", "price", "resetprice", "reset"));
                }
                if (args.length == 3 && List.of("disable", "enable", "price", "resetprice")
                        .contains(args[1].toLowerCase(Locale.ROOT))) {
                    return partial(args[2], shopItemIds());
                }
                if (args.length == 5 && args[1].equalsIgnoreCase("price")) {
                    return partial(args[4], dropTypeIds());
                }
            }
            case "preset", "presets" -> {
                if (args.length == 2) {
                    return partial(args[1], List.of("list", "save", "apply", "delete"));
                }
                // Names live in async storage — no completion for them.
            }
            default -> { /* no further arguments */ }
        }
        return Collections.emptyList();
    }

    private List<String> hostedArenaPlayerNames(CommandSender sender) {
        Arena arena = hostedLocalArena(sender);
        if (arena == null) return List.of();
        return arena.getPlayers().stream().map(Player::getName).toList();
    }

    private List<String> hostedArenaTeamNames(CommandSender sender) {
        Arena arena = hostedLocalArena(sender);
        if (arena == null) return List.of();
        return arena.getEnabledTeams().stream()
                .map(t -> t.name().toLowerCase(Locale.ROOT)).toList();
    }

    /** The local arena of the sender's hosted (or currently occupied private) match, if any. */
    private Arena hostedLocalArena(CommandSender sender) {
        if (!(sender instanceof Player p)) return null;
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(p);
        PrivateSession session = arena != null ? sessions.getByArena(arena) : null;
        if (session == null) {
            session = sessions.getByOwner(p.getUniqueId());
            arena = session == null ? null
                    : BedwarsAPI.getGameAPI().getArenaByExactName(session.getArenaName());
        }
        return arena != null && arena.exists() ? arena : null;
    }

    private static List<String> shopItemIds() {
        List<String> ids = new ArrayList<>();
        for (ShopPage page : BedwarsAPI.getGameAPI().getShopPages()) {
            for (ShopItem item : page.getItems()) {
                ids.add(item.getId());
            }
        }
        return ids;
    }

    private static List<String> dropTypeIds() {
        List<String> ids = new ArrayList<>();
        for (DropType type : BedwarsAPI.getGameAPI().getDropTypes()) {
            ids.add(type.getId());
        }
        return ids;
    }

    private static List<String> partial(String token, List<String> options) {
        String t = token == null ? "" : token.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(t)).toList();
    }
}
