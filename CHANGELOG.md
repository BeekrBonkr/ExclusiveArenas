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
- **Lock Teams** (Match Controls → Manage Teams) — a host can freeze team selection for the
  lobby: players can no longer open the team menu or switch teams themselves, while the host
  keeps moving anyone anywhere from Manage Teams. Everyone in the arena is told when the lock is
  toggled, anyone joining a locked lobby is told on arrival, and a player who tries to switch is
  told why they can't. The flag rides on the match's replicated settings, so the server that
  actually hosts the arena enforces it no matter where it was toggled from. New `guis.yml`
  buttons `team-select.buttons.lock-teams` / `unlock-teams` (two looks of one toggle — keep them
  on the same slot) and a `teams.locked-*` block in `lang.yml`.
- **Team Size in Manage Teams** — the same ±1 editor the Arena Modifiers hub offers, reachable
  from the menu where a wrong per-team cap is actually noticed (`team-select.buttons.team-size`).
  Its Back button returns to Manage Teams rather than the hub the host never passed through.
- **Build-your-own timeline events, in the GUI** — Add Event → *Build Your Own Event* opens a
  three-step wizard (what it does → what it applies to → when it fires) covering every
  host-authorable event type: resource bursts (any of the server's drop types), buffs for
  everyone (14 effects × strength × duration), trap chaos, weather and time-of-day changes,
  announcements typed into an anvil, fireworks, heal pulses, item cleanup, team reshuffles, trap
  purges and upgrade resets. `/ea timeline custom` still works and is unchanged.
- **Revamped Event Timeline editor** — a paged event strip (the schedule can now outgrow one
  screen), a live schedule summary (event count, match length, custom events used, whether the
  timings are still the server defaults), a detail card for the selected event (type, value,
  effect, exact time), plus three new operations alongside the existing move/delete: **Duplicate
  Event** (schedule the same thing again later), **Change What It Does** (re-run the wizard's
  value step on an event you built), and **Clear All Events** (start from an empty schedule;
  Match End always stays). Catalog events can now be added at a chosen time with a shift-click
  instead of only at their configured default.
- **Preview a saved configuration before applying it** — right-click any entry in Saved
  Configurations for a read-only breakdown: every scheduled event with its time, the disabled and
  repriced shop items by name, team size, changed match rules, environment, and a *What would
  change* card comparing it with the match's current setup. Apply and Delete sit on the same
  screen (`preset-preview` in `guis.yml`).
- **Live "what's changed" lines in Arena Modifiers** — every editor's button in the hub (and the
  pre-creation builder's) now carries a one-line summary of what this match has actually changed:
  the timeline's event count and match length, how many shop items are disabled/repriced, the
  per-team cap against the arena's own, which match rules are off default, and the current
  time/weather. Configurable via the new `%summary%` placeholder in each button's lore.

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
- **Match Rules was unreachable from either Arena Modifiers hub** — same class of bug: the button
  was defined in `guis.yml` and fully handled on click, but neither the live hub nor the builder's
  ever rendered it, so the whole rules editor could only be reached by accident. Both hubs now
  place it.
- Manage Teams and its player picker only resolved the session before acting, skipping the
  host/admin check every other management menu applies — an already-open menu could therefore
  still move players after its viewer stopped being entitled to. Both now go through the same
  guard.
- Applying a saved configuration no longer silently changes whether teams are locked: the lock is
  live lobby state, not part of the setup a preset describes, so it is carried across untouched.

### Changed
- Renamed "Arena Settings" to "Arena Modifiers" throughout every menu, message, and doc.
- `guis.yml` is now version 5 and `lang.yml` version 4 — both upgrade in place, keeping your
  edits. `timeline-add.buttons.custom-info` (the "run this command instead" note) is removed by
  the upgrade; the wizard button that replaces it lands at the same slot.
