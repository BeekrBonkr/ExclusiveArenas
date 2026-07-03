package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The /ea command. Every action a host can click in the menus has a subcommand equivalent
 * here — same guards, same messages, same {@link ExclusiveArenasPlugin#runArenaAction
 * local-or-relayed} execution — so power users and command blocks never need the GUI.
 */
public final class EaCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_USE    = "exclusivearenas.command";
    private static final String PERM_BYPASS = "exclusivearenas.bypass";

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
                gui.openControls(p, session, p.hasPermission(GuiManager.ADMIN_PERM));
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

            // ── Arena Settings editors ──────────────────────────────────────────

            case "timeline" -> handleTimeline(p, args);
            case "shop" -> handleShop(p, args);
            case "preset", "presets" -> handlePreset(p, args);

            default -> p.sendMessage(Lang.msg("general.unknown-subcommand"));
        }
        return true;
    }

    private void runQuick(Player p, RemoteCommandService.Type type) {
        PrivateSession session = requireHostedSession(p);
        if (session == null) return;
        plugin.runArenaAction(p, session, type);
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
                    TimelineService.Definition def = timelines.definition(entry.id());
                    p.sendMessage(Lang.msg("cmd.timeline-line",
                            "%time%", TimelineService.format(entry.seconds()),
                            "%event%", def != null ? def.name() : entry.id(),
                            "%id%", entry.id()));
                }
            }

            case "move", "set" -> {
                if (args.length < 4) { p.sendMessage(Lang.msg("cmd.timeline-usage")); return; }
                String id = resolveEventId(timelines, args[2]);
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
                TimelineService.Definition def = timelines.definition(id);
                boolean isEnd = def != null && def.type() == TimelineService.Type.MATCH_END;
                p.sendMessage(Lang.msg(isEnd ? "timeline.end-moved" : "timeline.moved",
                        "%event%", def != null ? def.name() : id,
                        "%time%", TimelineService.format(newTime)));
            }

            case "delete", "remove" -> {
                if (args.length < 3) { p.sendMessage(Lang.msg("cmd.timeline-usage")); return; }
                String id = resolveEventId(timelines, args[2]);
                if (id == null) {
                    p.sendMessage(Lang.msg("cmd.timeline-unknown-event", "%id%", args[2]));
                    return;
                }
                TimelineService.Definition def = timelines.definition(id);
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
                timelines.resetTimeline(session.getSettings());
                sessions.saveSettings(session);
                p.sendMessage(Lang.msg("timeline.reset"));
            }

            default -> p.sendMessage(Lang.msg("cmd.timeline-usage"));
        }
    }

    /** Case-insensitive event-id lookup, so tab-completed and hand-typed ids both work. */
    private static String resolveEventId(TimelineService timelines, String raw) {
        for (String id : timelines.definitionIds()) {
            if (id.equalsIgnoreCase(raw)) return id;
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
                    live.setSettings(SessionSettings.fromJson(presets.get(name)));
                    sessions.saveSettings(live);
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

    // ── Time parsing ─────────────────────────────────────────────────────────────

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
            return minutes * 60 + seconds;
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
                // Session state is replicated to every server, so the code resolves locally
                // even when the arena lives on another server.
                PrivateSession session = sessions.getByJoinCode(code);
                if (session == null) {
                    p.sendMessage(Lang.msg("join.invalid-code"));
                    return;
                }
                joinSession(p, session);
            });
        });
    }

    private void joinSession(Player p, PrivateSession session) {
        if (session.getJoinPolicy() == JoinPolicy.CODE && !session.isPublic()) {
            p.sendMessage(Lang.msg("join.not-accepting"));
            return;
        }

        // Authorise this player (write-through to the shared DB) then route them to the arena.
        tickets.grant(p.getUniqueId(), session.getSessionId(), session.getArenaName());
        plugin.sendPlayerToArena(p, session.getArenaName());
    }

    /**
     * Returns the private match this player should be controlling: whatever they're physically
     * standing in, if it's a private match, otherwise whichever match they host — so these
     * commands also work when controlling the arena remotely, from elsewhere on the network.
     */
    private PrivateSession requireHostedSession(Player p) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(p);
        PrivateSession session = arena != null ? sessions.getByArena(arena) : null;

        if (session == null) session = sessions.getByOwner(p.getUniqueId());
        if (session == null) {
            p.sendMessage(Lang.msg("host.not-hosting"));
            return null;
        }
        boolean privileged = p.hasPermission(PERM_BYPASS) || p.hasPermission(GuiManager.ADMIN_PERM);
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
                    "goto", "kick", "code", "public", "team",
                    "regen", "heal", "drop", "beds", "clearitems", "skipevent",
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
            case "team" -> {
                if (args.length == 2) return partial(args[1], hostedArenaPlayerNames(sender));
                if (args.length == 3) return partial(args[2], hostedArenaTeamNames(sender));
            }
            case "timeline" -> {
                if (args.length == 2) {
                    return partial(args[1], List.of("list", "move", "set", "delete", "reset"));
                }
                if (args.length == 3 && List.of("move", "set", "delete").contains(
                        args[1].toLowerCase(Locale.ROOT))) {
                    return partial(args[2],
                            List.copyOf(plugin.getTimelineService().definitionIds()));
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
