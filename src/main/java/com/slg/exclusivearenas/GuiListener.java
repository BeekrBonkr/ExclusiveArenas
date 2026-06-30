package com.slg.exclusivearenas;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

public final class GuiListener implements Listener {

    private final ExclusiveArenasPlugin plugin;
    private final DraftService drafts;
    private final PrivateSessionService sessions;
    private final GuiManager gui;

    public GuiListener(ExclusiveArenasPlugin plugin,
                       DraftService drafts,
                       PrivateSessionService sessions,
                       GuiManager gui) {
        this.plugin = plugin;
        this.drafts = drafts;
        this.sessions = sessions;
        this.gui = gui;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        String title = e.getView().getTitle();
        if (title == null) return;

        boolean ours = title.startsWith(GuiManager.TITLE_MAIN)
                || title.startsWith(GuiManager.TITLE_BUILDER)
                || title.startsWith(GuiManager.TITLE_ARENAS)
                || title.startsWith(GuiManager.TITLE_LOBBY)
                || title.startsWith(GuiManager.TITLE_HELP);
        if (!ours) return;

        e.setCancelled(true);

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        if (title.startsWith(GuiManager.TITLE_MAIN))    { handleMainMenu(p, e.getSlot()); return; }
        if (title.startsWith(GuiManager.TITLE_BUILDER)) { handleBuilder(p, e.getSlot()); return; }
        if (title.startsWith(GuiManager.TITLE_ARENAS))  { handleArenaSelect(p, title, e.getSlot(), clicked); return; }
        if (title.startsWith(GuiManager.TITLE_LOBBY))   { handleLobby(p, e.getSlot()); return; }
        if (title.startsWith(GuiManager.TITLE_HELP))    { handleHelp(p, e.getSlot()); }
    }

    // ── Main menu ──────────────────────────────────────────────────────────────

    private void handleMainMenu(Player p, int slot) {
        switch (slot) {
            case 11 -> gui.openBuilder(p);
            case 13 -> gui.openHelp(p);
            case 26 -> p.closeInventory();
        }
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    private void handleBuilder(Player p, int slot) {
        DraftPrivateMatch d = drafts.getOrCreate(p.getUniqueId());

        switch (slot) {
            case 10 -> gui.openArenaSelect(p, 0);

            case 12 -> {
                // Toggle policy
                if (d.getJoinPolicy() == JoinPolicy.PARTY) {
                    d.setJoinPolicy(JoinPolicy.CODE);
                    if (d.getArenaName() != null && (d.getJoinCode() == null || d.getJoinCode().isBlank())) {
                        d.setJoinCode(sessions.generateCodeForArena(d.getArenaName()));
                    }
                } else {
                    d.setJoinPolicy(JoinPolicy.PARTY);
                }
                gui.openBuilder(p);
            }

            case 14 -> {
                // Toggle public/private (CODE only)
                if (d.getJoinPolicy() == JoinPolicy.CODE) {
                    d.setPublic(!d.isPublic());
                    gui.openBuilder(p);
                }
            }

            case 16 -> {
                // Create the private match and send the host straight in.
                p.closeInventory();
                plugin.createAndJoin(p, d);
            }

            case 22 -> gui.openMainMenu(p);
            case 26 -> p.closeInventory();
        }
    }

    // ── Arena selector ─────────────────────────────────────────────────────────

    private void handleArenaSelect(Player p, String title, int slot, ItemStack clicked) {
        if (slot == 49) { gui.openBuilder(p); return; } // Back

        int page = parsePageFromTitle(title);
        if (slot == 45) { gui.openArenaSelect(p, Math.max(0, page - 1)); return; }
        if (slot == 53) { gui.openArenaSelect(p, page + 1); return; }
        if (slot < 0 || slot > 44) return;

        String name = clicked.getItemMeta() != null
                ? ChatColor.stripColor(clicked.getItemMeta().getDisplayName()) : null;
        if (name == null || name.isBlank()) return;

        if (sessions.isArenaReserved(name)) {
            p.sendMessage(ItemUtil.color("&cThat arena is already reserved."));
            return;
        }

        DraftPrivateMatch d = drafts.getOrCreate(p.getUniqueId());
        d.setArenaName(name);
        if (d.getJoinPolicy() == JoinPolicy.CODE
                && (d.getJoinCode() == null || !d.getJoinCode().startsWith(name + "::"))) {
            d.setJoinCode(sessions.generateCodeForArena(name));
        }

        gui.openBuilder(p);
    }

    // ── Lobby controls ─────────────────────────────────────────────────────────

    private void handleLobby(Player p, int slot) {
        if (slot == 26) { p.closeInventory(); return; }

        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(p);
        if (arena == null) {
            p.sendMessage(ItemUtil.color("&cYou're not in a BedWars arena."));
            p.closeInventory();
            return;
        }

        PrivateSession session = sessions.getByArena(arena);
        if (session == null) {
            p.sendMessage(ItemUtil.color("&cThis arena is not a private match."));
            p.closeInventory();
            return;
        }

        boolean host = p.getUniqueId().equals(session.getOwner());
        if (!host && !p.hasPermission("exclusivearenas.bypass")) {
            p.sendMessage(ItemUtil.color("&cOnly the host can do that."));
            return;
        }

        switch (slot) {
            case 12 -> {
                plugin.startLobbyCountdown(arena, session);
                gui.openLobbyControls(p, session, arena);
            }
            case 14 -> {
                // Toggle public/private (CODE only)
                if (session.getJoinPolicy() == JoinPolicy.CODE) {
                    session.setPublic(!session.isPublic());
                    plugin.getNetworkBus().broadcastUpdate(session);
                    p.sendMessage(ItemUtil.color(session.isPublic()
                            ? "&aArena set to &fPublic&a. Anyone with the code may join."
                            : "&cArena set to &fPrivate&c. Joining is now disabled."));
                    gui.openLobbyControls(p, session, arena);
                }
            }
            case 16 -> {
                if (session.getJoinPolicy() == JoinPolicy.CODE) {
                    plugin.regenerateJoinCode(session);
                    gui.openLobbyControls(p, session, arena);
                } else {
                    plugin.summonPartyToArena(p, arena, session);
                }
            }
        }
    }

    // ── Help ──────────────────────────────────────────────────────────────────

    private void handleHelp(Player p, int slot) {
        if (slot == 26) gui.openMainMenu(p); // Back
        // All other slots are informational buttons with no action
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int parsePageFromTitle(String title) {
        try {
            int open  = title.lastIndexOf('(');
            int slash = title.lastIndexOf('/');
            int close = title.lastIndexOf(')');
            if (open < 0 || slash < 0 || close < 0) return 0;
            return Math.max(0, Integer.parseInt(
                    ChatColor.stripColor(title.substring(open + 1, slash)).trim()) - 1);
        } catch (Exception ignored) {
            return 0;
        }
    }
}
