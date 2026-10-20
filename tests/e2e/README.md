# End-to-end bot tests

Real Mineflayer bots against a real Paper + MBedwars server. No mocks: the bots run `/ea`
commands, click through the menus, use the lobby/spectator hotbar items and assert on what the
server tells them.

## Requirements

- Node 22 and `npm install` in this folder (installs Mineflayer).
- A locally running Paper 1.21.5 server with MBedwars, MBedwarsTweaks and the add-on installed,
  `online-mode=false` so bots can join, and no proxy-only party plugin (a PAF bridge without its
  proxy makes every party lookup wait 5 s).
- An arena named `Acropolis-duels-1` (or set `ARENA`), 1 player per team, in its lobby.
- Optional: the server's console on a FIFO plus its stdout in a file, so tests can run console
  commands and assert on the log:

```
mkfifo /tmp/mc-console.in
(while true; do cat /tmp/mc-console.in; done) | java -jar server.jar nogui > /tmp/mc.log 2>&1
```

## Running

```
export MC_HOST=127.0.0.1 MC_PORT=25589 MC_VERSION=1.21.5
export MC_CONSOLE=/tmp/mc-console.in MC_LOG=/tmp/mc.log   # optional
npm run core    # command surface: create/join/code/lock, overrides, timeline, presets, start, quick actions
npm run gui     # menus: builder, Match Controls, Arena Modifiers, Quick Actions, hotbar items
npm run admin   # help/list, limits, Manage Teams, Match Rules, admin panel, console reload
npm run quick   # every Quick Actions button clicked during a running match, ending with Force Win
npm test        # all four
```

Each scenario prints one line per check and writes `reports/<name>.json`. Bots send at most one
command per 1.2 s (vanilla's spam kick) and the scenarios clean up after themselves, so a run
can be repeated on the same server.

## Not covered

Party-gated matches (needs a server-side party plugin), anything cross-server, the abandon /
inactivity timers, and visual-only output such as particles.
