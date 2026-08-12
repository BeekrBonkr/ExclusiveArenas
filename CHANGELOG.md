# Changelog

All notable changes to ExclusiveArenas are documented here.

## [Unreleased]

### Added
- **Context-sensitive main-menu button** — the `/ea` menu's first slot now adapts: **Create New
  Arena** while you host nothing, **Match Controls** (straight into your match) while you host
  your single allowed match, and the full **Arena Management** list only when your limit allows
  several matches. All three are separately configurable in `guis.yml` (`main.buttons.create-arena`
  / `match-controls` / `arena-management`) and should share a slot.
- **Party lifecycle handling** — a Party-gated match whose host leaves (or loses leadership of)
  their party now converts cleanly to a Join-Code gate: fresh code sent to the host, in-arena
  broadcast, replicated network-wide. New `private.party_check_seconds` config key controls the
  check cadence; the check never fires while no parties plugin is hooked into MBedwars (an
  absent hook is indistinguishable from "left the party").
- **Party-member hosting block** — a party leader can't create a private match while anyone in
  their party is hosting one of their own; the builder shows a dedicated locked screen
  (`builder.buttons.member-hosting-blocked`) and `/ea create` refuses with the same reason.
- **Multi-host policy rule** — hosting several matches at once (elevated
  `exclusivearenas.limit.<n>`) is only allowed while every one of them is Join-Code gated; a
  Party-gated match is always its host's only match.
- **Remote-availability lore markers** — when a match's arena lives on another server, buttons
  whose action can't run from here say so in their lore instead of failing quietly: strictly
  local actions (Reveal Border, the Force Win team picker, Team Size, Environment) are marked
  outright, and every relayed action is marked whenever no shared database is connected to relay
  through (also applies to Start/Kick All/End Match in Match Controls). Texts configurable via
  `global.remote-unavailable-lore` / `global.relay-unavailable-lore` in `guis.yml`.
- **MBedwars environment detection** — on enable, the plugin logs a one-line summary of the
  detected setup (parties hook, RemoteAPI, MBedwarsTweaks + which timeline backend engaged) and
  adapts to it. With RemoteAPI active but no shared database, remote arenas are now hidden from
  the map selector (and refused by `/ea create`) — a "private" match on a remote arena would
  otherwise be completely ungated on the server actually hosting it.
- **11 new Quick Actions** (Match Controls → Quick Actions): +2:00 / -2:00 Match Timer, Toggle
  PvP, Strip Inventories, Comeback Buff (buffs whichever team has the fewest players), Random
  Scatter, Kick AFK Players, Reset Shop Prices, Give Tracking Compass, Announce Match Stats, and
  Emergency Pause (freezes everyone and holds off the timeline until resumed). All work
  cross-server like the existing quick actions.
- **Match Rules** — a new menu under Arena Modifiers (and the pre-creation builder) with 12
  standing per-match toggles, separate from the one-shot event timeline: Friendly Fire, Fall
  Damage, Explosion Block Damage, Kill Bounty (0/1x/2x/3x), Shop Prices multiplier, Bonus
  Starting Kit, PvP Grace Period, Health Multiplier, World Border Shrink, Bed Respawn Once,
  Spawn Protection, and Kill Goal (an alternate first-to-N-kills win condition). Click a rule to
  cycle its value; Reset All Rules reverts everything to vanilla. New `arena_modifiers` section
  in `config.yml` configures the kill bounty resource/amount, starting kit items, world border
  target/duration, and spawn protection radius.
- **5 new timeline event types** — `heal_all`, `clear_items`, `balance_teams`, `clear_traps`,
  `reset_upgrades` — schedulable like any other timeline event, backed by the same logic as the
  matching Quick Actions.
- **20 new optional timeline catalog entries** (Heal Pulse, Item Cleanup, Team Reshuffle, Trap
  Purge, Upgrade Reset, Trap Chaos, four buff presets, weather/time-of-day presets, four
  resource-burst presets, and a reminder announcement) — all appear directly in the timeline
  editor's "Add Event" list, nothing to configure.
- `/ea timeline custom <type> [value] <time>` now accepts value-less types (`trap_chaos`,
  `fireworks`, `heal_all`, `clear_items`, `balance_teams`, `clear_traps`, `reset_upgrades`)
  without needing a placeholder value argument.

### Fixed
- **Distribute Players actually distributes evenly now** — players who already held a team kept
  their seats during the pass, so their old teams looked full to the capacity check and the
  round-robin could come out lopsided (e.g. 4 players on one team of two redistributing 1/3
  instead of 2/2). Everyone is unassigned first, then dealt out round-robin.
- **Kick All with "keep host" no longer kicks a spectating host** — the spectator sweep used
  `kickAllSpectators`, which swept the host out too whenever they happened to be spectating
  their own match; spectators are now kicked individually with the host spared.
- **Spectator "Rejoin as Player" item is strictly lobby-only** — it previously also showed
  during END_LOBBY/RESETTING (where `/bw join` can't seat anyone), and its visibility now
  matches the requirement that mid-round death spectators never see it.
- AFK-kick movement tracking no longer retains entries for players who logged off.
- Team size scoreboard only updated the *first* time the players-per-team cap was changed in a
  row — subsequent changes updated the arena's actual cap but left the scoreboard stale, because
  the redraw only ever happened as a side effect of unassigning players from their teams (and a
  second change in a row has nothing left to unassign). Now forces a scoreboard refresh on every
  change.
- Arena Modifiers → Environment (time/weather) button was fully wired up in the click handler but
  never actually rendered in the menu — introduced in the same release that added it, invisible
  and unreachable ever since.

### Changed
- Renamed "Arena Settings" to "Arena Modifiers" throughout every menu, message, and doc.
