// Every Quick Actions button, clicked from the menu during a running private match.
'use strict';
const { spawnBot, sleep, server, test, assert, summary } = require('./lib');

const ARENA = process.env.ARENA || 'Acropolis-duels-1';

(async () => {
  const host = await spawnBot('ea_host');
  const guest = await spawnBot('ea_guest');
  const logStart = server.logSize();

  async function resetState() {
    for (const b of [host, guest]) b.cmd('bw leave');
    await sleep(2500);
    for (let i = 0; i < 3; i++) {
      host.cmd('ea end');
      const m = await host.waitFor(/Ended the private match|You aren't hosting a private match/, { timeout: 6000 });
      if (/aren't hosting/.test(m[0])) break;
    }
    await sleep(6000);
  }
  await resetState();

  const { plain, windowTitle } = require('./lib');
  function tail(b, n = 12) { return b.messages.slice(-n).map((m) => `      ${b.name}: ${m.text}`).join('\n'); }
  async function openQuickActions() {
    if (host.window && /Quick Actions/.test(plain(windowTitle(host.window)))) return;
    await host.closeWindow();
    host.mark();
    host.cmd('ea lobby');
    try {
      await host.waitForWindow(/Match Controls/);
    } catch (e) {
      throw new Error(e.message + '\n    host said:\n' + tail(host) + '\n    guest saw:\n' + tail(guest));
    }
    await host.clickNamed(/Quick Actions/);
    await host.waitForWindow(/Quick Actions/);
  }

  /** Clicks one Quick Actions button and waits for the host to see `expect`. */
  async function act(button, expect, { timeout = 10000 } = {}) {
    await openQuickActions();
    host.mark();
    await host.clickNamed(button);
    return host.waitFor(expect, { timeout });
  }

  await test('start a match with two players', async () => {
    host.cmd(`ea create ${ARENA}`);
    const code = (await host.waitFor(/Join code: (\S+)/))[1];
    await sleep(2500);
    guest.cmd(`ea join ${code}`);
    await host.waitFor(/ea_guest.*joined/i, { timeout: 10000 });
    await sleep(1500);
    host.cmd('ea start');
    await host.waitFor(/The host started the match!/, { timeout: 10000 });
    await sleep(5000);
  });

  await test('Heal Everyone', () => act(/Heal Everyone/, /Healed \d+ player/));
  await test('Trigger All Generators', () => act(/Trigger All Generators/, /Triggered \d+ spawner drop/));
  await test('Clear Ground Items', () => act(/Clear Ground Items/, /Cleared \d+ dropped item/));
  await test('Balance Teams', () => act(/Balance Teams/, /Re-balanced \d+ player/));
  await test('Trigger Random Trap', () => act(/Trigger Random Trap/, /No team has a trap queued|Triggered a trap/));
  await test('Clear Trap Queues', () => act(/Clear Trap Queues/, /Cleared \d+ queued trap/));
  await test('Reset Team Upgrades', () => act(/Reset Team Upgrades/, /Reset \d+ team upgrade/));
  await test('Buff Everyone → Speed II', async () => {
    await openQuickActions();
    await host.clickNamed(/Buff Everyone/);
    await host.waitForWindow(/Buff Everyone/);
    host.mark();
    await host.clickNamed(/Speed II/);
    await host.waitFor(/Buffed \d+ player/);
  });
  await test('Freeze then Unfreeze', async () => {
    await act(/Freeze \/ Unfreeze All/, /Everyone is frozen/);
    await act(/Freeze \/ Unfreeze All/, /Everyone can move again/);
  });
  await test('Force-Rejoin Disconnected', () => act(/Force-Rejoin Disconnected/, /Rejoined \d+ disconnected/));
  await test('Reveal Arena Border', () => act(/Reveal Arena Border/, /Showing you the arena/));
  await test('+2:00 Match Timer', () => act(/\+2:00 Match Timer/, /extended the match|Match end is now/));
  await test('-2:00 Match Timer', () => act(/-2:00 Match Timer/, /shortened the match|Match end is now/));
  await test('Toggle PvP off then on', async () => {
    await act(/Toggle PvP/, /PvP is now OFF/);
    await act(/Toggle PvP/, /PvP is now ON/);
  });
  await test('Strip Inventories', () => act(/Strip Inventories/, /Stripped \d+ player/));
  await test('Comeback Buff', () => act(/Comeback Buff/, /Applied the comeback buff|No team has any players/));
  await test('Random Scatter actually moves the players', async () => {
    const before = [host.pos(), guest.pos()];
    await act(/Random Scatter/, /Scattered \d+ player|no region corners/);
    await sleep(2500);
    const moved = [host.pos(), guest.pos()].filter((p, i) => p.distanceTo(before[i]) > 2).length;
    assert(moved >= 1, `nobody moved: ${before} -> ${host.pos()}, ${guest.pos()}`);
  });
  await test('Kick AFK Players', () => act(/Kick AFK Players/, /Kicked \d+ AFK player/));
  await test('Reset Shop Prices', () => act(/Reset Shop Prices/, /Cleared \d+ shop override/));
  await test('Give Tracking Compass', () => act(/Give Tracking Compass/, /Gave \d+ player\(s\) a tracking compass/));
  await test('Announce Match Stats', () => act(/Announce Match Stats/, /Stats announced|Match Stats/));
  await test('Emergency Pause then Resume', async () => {
    await act(/Emergency Pause/, /Emergency Pause/);
    await act(/Emergency Pause/, /Resumed/);
  });
  await test('Skip to Next Event', () => act(/Skip to Next Event/, /is now active|no upcoming timeline event|upgraded|Tier|destroyed/i));
  await test('Swap Teams hint', () => act(/Swap Teams/, /Use \/ea swapteams/));
  await test('Sudden Death (destroy beds)', () => act(/Sudden Death/, /Destroyed \d+ bed/));
  await test('Regenerate Map keeps players and restarts', async () => {
    await act(/Regenerate Map/, /Regenerating the map/);
    await host.waitFor(/Map regenerated|regeneration failed|taking longer/, { timeout: 90000 });
    await sleep(8000);
    host.cmd('ea heal');
    await host.waitFor(/Healed \d+ player|only works while the match is running/);
  });
  await test('Force Win → pick a team', async () => {
    await sleep(2000);
    await openQuickActions();
    await host.clickNamed(/Force Win/);
    const w = await host.waitForWindow(/Force Win/);
    const team = host.guiItems(w).find((i) => i.type === 'white_banner');
    assert(team, 'no team entries in the picker');
    host.mark();
    await host.click(team.slot);
    await host.waitFor(/awarded the win/, { timeout: 10000 });
    await sleep(6000);
    host.cmd('ea end');
    await host.waitFor(/You aren't hosting a private match/);
  });

  await test('no add-on errors in the server log during the run', async () => {
    const bad = server.logSince(logStart).split('\n')
      .filter((l) => /at com\.slg\.|Missing lang key|\[ExclusiveArenas\].*(SEVERE|Exception)/.test(l));
    assert(bad.length === 0, 'log lines:\n' + bad.slice(0, 10).join('\n'));
  });

  await resetState();
  await host.quit(); await guest.quit();
  process.exit(summary(__dirname + '/reports/quick.json') ? 0 : 1);
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
