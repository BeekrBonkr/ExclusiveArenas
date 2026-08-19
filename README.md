# ExclusiveArenas

An MBedwars add-on that lets players host **private, gated BedWars matches** — restricted to
party members or to players holding a join code — with a full in-game GUI for managing them:
a fully customizable event timeline (add events from a catalog, or author your own — weather
changes, timed buffs, trap chaos, scripted announcements, and more), per-match shop overrides,
flexible team size and environment (time/weather) controls, saved configurations, over a dozen
one-click quick actions, optional cross-server control with automatic crash cleanup, continuous
arena health monitoring, and a handful of extra lobby hotbar items.

---

## Requirements

| Dependency | Notes |
|---|---|
| Paper 1.21.x | Earlier versions may work but are untested |
| MBedwars | Must be installed and configured; ExclusiveArenas registers as a real MBedwars add-on |
| MBedwars Tweaks (optional) | If installed, the event timeline edits Tweaks' own gen-tier schedule directly so the scoreboard's next-event timer stays accurate — see [Event Timeline](#event-timeline) |
| Party plugin (optional) | Any plugin with an MBedwars `PartiesHook` implementation — required for Party-policy matches |
| MySQL / MariaDB (optional) | Only needed for multi-server networks — see [Network Setup](#network-multi-server-setup) |

---

## Installation

1. Drop `ExclusiveArenas-x.y.z.jar` into `plugins/MBedwars/add-ons/`.
2. Start (or `/bw reload`) the server.
3. Configure `plugins/MBedwars/add-ons/ExclusiveArenas/config.yml` as needed, then `/ea reload`
   (admin) to apply changes without a restart.

The config is self-healing: any key missing from your copy (e.g. after an update) is restored
with its default value automatically on startup, and every key is commented inline.

---

## Permissions

| Permission | Default | Description |
|---|---|---|
| `exclusivearenas.command` | `true` | Use `/ea` commands |
| `exclusivearenas.bypass` | `op` | Join any private arena regardless of policy; skip auto-summon's party enforcement |
| `exclusivearenas.admin` | `op` | Admin Panel access, control any match, `/ea reload` |
| `exclusivearenas.limit.<n>` | — | Host up to `<n>` simultaneous private matches (e.g. `exclusivearenas.limit.5`). The highest granted number wins; falls back to `private.default_arena_limit` (default `1`) if none is granted |
| `exclusivearenas.limit.unlimited` | `false` | No cap on simultaneous hosted matches |

---

## Commands

| Command | Description |
|---|---|
| `/ea` or `/ea menu` | Open the main menu |
| `/ea arena` (or `create`, `builder`) | Jump straight to the match builder |
| `/ea arena <map> [nojoin]` | Skip the builder and create + join a match on `<map>` immediately; `nojoin` creates without joining |
| `/ea list` (or `arenas`) | Open Arena Management — the matches you host |
| `/ea help` | In-game help panel |
| `/ea join [code]` | Join a private match by its join code — or with no code at all, or any code, if your party's leader hosts an active match (that always takes priority) |
| `/ea lobby` (or `controls`) | Open Match Controls for the match you're standing in |
| `/ea start` | Start the match right now (host only) — also works when controlling remotely |
| `/ea end` | End the match (host only) — also works when controlling remotely |
| `/ea summon` | Summon your whole party into the match (Party policy, host only) |
| `/ea goto` | Go to your arena (grants yourself a join ticket first) |
| `/ea kick [keephost]` | Kick every player and spectator; `keephost` leaves you in the match |
| `/ea code` | Regenerate the join code (Join Code policy) |
| `/ea public [on\|off]` | Open or lock joining by code (Join Code policy) |
| `/ea team <player> <team>` | Move a lobby player onto a team (arena's server only) |
| `/ea teamsize <amount\|reset>` | Change how many players fit on each team this match |
| `/ea weather <clear\|rain\|off>` | Set the arena's weather |
| `/ea time <noon\|sunset\|night\|off>` | Set the arena's time of day |
| `/ea regen` | Regenerate the map, keeping everyone in the match on their teams |
| `/ea heal` | Heal every player in the match |
| `/ea drop` | Make every generator drop immediately |
| `/ea beds` | Sudden death — destroy every remaining bed |
| `/ea clearitems` | Clear all dropped items off the ground |
| `/ea skipevent` | Fire the next timeline event right now |
| `/ea balance` | Re-shuffle everyone evenly across the enabled teams |
| `/ea trigtrap` | Force-trigger a random team's queued trap |
| `/ea cleartraps` | Clear every team's queued traps |
| `/ea resetupgrades` | Reset every team's generator/shop upgrades |
| `/ea freeze` | Toggle locking everyone in the arena in place |
| `/ea rejoinall` | Rejoin any disconnected players who are back online |
| `/ea forcewin <team>` | Instantly end the match, awarding `<team>` the win |
| `/ea swapteams <team-a> <team-b>` | Swap two teams' entire rosters |
| `/ea buff <speed\|jump\|regen\|strength\|potion_type> [amplifier] [seconds]` | Grant everyone in the arena a timed potion effect |
| `/ea border` | Show yourself the arena's match-area border |
| `/ea timeline list\|add\|custom\|move\|set\|delete\|reset` | Edit this match's event timeline — see [Event Timeline](#event-timeline) |
| `/ea shop list\|disable\|enable\|price\|resetprice\|reset` | Customize this match's shop, e.g. `/ea shop disable blocks-wool`, `/ea shop price blocks-wool 8 iron` |
| `/ea preset list\|save\|apply\|delete` | Saved arena configurations, e.g. `/ea preset save sweats`, `/ea preset apply sweats` |
| `/ea admin` | Admin Panel — every active match on the network (`exclusivearenas.admin`) |
| `/ea reload` | Reload config, reconnect the database, resync sessions (`exclusivearenas.admin`) |

Every host command (`start`, `end`, `summon`, `kick`, the quick actions, the editors, …)
works even when you aren't physically standing in your arena — they fall
back to whichever match you host (or, in a database setup, relay the request to whichever
server actually hosts the arena; see [Remote Control](#remote-control)).

---

## Hosting a Private Match

1. Run `/ea`. The main menu's first button is context-sensitive: while you host nothing it's
   **Create New Arena** (straight into the builder); while you host your single allowed match
   it's **Match Controls** (straight into managing it); and only when your limit allows several
   matches does it read **Arena Management**, opening the full list. `/ea arena` always jumps
   straight to the builder.
2. **Join Policy is automatic, not a choice** — it's set from your current party leadership the
   moment you open the builder:
   - Leading a party → **Party Only**: only your party members may join.
   - Not in a party at all → **Join Code**: players join with `/ea join <code>`.
   - In a party but *not* its leader → the whole builder is locked; you're told to leave the
     party instead (party members should join their leader's match — see `/ea join` below —
     not create a competing one of their own).
   - Leading a party in which *someone else* already hosts a private match → also locked,
     until those members' matches end (or they leave the party).

   A Party-gated match doesn't outlive its party, either: if the host leaves the party (or
   stops being its leader), the match automatically converts to a **Join Code** gate — the
   host is sent the fresh code, and the arena is told the gate changed
   (`private.party_check_seconds` controls how quickly this is noticed).

   Hosting **several matches at once** (an elevated `exclusivearenas.limit.<n>`) is allowed
   only while every one of them is Join-Code gated — a Party-gated match is always its host's
   only match.
3. Click **Select Map** and choose an arena from the list. Each entry shows the arena's own
   MBedwars icon, and can be filtered by **team count** and **players per team** — the filter
   buttons cycle through values discovered from your actual arena roster, not a fixed list.
   The moment you select one it's soft-reserved for you — no one else can pick the same arena
   in their own builder until you create your match, back out, or leave the menu (this lock
   self-expires after 10 minutes in case you just wander off).
4. (Optional) Click **Arena Modifiers** to configure [Event Timeline](#event-timeline),
   [Shop Items](#shop-overrides), and [Team Size](#team-size) *before* the match is created —
   the exact same editors as Match Controls, just working on your draft. Whatever you set here
   is applied the moment the arena is actually created.
5. Click **Create & Join** to create the match and be sent straight in, locally or across
   servers — or **shift-click** to create it without joining (grants you a ticket, so **Go to
   Arena** in Match Controls still works whenever you're ready). Use **Summon Party** in Match
   Controls (or the [party-summon lobby item](#custom-lobby-hotbar-items)) afterwards to pull
   your party in — there's no automatic continuous sync; that machinery exists internally
   (`private.auto_summon_enabled`) but is currently retired from every menu pending a future pass.

You also can't open the builder at all while already inside one of your own private matches —
leave it first.

### Match Controls

Open via the arena's entry in **Arena Management**, `/ea lobby`, or the host-only
[lobby hotbar item](#custom-lobby-hotbar-items):

- **Start Match** — begins the round immediately. There's no pre-game timer to wait out; MBedwars'
  own automatic lobby countdown is suppressed entirely, so the match only ever starts when you
  press this. Requires at least one other player in the arena.
- **Join Policy** — a live indicator of the match's current policy (still switchable here, on
  an already-created match, unlike the automatic builder above).
- **Public / Locked** (Code policy) or **Summon Party** (Party policy).
- **Regenerate Code** (Code policy) — invalidates the old code and issues a new one.
- **Manage Teams** — move players between teams, lock team selection, and change the per-team
  size while in the lobby (see below). Requires being on the arena's own server (MBedwars doesn't
  expose per-player team data remotely).
- **Go to Arena** — teleport/connect to the match yourself.
- **Kick All Players** — clears the arena; shift-click keeps you (the host) in it.
- **Arena Modifiers** — opens [Event Timeline](#event-timeline), [Shop Items](#shop-overrides),
  [Team Size](#team-size), [Match Rules](#match-rules), [Environment](#environment-time--weather),
  and [Saved Configurations](#saved-configurations-presets) for this match. Each button's lore
  carries a live one-line summary of what this match has actually changed there, so you can see
  the whole setup at a glance without opening every editor. Event Timeline/Shop
  Items/Team Size/Match Rules are editable any time the match hasn't started yet (Event
  Timeline/Shop Items again once it has, taking effect from the next round; Match Rules changes
  only take effect from the next round too); Environment is editable any time, match running or
  not.
- **Quick Actions** — one-click shortcuts, recreated through the stable MBedwars API so they
  can't silently break with an update. Most work on a *live* (running) match; team/trap/upgrade
  actions work any time:
  - **Regenerate Map** — rebuilds the arena to a pristine state without anyone leaving. Everyone
    is switched to a spectator (teams snapshotted first) so the round ends and MBedwars
    regenerates the map on its own; the moment the arena is back in its lobby, everyone is
    re-added on their original team, and the round restarts automatically if
    `quick_actions.restart_after_regen` is enabled (default on).
  - **Heal Everyone** — full health, hunger, and extinguishes fire for every player.
  - **Trigger All Generators** — every resource generator drops immediately.
  - **Sudden Death** — destroys every remaining bed.
  - **Clear Ground Items** — removes every dropped item in the arena (lag cleanup).
  - **Skip to Next Event** — fires the next timeline event immediately instead of waiting for it.
  - **Force Win** — pick a team from a sub-menu to instantly end the match, awarding them the win.
  - **Swap Teams** — exchange two teams' rosters. Two teams is one click too many for a button —
    use `/ea swapteams <team-a> <team-b>`; the menu item just points you at the command.
  - **Balance Teams** — re-shuffles everyone currently in the arena evenly across the enabled
    teams.
  - **Trigger Random Trap** — force-triggers a random team's queued trap, if any team has one
    queued — a chaos/wildcard button.
  - **Clear Trap Queues** — clears every team's queued (not yet triggered) traps.
  - **Reset Team Upgrades** — resets every team's generator/shop upgrades to nothing purchased —
    handy for a practice or showcase match.
  - **Buff Everyone** — pick a short-list effect (Speed II, Jump Boost II, Regeneration II,
    Strength II, 60 seconds each) to grant everyone in the arena; `/ea buff` also accepts any
    Bukkit potion type with a custom amplifier/duration.
  - **Freeze / Unfreeze All** — locks everyone in the arena in place (looking around still
    works) — a "hold on a second" button for streamers or screenshots. Toggles back on a second
    click.
  - **Force-Rejoin Disconnected** — sweeps players who disconnected mid-match and are back
    online, and rejoins them to the arena.
  - **Reveal Arena Border** — shows *you only* the arena's match-area border — a debug/QoL aid
    with zero gameplay effect. Only works while you're on the arena's own server.
  - **+2:00 / -2:00 Match Timer** — nudges the match-end countdown, live (never below 0:30).
  - **Toggle PvP** — blocks or restores ALL player damage in the arena, friend or foe.
  - **Strip Inventories** — clears everyone's inventory and armor — a hard reset, not a heal.
  - **Comeback Buff** — grants Strength II + Resistance II (60s) to whichever team currently has
    the fewest players.
  - **Random Scatter** — teleports everyone to a random X/Z within the arena's region corners —
    chaos mode.
  - **Kick AFK Players** — removes anyone who hasn't actually moved in
    `quick_actions.afk_kick_threshold_seconds` (default 120s); a player never seen moving is
    never kicked.
  - **Reset Shop Prices** — clears every price/disable override you've made for this match's shop.
  - **Give Tracking Compass** — hands everyone a compass pointed at their nearest enemy, a
    one-time snapshot (not a live tracker).
  - **Announce Match Stats** — broadcasts each enabled team's alive/eliminated status and total
    kills.
  - **Emergency Pause** — freezes everyone AND holds off every pending timeline event until
    resumed (pending events are pushed back by however long the pause lasted, so nothing fires
    all at once on resume). MBedwars' own match-end clock keeps counting during a pause — there's
    no public API to actually stop it.
- **End Match** — closes the session and kicks everyone, players and spectators alike.

#### Match Rules

A grid of standing per-match toggles, separate from the one-shot [Event Timeline](#event-timeline)
— click a rule to cycle its value, **Reset All Rules** to put everything back to vanilla. Changes
apply from the next round start:

- **Friendly Fire** — lets teammates damage each other.
- **Fall Damage** — off disables fall damage entirely.
- **Explosion Block Damage** — off keeps explosions from breaking blocks (entity damage is
  unaffected).
- **Kill Bounty** (0/1x/2x/3x) — resources handed to a killer on top of the normal drop
  (`arena_modifiers.kill_bounty` in config.yml sets the resource and 1x amount).
- **Shop Prices** (Normal/Half Price/+50%/Double) — scales every shop price for the match.
- **Bonus Starting Kit** — extra items everyone spawns with (`arena_modifiers.starting_kit.items`
  in config.yml).
- **PvP Grace Period** (Off/15s/30s/60s) — no PvP damage for this long after the match starts.
- **Health Multiplier** (Normal/1.5x/2x/0.5x) — scales everyone's max health.
- **World Border Shrink** — shrinks the arena's playable area over time
  (`arena_modifiers.world_border` in config.yml sets the target size and duration). Assumes one
  game world per arena, MBedwars' default — enabling it while multiple arenas share one world
  would shrink the border for all of them.
- **Bed Respawn Once** — each team's bed survives its first hit, then breaks normally on the next.
- **Spawn Protection** (Off/5s/10s/20s) — blocks PvP damage near a player's own team spawn for
  this long right after they respawn (`arena_modifiers.spawn_protection.radius` sets how close
  counts as "near").
- **Kill Goal** (Off/10/20/30) — first team to reach this many total kills wins instantly, instead
  of the normal last-team-standing condition.

#### Manage Teams

Click a team to see its roster and open the player picker: every player currently in the arena
(not already on that team) shows as a head. A plain click moves that one player and returns you
to team selection; shift-click (or a plain click while others are already picked) stages
multiple players — shown with an enchant glint — for a batch move via the **Move N Selected
Player(s)** button. Clicking an already-staged head un-stages it. Trying to select more than
the team has room for flips the menu's title into a warning until you free up a slot.

**Distribute Players** (bottom of the team list) shuffles and re-splits *everyone* currently in
the lobby evenly across the enabled teams in one click, respecting each team's capacity —
handy for quickly randomizing teams instead of moving people one at a time.

**Lock Teams** freezes team selection for everyone but you: the team menu simply refuses to open
for them, and a player moved onto another team by any other route is put straight back. You keep
moving anyone anywhere from this menu — Distribute Players, the player picker and a team-size
change all still work normally, and admins (`exclusivearenas.admin` / `.bypass`) are exempt.
Everyone in the arena is told when you lock or unlock, anyone joining a locked lobby is told on
arrival, and a player who tries to switch is told why they can't. Click the button again to
unlock. The lock only applies while the arena is in its lobby (there is nothing to switch once
the round is running) and travels with the match's settings, so it holds on the arena's own
server even when you toggled it from a hub.

**Team Size** (bottom-left) is the same editor as [Team Size](#team-size) under Arena Modifiers,
put where a wrong per-team cap is usually noticed. Back returns you to Manage Teams.

### Event Timeline

Each event fires this many minutes into the match, left to right; click one to select it, then
use the buttons below to nudge it ±1 minute / ±5 seconds, or delete it (Match End can be moved
but never deleted — shortening it proportionally rescales every other event to still fit inside
the shorter match). **Match End**'s lore shows the *total* match time, not a delta.

The editor also gives you:

- a **schedule summary** card — how many events, how long the match runs, how many of your 15
  custom-event slots are used, and whether the timings are still the server's defaults;
- a **detail card** for the selected event — its type, what it applies to, its effect and its
  exact time;
- **Duplicate Event** — schedules the same thing a second time just after the original (works on
  anything you could have built yourself; a generator upgrade or Match End can't be copied);
- **Change What It Does** — re-runs the value step of the wizard on an event you built, so an
  announcement's text or a buff's effect can be changed without deleting and re-adding it;
- **Clear All Events** — empties the schedule to start from scratch (Match End always stays),
  next to **Reset to Defaults** which does the opposite and restores the server's own schedule;
- **paging** — a schedule longer than one screen pages with the arrows at the bottom (selecting
  an event always jumps to the page it's on).

- **Without MBedwarsTweaks**: events come from `timeline.events` in `config.yml` — spawner
  speed-ups (tied to a resource type and a drop-duration multiplier), a bed-destruction event,
  and exactly one `match_end` event. An internal engine runs the schedule at match start.
- **With MBedwarsTweaks installed**: the editor's defaults are read straight from Tweaks' own
  gen-tier chain instead, and a host's custom timings are applied by rewriting Tweaks'
  scheduling live — so Tweaks still runs the actual schedule (and its scoreboard placeholders
  stay accurate) while showing the host's edits. Tweaks' Sudden Death tier spawns dragons that
  threaten players but never register a bed as destroyed with MBedwars; if a host's customized
  timeline could let Sudden Death fire without a preceding real bed-break, ExclusiveArenas forces
  one itself at the moment Sudden Death actually triggers. New event types below that have no
  Tweaks gen-tier equivalent (weather, buffs, announcements, …) are simply skipped on this
  backend rather than breaking the schedule — use `timeline.backend: internal` if you want them
  to actually fire alongside Tweaks-driven matches.
- **Match length is capped** at `timeline.max_match_time` (default `60:00`/1 hour) — however
  Match End is edited (the GUI, `/ea timeline set`, or `/ea timeline move`), it can never be
  pushed past this.
- Also editable via `/ea timeline list|add|custom|move|set|delete|reset` (see
  [Commands](#commands)).

#### Adding events

**Add Event** (top-right of the editor) lists every catalog event not currently on this match's
timeline — either previously deleted, or one an admin defined with `default: false` in
`config.yml` so it's an optional extra rather than part of every match's starting schedule.
Click one to add it at its configured default time, or shift-click to pick the time first.

For something the catalog doesn't have at all, **Build Your Own Event** (bottom of Add Event)
walks you through it in three steps:

1. **What it does** — pick the event type (see the table below).
2. **What it applies to** — pick the resource, the effect, the weather or the time of day. Buff
   events also get strength (I–III) and duration (15s/30s/60s/120s) dials on the next screen;
   an announcement's message is typed into an anvil instead.
3. **When it fires** — nudge the time ±1 minute / ±5 seconds against the match's length, then
   confirm. The new event lands on the timeline already selected, ready to fine-tune.

The same events can still be created from the command line, which is quicker once you know the
syntax:

```
/ea timeline custom <type> <value> <time>
```

| Type | Value | Effect |
|---|---|---|
| `resource_burst` | a drop type id (e.g. `diamond`) | One-time bonus drop from every generator of that type, right now |
| `team_buff` | `POTION_TYPE:amplifier:seconds` (e.g. `SPEED:1:30`) | Grants everyone in the arena a timed potion effect |
| `trap_chaos` | — | Force-triggers a random team's queued trap, if any are queued |
| `weather_change` | `CLEAR`, `RAINING`, or `UNTOUCHED` | Scripted weather change |
| `time_change` | `NOON`, `SUNSET`, `NIGHT`, or `UNTOUCHED` | Scripted time-of-day change |
| `announcement` | any message | Pure broadcast, no gameplay effect — script your own callouts |
| `fireworks` | — | Cosmetic firework show over the arena |

Custom events are capped at 15 per session and can be moved, duplicated, re-valued or deleted
like any other entry once created (from the command line, reference them by the id
`/ea timeline list` shows for them).

### Shop Overrides

Disable individual shop items for just this match, or change what they cost (amount + currency),
from Arena Modifiers → **Shop Items**. Click an item to toggle it on/off; shift-click to open the
price editor (±1/±10 buttons, cycle currency, reset to default). Also editable via
`/ea shop list|disable|enable|price|resetprice|reset`. Disabled items are re-skinned to red dye
wherever the shop is opened — the normal category pages and MBedwars' Quick Buy home screen
alike — or hidden entirely if `shop.disabled_display: remove`.

### Team Size

Change how many players fit on each team for this match from Arena Modifiers → **Team Size**,
Match Controls → Manage Teams → **Team Size**, or `/ea teamsize <amount|reset>` (±1 buttons,
1–8, reset to the arena's own default). Editable
for the whole lobby phase, regardless of how many players are already in — only locked once the
match is actually `RUNNING`. Changing it while players already hold teams **unassigns everyone
from their team** (they stay in the lobby, not kicked from the arena) with a message explaining
why, since a roster picked under the old cap may no longer fit the new one. The override is
temporary to this match; the arena's own value is restored once it ends.

### Environment (Time & Weather)

Set the arena's time of day and weather for this match from Arena Modifiers → **Environment**
(cycle buttons) or `/ea weather <clear|rain|off>` / `/ea time <noon|sunset|night|off>`. Purely
cosmetic — a per-player visual effect with no gameplay-balance impact — so it's editable any
time, match running or not, and applies instantly to everyone currently in the arena plus anyone
who joins afterward. Resets to untouched once the match ends, so it never carries over into
whatever match (private or public) uses the arena next. The same weather/time changes are also
available as scripted [timeline events](#event-timeline) (`weather_change`/`time_change`) if you
want them to happen automatically partway through a match instead of being set up front.

### Saved Configurations (Presets)

Snapshot a match's setup — event timeline, shop overrides, team size, match rules and
environment — as a named preset from Arena Modifiers → **Saved Configurations**, then apply it to
any later match (click), preview it first (right-click), or delete it (shift-click).

**Preview** opens a read-only breakdown so you never have to apply a configuration just to find
out what's in it: every scheduled event with its time, the disabled and repriced shop items by
name, the team size, which match rules it changes, its environment, and a *What would change*
card comparing all of that against the match's current setup. Apply and Delete are on that
screen too. Whether teams are currently locked is deliberately *not* part of a preset — it's
live lobby state, so applying a configuration never locks or unlocks a lobby underneath the
players standing in it.
Clicking **Save Current Setup** opens an anvil where you type the name — take the result item to
confirm, or press Escape to cancel; nothing is spent, the level-cost UI is cosmetic only. Also
editable via `/ea preset list|save|apply|delete`. Capped at 20 saved presets per player; names
are 1–24 characters of letters, numbers, `-`, and `_`.

### Spectating

- Joining a Join Code match that's already `RUNNING` automatically spectates you instead of
  failing.
- The [spectate lobby item](#custom-lobby-hotbar-items) lets any active player opt out of
  playing — immediately, not just at round start — freeing their slot for someone else. It
  disappears once they're actually spectating.
- The [rejoin lobby item](#custom-lobby-hotbar-items) lets any spectator switch back to playing,
  as long as the arena is still in its lobby and has a free player slot — however they ended up
  spectating (opted out, died and got moved to spectator, joined a running match, …).

---

## Custom Lobby Hotbar Items

ExclusiveArenas registers four MBedwars lobby hotbar item **handlers**. Registering only makes a
handler available by id — it won't appear anywhere until you add an entry referencing it to
your live `lobby-hotbar.yml` (under `plugins/MBedwars/configs/.../lobby-hotbar.yml`; see
MBedwars' own docs for the exact versioned path). Slot is yours to choose; name/item are just
placeholders — every handler below overrides its own icon/name/lore at render time. All four
items only ever appear inside an ExclusiveArenas private match — never on a regular public arena.
`toggle-spectate` and `rejoin-as-player` are mutually exclusive (only an active player can opt
out; only a spectator can rejoin), so they conventionally share a slot.

```yaml
open-controls:
  name: '&eMatch Controls'
  slot: 2
  handler: 'exclusivearenas:open_controls'    # visible only to the match's host
  item: 'command_block'

toggle-spectate:
  name: '&7Spectate This Match'
  slot: 3
  handler: 'exclusivearenas:toggle_spectate'   # visible to any active player
  item: 'gray_dye'                             # hidden once they're actually spectating

rejoin-as-player:
  name: '&aRejoin as Player'
  slot: 3
  handler: 'exclusivearenas:rejoin_as_player'  # visible to any spectator
  item: 'lime_dye'                             # hidden once the round is running or full

summon-party:
  name: '&eSummon Party'
  slot: 4
  handler: 'exclusivearenas:summon_party'      # host only, and only on party-gated matches
  item: 'ender_pearl'
```

- **`exclusivearenas:open_controls`** — opens Match Controls for the host, without needing
  `/ea lobby`.
- **`exclusivearenas:toggle_spectate`** — lets an active player opt out of playing. Using it
  immediately converts them to a spectator — leaving their team and freeing their player slot
  right away, rather than waiting until the round starts. Hidden once they're spectating; from
  there, `rejoin_as_player` is the way back.
- **`exclusivearenas:rejoin_as_player`** — lets a spectator switch back to playing. Only visible
  while the round isn't `RUNNING` and the arena has a free player slot; actually rejoining still
  requires the arena to be in its lobby (a friendly error otherwise).
- **`exclusivearenas:summon_party`** — visible only to the host, and only when the match is
  Party-policy; pulls their online party members straight into the lobby (same action as Match
  Controls' **Summon Party** button).

---

## Boss Bar

Everyone inside a private match's arena sees a boss bar stating that it's private, its current
join policy (the join code itself is hidden while the match is locked), and — while the match is
running — whether MBedwars' in-game timer is currently paused. Bukkit has no true "orange" boss
bar color, so it renders as the closest built-in (yellow) with gold-colored text. Toggle with
`private.bossbar_enabled` in `config.yml`.

---

## Session Cleanup

A background task (every 30 seconds) ends sessions that have been abandoned, gone stale, or sat
inactive, so forgotten private matches don't pile up:

- **Abandoned** — the host left the lobby and hasn't returned within
  `private.host_abandon_timeout_minutes` (default 5). Ends immediately, with an in-arena
  broadcast.
- **Stale** — the session was created more than `private.stale_session_hours` ago (default 12)
  and the arena isn't actively running. A last-resort safety net for stuck state.
- **Inactive** — the lobby has had zero active players (everyone spectating, or nobody ever
  joined) for `private.inactivity_warning_minutes` (default 10). Everyone online — anyone
  actually in the arena, plus the host if they're online elsewhere — gets warned that it'll
  close soon; if it's still empty of active players after a further
  `private.inactivity_close_grace_minutes` (default 5), the session ends. Set
  `inactivity_warning_minutes` to `0` to disable this check.

---

## Stability & Health Monitoring

A separate background task (every `stability.health_check_seconds`, default 30) continuously
checks every active session against MBedwars' *actual* live arena state — not just trusted to
stay in sync — and self-heals what it finds, logging every corrective action so admins can audit
what the plugin did on its own:

- A session whose arena has silently gone `STOPPED` (crashed reset, a manual admin action,
  anything not routed through ExclusiveArenas) is ended instead of lingering.
- A match `RUNNING` far longer than the timeline's own match-length cap plus
  `stability.stuck_match_grace_seconds` (default 300) is logged as possibly stuck. It's only
  ever force-ended if `stability.force_end_stuck_matches` is explicitly turned on (default off)
  — until then it's just logged, never touched automatically.
- A generator whose drop timer stops ticking is flagged as a possible desync (warn-only —
  auto-"fixing" a spawner is riskier than reporting it).
- MBedwars' own arena configuration issues (missing bed/spawn/lobby, etc.) are surfaced as a log
  warning instead of silently misbehaving.

This task never messages players — only the console.

---

## Hiding Private Arenas from MBedwars' ArenasGUI

ExclusiveArenas registers an MBedwars arena-picker condition variable named
`exclusivearenas_private` (`1` while an arena hosts one of our private matches, else `0`). To
keep reserved private lobbies out of MBedwars' own ArenasGUI menu, add a condition to the
`arenas-collection` element(s) of your live ArenasGUI layout file(s)
(`plugins/MBedwars/arenasgui-layouts/...`):

```yaml
- type: arenas-collection
  condition: "[exclusivearenas_private=0]"
  area: ...
```

---

## Network (Multi-Server) Setup

ExclusiveArenas runs single-server out of the box (fully in-memory) or across a network of any
number of hubs and arena servers, sharing state through **its own MySQL/MariaDB database** —
not MBedwars' internal storage or its RemoteAPI messaging, since MBedwars exposes no general
cross-plugin messaging channel and may run on a non-shared SQLite backend.

1. Set up a MySQL/MariaDB database reachable from every server.
2. On **every** server (hubs and arena servers alike), set in `config.yml`:
   ```yaml
   server_id: "unique-per-server"   # must be distinct on every server
   is_hub_server: true              # only on hub/lobby servers
   database:
     enabled: true
     host: "..."
     port: 3306
     database: "exclusivearenas"
     user: "..."
     password: "..."
   ```
3. Every server mirrors all active sessions/tickets/commands via polling
   (`database.session_poll_seconds`, `ticket_poll_seconds`, `command_poll_seconds`), so a session
   created on one server is immediately usable from any other.

**Requirement: arena names must be unique across the whole network.** Sessions are looked up by
bare arena name only, network-wide — two different arena servers each having an arena with the
same name will collide (the second silently overwrites the first's session row). Unique
*display* names are unaffected — only the actual MBedwars arena name needs to be unique; nothing
in ExclusiveArenas reads MBedwars' display-name feature.

### Remote Control

A host can manage their match from another arena server or from a hub, not just from the arena
itself:

- **Start Match** / **End Match** relay through the shared database to whichever server actually
  hosts the arena if you aren't standing on it.
- **Join Policy**, **Summon Party**, and **Regenerate Code** never needed the arena to be local
  in the first place.
- **Manage Teams** is the one exception — it requires being on the arena's own server, since
  MBedwars' RemoteAPI doesn't expose per-player team assignments for a remote arena.

### Crash Resilience

Every server stamps its own heartbeat row on every session poll. Separately, every server also
periodically (`database.dead_server_sweep_seconds`, default 120) sweeps for other servers that
have gone quiet for longer than `database.dead_server_after_seconds` (default 90) and purges
their orphaned sessions, tickets, and commands from the shared database — so a crashed backend's
private matches don't sit around the network looking "active" forever. Safe for multiple
servers to notice and purge the same dead server concurrently. Keep `dead_server_after_seconds`
a generous multiple of `session_poll_seconds` (the default is ~22x) so a slow poll or brief
network blip is never mistaken for an actual crash.

### Party plugins across multiple hubs

Party-policy gating and auto-summon rely entirely on MBedwars' `PartiesHook`. If your party
plugin isn't itself network-synced, a party split across two different hubs won't be recognized
as one party — this is a property of the party plugin, not something ExclusiveArenas controls.

---

## Known Limitations

- **Team management requires the arena's own server.** See [Remote Control](#remote-control).
- **Arena names must be unique network-wide.** See [Network Setup](#network-multi-server-setup).
- **`server_id` uniqueness isn't validated.** Cloning one server's data folder to spin up
  another without editing `server_id` won't break session logic, but does corrupt
  debug/attribution info.
- **No per-match cosmetic control.** MBedwars exposes no cosmetics API to hook into.
- **Auto-summon isn't exposed anywhere.** The continuous party-sync machinery
  (`private.auto_summon_enabled`) still exists internally but has no command or menu right now —
  use **Summon Party** for a one-time pull instead.
- **Swap Teams has no GUI team-picker.** Two teams is one click too many for a menu button —
  it's `/ea swapteams <team-a> <team-b>` only; the Quick Actions menu item just points you at it.
- **Reveal Border only works on the arena's own server.** The border particles are shown to you
  directly and can't be relayed cross-server, unlike most other quick actions.
- **Locking teams can't stop a plugin that reassigns teams outright.** MBedwars has no cancellable
  "player wants a different team" hook, so the lock refuses the team-selection UI and, as a
  backstop, puts anyone moved by another route back on their previous team a tick later. A third
  plugin that force-assigns teams would therefore see its change reverted rather than blocked.

---

## Configuration Reference

See `config.yml` — every key is commented inline, including the setup snippets referenced above.

---

## Versioning

- Plugin version follows [Semantic Versioning](https://semver.org/).
- `config.yml`, `lang.yml`, and `guis.yml` are each versioned independently via their own
  `config-version` key. Each migrates and self-heals on startup — any key missing from your copy
  (new install or after an update) is restored with its bundled default value automatically, and
  values that still match an old default are moved forward to the new one where it makes sense
  (e.g. a moved menu button). You do not need to delete any of the three when updating; edits to
  values you've actually changed are always preserved.
