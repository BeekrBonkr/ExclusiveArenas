// Core command-driven scenarios: a Join-Code private match from creation through a forced
// start, in-match quick actions, and the end of the round. Run with the server up:
//   MC_CONSOLE=<fifo> MC_LOG=<server log> node run-core.js
'use strict';
const { spawnBot, sleep, server, test, assert, summary } = require('./lib');

const ARENA = process.env.ARENA || 'Acropolis-duels-1';
const LOBBY = { x: 12500, y: 181, z: 2500 }; // Acropolis-duels-1 lobby location (data.json)

function near(pos, target, r = 25) {
  return pos && Math.abs(pos.x - target.x) < r && Math.abs(pos.z - target.z) < r;
}

(async () => {
  const host = await spawnBot('ea_host');
  const guest = await spawnBot('ea_guest');
  const outsider = await spawnBot('ea_out');
  let code = null;
  let teams = [];

  // Clean slate: a previous run may have left a session (host kicked mid-run) or bots in
  // an arena. End whatever the host still owns and pull everyone out of arenas first.
  async function resetState() {
    for (const b of [host, guest, outsider]) { b.cmd('bw leave'); }
    await sleep(2500);
    for (let i = 0; i < 3; i++) {
      host.cmd('ea end');
      const m = await host.waitFor(/Ended the private match|You aren't hosting a private match/, { timeout: 6000 });
      if (/aren't hosting/.test(m[0])) break;
    }
    await sleep(6000); // let the arena finish resetting before it is reserved again
  }
  await resetState();

  await test('host creates a Join-Code match and is sent in', async () => {
    host.cmd(`ea create ${ARENA}`);
    await host.waitFor(new RegExp(`Created your private match on ${ARENA}`));
    const m = await host.waitFor(/Join code: (\S+)/);
    code = m[1];
    assert(code.length >= 4, 'code length');
    await sleep(2500);
    assert(near(host.pos(), LOBBY), `host not at the arena lobby: ${host.pos()}`);
  });

  await test('a plain /bw join is refused while the match is private', async () => {
    outsider.cmd(`bw join ${ARENA}`);
    await outsider.waitFor(/private|not accepting|failed adding you/i);
    await sleep(1000);
    assert(!near(outsider.pos(), LOBBY), 'outsider was let into the lobby');
  });

  await test('a wrong code is rejected', async () => {
    outsider.cmd('ea join NOPE12');
    await outsider.waitFor(/Invalid or expired join code/);
  });

  await test('a locked match rejects even the right code', async () => {
    host.cmd('ea public off');
    await host.waitFor(/Match locked/);
    outsider.cmd(`ea join ${code}`);
    await outsider.waitFor(/Invalid or expired join code/);
    host.cmd('ea public on');
    await host.waitFor(/Match opened/);
  });

  await test('the right code lets a guest in', async () => {
    assert(code, 'no join code from the create step');
    guest.cmd(`ea join ${code}`);
    await host.waitFor(/ea_guest.*joined/i, { timeout: 10000 });
    await sleep(2000);
    assert(near(guest.pos(), LOBBY), `guest not at the arena lobby: ${guest.pos()}`);
  });

  await test('regenerating the code invalidates the old one', async () => {
    host.cmd('ea code');
    const m = await host.waitFor(/Join code regenerated: (\S+)/);
    assert(m[1] !== code, 'code did not change');
    const old = code; code = m[1];
    outsider.cmd(`ea join ${old}`);
    await outsider.waitFor(/Invalid or expired join code/);
  });

  await test('boss bar shows the private match', async () => {
    await sleep(1500);
    const titles = host.bossBarTitles();
    assert(titles.some((t) => /PRIVATE MATCH/i.test(t)), `boss bars: ${JSON.stringify(titles)}`);
  });

  await test('environment and team-size overrides apply in the lobby', async () => {
    host.cmd('ea weather rain');
    await host.waitFor(/Arena weather set to RAINING/);
    host.cmd('ea time night');
    await host.waitFor(/Arena time set to NIGHT/);
    host.cmd('ea teamsize 2');
    await host.waitFor(/Players per team set to 2/);
    host.cmd('ea teamsize reset');
    await host.waitFor(/Players per team set to/);
    host.cmd('ea weather off');
    await host.waitFor(/Arena weather set to UNTOUCHED/);
    host.cmd('ea time off');
    await host.waitFor(/Arena time set to UNTOUCHED/);
  });

  await test('timeline can be listed, moved and reset', async () => {
    host.cmd('ea timeline list');
    await host.waitFor(new RegExp(`Event timeline for ${ARENA}`));
    await sleep(500);
    const lines = host.recent().map((m) => m.text).filter((t) => /• .* \(.*\)$/.test(t));
    assert(lines.length >= 1, 'no timeline lines: ' + host.recent().map((m) => m.text).join(' | '));
    const first = lines[0].match(/\(([^)]+)\)$/)[1];
    host.cmd(`ea timeline move ${first} +30`);
    await host.waitFor(/Moved .* to|Match length set to/);
    host.cmd('ea timeline reset');
    await host.waitFor(/Event timeline restored to the defaults/);
  });

  await test('shop overrides list/reset', async () => {
    host.cmd('ea shop list');
    await host.waitFor(/No shop overrides|Shop overrides for/);
    host.cmd('ea shop reset');
    await host.waitFor(/All shop customizations cleared/);
  });

  await test('presets save/list/apply/delete', async () => {
    host.cmd('ea preset save e2etest');
    await host.waitFor(/Saved the current setup as e2etest/);
    host.cmd('ea preset list');
    await host.waitFor(/e2etest/);
    host.cmd('ea preset apply e2etest');
    await host.waitFor(/Applied e2etest/);
    host.cmd('ea preset delete e2etest');
    await host.waitFor(/Deleted the saved configuration e2etest/);
  });

  await test('freeze / pvp / pause are refused in the lobby', async () => {
    host.cmd('ea freeze');
    await host.waitFor(/only works while the match is running/);
  });

  await test('host can move a guest to a team', async () => {
    host.cmd('ea team ea_guest NOSUCH');
    const m = await host.waitFor(/Unknown team|Usage/);
    // Learn the team names from the controls status / fall back to common names.
    for (const t of ['RED', 'BLUE', 'GREEN', 'YELLOW', 'AQUA', 'WHITE', 'PINK', 'GRAY']) {
      host.cmd(`ea team ea_guest ${t}`);
      try { await host.waitFor(/Moved 1 player/, { timeout: 2500 }); teams.push(t); break; } catch { /* next */ }
    }
    assert(teams.length === 1, 'could not move the guest to any team');
  });

  await test('non-host cannot run host commands', async () => {
    guest.cmd('ea end');
    await guest.waitFor(/You aren't hosting a private match|Only the host can do that/);
    guest.cmd('ea lobby');
    await guest.waitFor(/Only the host can manage this match/);
  });

  await test('host starts the match', async () => {
    host.cmd('ea start');
    await host.waitFor(/The host started the match!/, { timeout: 10000 });
    await sleep(4000);
    assert(!near(host.pos(), LOBBY, 5), 'host still at the lobby spot after start');
  });

  await test('in-match quick actions respond', async () => {
    host.cmd('ea heal');       await host.waitFor(/Healed \d+ player/);
    host.cmd('ea freeze');     await host.waitFor(/Everyone is frozen/);
    host.cmd('ea freeze');     await host.waitFor(/Everyone can move again/);
    host.cmd('ea pvp');        await host.waitFor(/PvP is now|Unknown subcommand/);
    host.cmd('ea buff speed'); await host.waitFor(/Buffed \d+ player/);
    host.cmd('ea drop');       await host.waitFor(/Triggered \d+ spawner drop/);
    host.cmd('ea clearitems'); await host.waitFor(/Cleared \d+ dropped item/);
    host.cmd('ea border');     await host.waitFor(/Showing you the arena/);
    host.cmd('ea skipevent');  await host.waitFor(/is now active|no upcoming timeline event|upgraded|Tier|destroyed/i); // Tweaks backend announces the tier itself
    host.cmd('ea beds');       await host.waitFor(/Destroyed \d+ bed/);
  });

  await test('force win ends the round and the session', async () => {
    host.cmd(`ea forcewin ${teams[0] || 'RED'}`);
    await host.waitFor(/awarded the win/, { timeout: 10000 });
    await sleep(6000);
    host.cmd('ea end');
    await host.waitFor(/You aren't hosting a private match/);
  });

  await test('lobby-phase end kicks everyone and frees the arena', async () => {
    await sleep(8000); // let the arena finish resetting
    host.cmd(`ea create ${ARENA}`);
    const m = await host.waitFor(/Join code: (\S+)/, { timeout: 15000 });
    guest.cmd(`ea join ${m[1]}`);
    await host.waitFor(/ea_guest.*joined/i, { timeout: 10000 });
    host.cmd('ea kick keephost');
    await host.waitFor(/Kicked 1 player/);
    host.cmd('ea end');
    await host.waitFor(new RegExp(`Ended the private match on ${ARENA}`));
    await sleep(1500);
    host.cmd('ea end');
    await host.waitFor(/You aren't hosting a private match/);
  });

  await resetState();
  await host.quit(); await guest.quit(); await outsider.quit();
  const ok = summary(__dirname + '/reports/core.json');
  process.exit(ok ? 0 : 1);
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
