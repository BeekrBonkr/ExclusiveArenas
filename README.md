# ExclusiveArenas

An MBedwars add-on that lets players host **private, gated BedWars matches** — restricted to
party members or to players holding a join code — with a full in-game GUI for managing them,
optional cross-server control, and a couple of extra lobby hotbar items.

---

## Requirements

| Dependency | Notes |
|---|---|
| Paper 1.21.x | Earlier versions may work but are untested |
| MBedwars | Must be installed and configured; ExclusiveArenas registers as a real MBedwars add-on |
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
| `/ea list` (or `arenas`) | Open Arena Management — the matches you host |
| `/ea help` | In-game help panel |
| `/ea join <code>` | Join a private match by its join code |
| `/ea lobby` (or `controls`) | Open Match Controls for the match you're standing in |
| `/ea start` | Start the match right now (host only) — also works when controlling remotely |
| `/ea end` | End the match (host only) — also works when controlling remotely |
| `/ea summon` | Summon your whole party into the match (Party policy, host only) |
| `/ea admin` | Admin Panel — every active match on the network (`exclusivearenas.admin`) |
| `/ea reload` | Reload config, reconnect the database, resync sessions (`exclusivearenas.admin`) |

`start`/`end`/`summon` work even when you aren't physically standing in your arena — they fall
back to whichever match you host (or, in a database setup, relay the request to whichever
server actually hosts the arena; see [Remote Control](#remote-control)).

---

## Hosting a Private Match

1. Run `/ea` and click **Arena Management**, then **Create New Arena** (or `/ea arena` directly).
2. Click **Select Map** and choose an arena from the list. Each entry shows the arena's own
   MBedwars icon, and can be filtered by **team count** and **players per team** — the filter
   buttons cycle through values discovered from your actual arena roster, not a fixed list.
3. Choose your **Join Policy**:
   - **Party Only** — only members of your MBedwars party may join. You must be the *leader*
     of a party to pick this (every Party-policy gating check assumes the host is a leader).
   - **Join Code** — players join with `/ea join <code>`. You can't pick this while you're in
     any party (mixing the two caused joiners to end up teleported in without properly joining).
4. **Auto-Summon** (Party policy only) — keeps your party continuously synced with the match:
   members not currently in it get pulled in (with a chat message explaining why), and anyone
   who leaves your party gets removed from the match.
5. Click **Create & Join** — the match is created and you're sent straight in, locally or across
   servers.

### Match Controls

Open via the arena's entry in **Arena Management**, `/ea lobby`, or the host-only
[lobby hotbar item](#custom-lobby-hotbar-items):

- **Start Match** — begins the round immediately. There's no pre-game timer to wait out; MBedwars'
  own automatic lobby countdown is suppressed entirely, so the match only ever starts when you
  press this. Requires at least one other player in the arena.
- **Join Policy** — switch between Party and Join Code live, while still in the lobby.
- **Public / Locked** (Code policy) or **Summon Party** (Party policy).
- **Regenerate Code** (Code policy) — invalidates the old code and issues a new one.
- **Manage Teams** — move players between teams while in the lobby (see below). Requires being
  on the arena's own server (MBedwars doesn't expose per-player team data remotely).
- **Auto-Summon** toggle (Party policy only).
- **Go to Arena** — teleport/connect to the match yourself.
- **Arena Settings** / **Quick Actions** — placeholder menus reserved for planned features
  (event timing, shop restrictions, cosmetics, one-click shortcuts); nothing in them does
  anything yet.
- **End Match** — closes the session and kicks everyone.

#### Manage Teams

Click a team to see its roster and open the player picker: every player currently in the arena
(not already on that team) shows as a head. A plain click moves that one player and returns you
to team selection; shift-click (or a plain click while others are already picked) stages
multiple players — shown with an enchant glint — for a batch move via the **Move N Selected
Player(s)** button. Clicking an already-staged head un-stages it. Trying to select more than
the team has room for flips the menu's title into a warning until you free up a slot.

### Spectating

- Joining a Join Code match that's already `RUNNING` automatically spectates you instead of
  failing.
- The [spectate-on-start lobby item](#custom-lobby-hotbar-items) lets any player opt out of
  playing before the round begins.

---

## Custom Lobby Hotbar Items

ExclusiveArenas registers two MBedwars lobby hotbar item **handlers**. Registering only makes a
handler available by id — it won't appear anywhere until you add an entry referencing it to
your live `lobby-hotbar.yml` (under `plugins/MBedwars/configs/.../lobby-hotbar.yml`; see
MBedwars' own docs for the exact versioned path). Slot, icon, and display name are entirely up
to you. Both items only ever appear inside an ExclusiveArenas private match — never on a regular
public arena.

```yaml
open-controls:
  name: '&eMatch Controls'
  slot: 2
  handler: 'exclusivearenas:open_controls'   # visible only to the match's host
  item: 'command_block'

toggle-spectate:
  name: '&7Spectate This Match'
  slot: 3
  handler: 'exclusivearenas:toggle_spectate'  # visible to any player in a private match
  item: 'gray_dye'
```

- **`exclusivearenas:open_controls`** — opens Match Controls for the host, without needing
  `/ea lobby`.
- **`exclusivearenas:toggle_spectate`** — lets a player opt out of playing. Using it leaves
  their team; using it again cancels the opt-out. Anyone still opted out when the round starts
  is converted to a spectator instead of being forced onto a team.

---

## Boss Bar

Everyone inside a private match's arena sees a boss bar stating that it's private, its current
join policy (the join code itself is hidden while the match is locked), and — while the match is
running — whether MBedwars' in-game timer is currently paused. Bukkit has no true "orange" boss
bar color, so it renders as the closest built-in (yellow) with gold-colored text. Toggle with
`private.bossbar_enabled` in `config.yml`.

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
- **Join Policy**, **Summon Party**, **Auto-Summon**, and **Regenerate Code** never needed the
  arena to be local in the first place.
- **Manage Teams** is the one exception — it requires being on the arena's own server, since
  MBedwars' RemoteAPI doesn't expose per-player team assignments for a remote arena.

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
- **Arena Settings / Quick Actions are stub menus.** Reserved navigation for planned features —
  nothing in them is functional yet.

---

## Configuration Reference

See `config.yml` — every key is commented inline, including the setup snippets referenced above.

---

## Versioning

- Plugin version follows [Semantic Versioning](https://semver.org/).
- `config-version` in `config.yml` is bumped when the config schema changes. Migration runs
  automatically on startup; you do not need to delete your config when updating.
