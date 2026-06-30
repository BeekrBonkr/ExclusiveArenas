# ExclusiveArenas

A MBedwars addon that lets players and tournament organizers create **private, gated BedWars matches**. Arenas can be restricted to party members or to players holding a join code, keeping public players out.

---

## Requirements

| Dependency | Notes |
|---|---|
| Paper 1.21.x | Earlier versions may work but are untested |
| MBedwars | Must be installed and configured |
| Party plugin (optional) | Any plugin with a MBedwars `PartiesHook` implementation |

---

## Installation

1. Drop `ExclusiveArenas-x.y.z.jar` into `plugins/MBedwars/addons/`.
2. Start or reload the server.
3. Configure `plugins/MBedwars/addons/ExclusiveArenas/config.yml` as needed.

**Updating:** Drop the new jar into `plugins/update/` (the standard Spigot update folder). ExclusiveArenas detects it on startup and moves it to the correct addons directory. Restart to apply.

---

## Permissions

| Permission | Default | Description |
|---|---|---|
| `exclusivearenas.command` | `true` | Use `/ea` commands |
| `exclusivearenas.bypass` | `op` | Join any private arena regardless of policy |

---

## Commands

| Command | Description |
|---|---|
| `/ea` or `/ea menu` | Open the main ExclusiveArenas menu |
| `/ea arena` | Open the private match builder directly |
| `/ea help` | Show the in-game help panel |
| `/ea join <code>` | Join a private arena using a join code |
| `/ea lobby` | Open lobby controls for your active private arena |
| `/ea start` | Start the lobby countdown (host only) |
| `/ea end` | End the private match and kick all players (host only) |
| `/ea summon` | Summon party members to the lobby (Party policy, host only) |
| `/ea cancel` | Cancel an active creation mode window |

---

## How to Create a Private Match

1. Run `/ea` to open the main menu and click **Create Private Arena**.
2. In the builder, click **Select Map** and choose an arena.
3. Choose your **Join Policy**:
   - **Party Only** — only members of your MBedwars party may join.
   - **Join Code** — players join with `/ea join <code>`.
4. For Join Code matches, toggle **Public** / **Private**:
   - **Public** — anyone with the code can join.
   - **Private** — joining is disabled (use as a "lock" toggle).
5. Click **Enter Creation Mode**. You have 45 seconds to open the MBedwars arena selector and join your chosen arena through the normal flow.
6. Once you join, that arena is claimed as your private match. No one else can join without authorization.

### While in the Lobby

- Use `/ea lobby` or click the NPC again to open the lobby controls panel.
- Press **Start Match** to begin the countdown.
- Press **Regenerate Code** (Code policy) to issue a new code and invalidate the old one.
- Press **Summon Party** (Party policy) to force party members into the lobby.
- Press **Public / Private** toggle (Code policy) to lock or unlock the arena.

### Host Leave & Return

The host can leave the lobby and rejoin at any time. The session remains active for a configurable timeout (default 5 minutes) after the lobby empties. If the host does not return within that window, the session ends and the arena is released.

---

## Network (Multi-Server) Setup

ExclusiveArenas is designed to run on both single-server and proxy-networked setups.

- Install on **every server** — the hub and all arena servers.
- The hub must have MBedwars loaded with RemoteAPI/ProxySync active so the arena list is visible.
- ExclusiveArenas uses MBedwars RemoteAPI for cross-server arena listing and plugin messaging for session state synchronization.
- Set `private.join_command_template` in `config.yml` to your proxy's join command (e.g., the ProxySync `/bw join %arena%` command).

---

## Known Limitations

- **Arena state 6**: The MBedwars public API (`ArenaStatus` enum) does not expose a custom "exclusive" state. Hiding the arena from the MBedwars arena browser is not currently possible without internal API access.
- **Mid-session party join**: If a player joins a party while already online (without rejoining the server), they are not automatically summoned. Use `/ea summon` in the lobby, or have them relog. This is a limitation of the MBedwars `PartiesHook` not exposing party-join events.
- **Sessions are in-memory only**: A server restart clears all active private sessions.

---

## Configuration Reference

See `config.yml` — every key is commented inline.

---

## Versioning

- Plugin version follows [Semantic Versioning](https://semver.org/).
- `config-version` in `config.yml` is bumped when the config schema changes. Migration runs automatically on startup; you do not need to delete your config when updating.
