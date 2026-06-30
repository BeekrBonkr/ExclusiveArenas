package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.remote.RemoteAPI;
import de.marcely.bedwars.api.remote.RemoteArena;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class GuiManager {

    // Inventory titles — used by GuiListener to identify which GUI is open
    public static final String TITLE_MAIN    = ItemUtil.color("&0ExclusiveArenas");
    public static final String TITLE_BUILDER = ItemUtil.color("&0Private Match Builder");
    public static final String TITLE_ARENAS  = ItemUtil.color("&0Select Map");
    public static final String TITLE_LOBBY   = ItemUtil.color("&0Private Match Controls");
    public static final String TITLE_HELP    = ItemUtil.color("&0ExclusiveArenas Help");

    private final ExclusiveArenasPlugin plugin;
    private final DraftService drafts;
    private final PrivateSessionService sessions;

    public GuiManager(ExclusiveArenasPlugin plugin, DraftService drafts, PrivateSessionService sessions) {
        this.plugin = plugin;
        this.drafts = drafts;
        this.sessions = sessions;
    }

    // ── Main menu ──────────────────────────────────────────────────────────────

    public void openMainMenu(Player p) {
        Inventory inv = Bukkit.createInventory(p, 27, TITLE_MAIN);

        inv.setItem(11, ItemUtil.button(Material.COMPASS, "&aCreate Private Arena",
                "&7Set up and start a private match.",
                "&8Click to open the builder."));

        inv.setItem(13, ItemUtil.button(Material.BOOK, "&eHelp",
                "&7Learn how to use ExclusiveArenas.",
                "&8Click to view help."));

        // Stubs for planned future features
        inv.setItem(15, ItemUtil.stub("Tournaments", "Organize bracket-based tournaments."));

        inv.setItem(26, ItemUtil.button(Material.BARRIER, "&cClose"));

        p.openInventory(inv);
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public void openBuilder(Player p) {
        DraftPrivateMatch d = drafts.getOrCreate(p.getUniqueId());

        Inventory inv = Bukkit.createInventory(p, 27, TITLE_BUILDER);

        // Slot 10: Select Map
        String mapLabel = d.getArenaName() == null ? "&7None" : "&a" + d.getArenaName();
        inv.setItem(10, ItemUtil.button(Material.MAP, "&bSelect Map",
                "&7Current: " + mapLabel,
                "&8Click to choose a map."));

        // Slot 12: Join Policy toggle
        boolean isParty = d.getJoinPolicy() == JoinPolicy.PARTY;
        inv.setItem(12, ItemUtil.button(Material.NAME_TAG,
                "&eJoin Policy",
                "&7Mode: " + (isParty ? "&aParty Only" : "&dJoin Code"),
                isParty
                        ? "&7Only your party members can join."
                        : "&7Players join using: &f/ea join <code>",
                "&8Click to switch."));

        // Slot 14: Public/Private toggle (CODE only)
        if (d.getJoinPolicy() == JoinPolicy.CODE) {
            inv.setItem(14, ItemUtil.button(
                    d.isPublic() ? Material.LIME_DYE : Material.RED_DYE,
                    d.isPublic() ? "&aPublic" : "&cPrivate",
                    d.isPublic()
                            ? "&7Anyone with the code can join."
                            : "&7Joining is disabled (arena is locked).",
                    "&8Click to toggle."));
        }

        // Slot 16: Create & Join
        boolean ready = d.isReadyToCreate();
        inv.setItem(16, ItemUtil.button(
                ready ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                ready ? "&aCreate & Join" : "&cCreate & Join",
                ready
                        ? "&7Create your private match on &f" + d.getArenaName() + "&7 and"
                        : "&7Select a map first.",
                ready ? "&7teleport straight in." : null,
                "&8Click to create."));

        // Slot 22: Back to main menu
        inv.setItem(22, ItemUtil.button(Material.ARROW, "&7Back"));

        inv.setItem(26, ItemUtil.button(Material.BARRIER, "&cClose"));

        p.openInventory(inv);
    }

    // ── Arena selector ────────────────────────────────────────────────────────

    public void openArenaSelect(Player p, int page) {
        List<ArenaEntry> entries = collectArenas();

        int perPage = 45;
        int pages = Math.max(1, (int) Math.ceil(entries.size() / (double) perPage));
        int pg = Math.max(0, Math.min(page, pages - 1));

        Inventory inv = Bukkit.createInventory(p, 54,
                TITLE_ARENAS + ItemUtil.color(" &8(" + (pg + 1) + "/" + pages + ")"));

        int start = pg * perPage;
        int end = Math.min(entries.size(), start + perPage);

        for (int i = start; i < end; i++) {
            ArenaEntry entry = entries.get(i);
            boolean reserved = sessions.isArenaReserved(entry.name());
            Material mat = reserved ? Material.GRAY_CONCRETE : Material.GRASS_BLOCK;
            String label = (reserved ? "&7" : "&a") + entry.name();
            String sub = entry.remote() ? "&8[Remote]" : "&8[Local]";
            String hint = reserved ? "&cAlready reserved." : "&7Click to select.";
            inv.setItem(i - start, ItemUtil.button(mat, label, sub, hint));
        }

        inv.setItem(45, ItemUtil.button(Material.ARROW, "&ePrevious Page"));
        inv.setItem(49, ItemUtil.button(Material.ARROW, "&7Back"));
        inv.setItem(53, ItemUtil.button(Material.ARROW, "&eNext Page"));

        p.openInventory(inv);
    }

    private List<ArenaEntry> collectArenas() {
        List<ArenaEntry> list = new ArrayList<>();

        // Remote API covers local + network arenas when ProxySync is active
        try {
            RemoteAPI remote = RemoteAPI.get();
            if (remote != null && remote.isAPIActive()) {
                for (RemoteArena ra : remote.getArenas()) {
                    if (ra != null && ra.exists()) {
                        list.add(new ArenaEntry(ra.getName(), !ra.isLocal()));
                    }
                }
                list.sort(Comparator.comparing(ArenaEntry::name, String.CASE_INSENSITIVE_ORDER));
                return list;
            }
        } catch (Throwable ignored) {
            // RemoteAPI not available; fall through to local
        }

        // Fallback: local arenas only
        for (Arena a : BedwarsAPI.getGameAPI().getArenas()) {
            if (a != null && a.exists()) list.add(new ArenaEntry(a.getName(), false));
        }
        list.sort(Comparator.comparing(ArenaEntry::name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    // ── Lobby controls ────────────────────────────────────────────────────────

    public void openLobbyControls(Player p, PrivateSession session, Arena arena) {
        Inventory inv = Bukkit.createInventory(p, 27, TITLE_LOBBY);

        boolean host = session != null && p.getUniqueId().equals(session.getOwner());
        String hostNote = host ? "&8Click to use." : "&cHost only.";

        // Slot 10: Join info
        String codeInfo = session.getJoinPolicy() == JoinPolicy.CODE
                ? "&7Code: &f" + session.getJoinCode()
                : "&7Mode: &aParty Only";
        inv.setItem(10, ItemUtil.button(Material.PAPER, "&bJoin Info",
                codeInfo,
                session.getJoinPolicy() == JoinPolicy.CODE
                        ? "&7Share: &f/ea join " + session.getJoinCode()
                        : "&7Party members may join freely."));

        // Slot 12: Start countdown
        boolean started = session.isCountdownStarted();
        inv.setItem(12, ItemUtil.button(
                started ? Material.CLOCK : Material.LIME_CONCRETE,
                started ? "&aCountdown Running" : "&aStart Match",
                started ? "&7Countdown is running." : "&7Start the lobby countdown.",
                hostNote));

        // Slot 14: Public/Private toggle (CODE only)
        if (session.getJoinPolicy() == JoinPolicy.CODE) {
            inv.setItem(14, ItemUtil.button(
                    session.isPublic() ? Material.LIME_DYE : Material.RED_DYE,
                    session.isPublic() ? "&aPublic" : "&cPrivate",
                    session.isPublic()
                            ? "&7Anyone with the code can join."
                            : "&7Joining is disabled (arena is locked).",
                    hostNote));
        }

        // Slot 16: Regen code / Summon party
        if (session.getJoinPolicy() == JoinPolicy.CODE) {
            inv.setItem(16, ItemUtil.button(Material.TRIPWIRE_HOOK, "&eRegenerate Code",
                    "&7Invalidate the old code and generate a new one.",
                    hostNote));
        } else {
            inv.setItem(16, ItemUtil.button(Material.ENDER_PEARL, "&eSummon Party",
                    "&7Force your party members to join this lobby.",
                    hostNote));
        }

        inv.setItem(26, ItemUtil.button(Material.BARRIER, "&cClose"));

        p.openInventory(inv);
    }

    // ── Help ──────────────────────────────────────────────────────────────────

    public void openHelp(Player p) {
        Inventory inv = Bukkit.createInventory(p, 27, TITLE_HELP);

        inv.setItem(10, ItemUtil.button(Material.COMPASS, "&b/ea &7or &b/ea menu",
                "&7Opens the main ExclusiveArenas menu."));

        inv.setItem(11, ItemUtil.button(Material.MAP, "&b/ea arena",
                "&7Opens the private match builder directly."));

        inv.setItem(12, ItemUtil.button(Material.NAME_TAG, "&b/ea join &f<code>",
                "&7Joins a private arena using a join code."));

        inv.setItem(13, ItemUtil.button(Material.LIME_CONCRETE, "&b/ea start",
                "&7Starts the lobby countdown (host only)."));

        inv.setItem(14, ItemUtil.button(Material.RED_CONCRETE, "&b/ea end",
                "&7Ends the private match and kicks all players (host only)."));

        inv.setItem(15, ItemUtil.button(Material.ENDER_PEARL, "&b/ea summon",
                "&7Summons your party to the arena lobby (Party policy, host only)."));

        inv.setItem(26, ItemUtil.button(Material.ARROW, "&7Back"));

        p.openInventory(inv);
    }

    private record ArenaEntry(String name, boolean remote) {}
}
