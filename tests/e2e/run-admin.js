// Admin, limits, menus beyond Match Controls, team locking and misc command paths.
'use strict';
const { spawnBot, sleep, server, test, assert, summary } = require('./lib');

const ARENA = process.env.ARENA || 'Acropolis-duels-1';
const ARENA2 = process.env.ARENA2 || 'Ahoy-duels';

(async () => {
  const host = await spawnBot('ea_host');
  const guest = await spawnBot('ea_guest');
  const logStart = server.logSize();

  async function resetState() {
    for (const b of [host, guest]) b.cmd('bw leave');
    await sleep(2500);
    for (const b of [host, guest]) {
      for (let i = 0; i < 3; i++) {
        b.cmd('ea end');
        const m = await b.waitFor(/Ended the private match|You aren't hosting a private match|Only the host/, { timeout: 6000 });
        if (!/Ended/.test(m[0])) break;
      }
    }
    await sleep(6000);
  }
  await resetState();
  let code;

  await test('/ea help and /ea list open their menus', async () => {
    host.cmd('ea help');
    const w = await host.waitForWindow(/Help/);
    assert(host.guiFind(/\/ea join/, w), 'help cards missing');
    await host.closeWindow();
    host.cmd('ea list');
    const l = await host.waitForWindow(/Your Private Matches/);
    assert(host.guiFind(/No active matches/, l), 'expected empty-state pane');
    await host.closeWindow();
  });

  await test('host limit of 1 and arena reservation are enforced', async () => {
    host.cmd(`ea create ${ARENA} nojoin`);
    await host.waitFor(new RegExp(`Created your private match on ${ARENA}`));
    code = (await host.waitFor(/Join code: (\S+)/))[1];
    await host.waitFor(/Use Go to Arena in Match Controls/);
    host.cmd(`ea create ${ARENA2}`);
    await host.waitFor(/You already host the maximum of 1 private matches/);
    guest.cmd(`ea create ${ARENA}`);
    await guest.waitFor(/already reserved by another private match|is not available right now/);
  });

  await test('/ea goto sends the host in; a guest inside cannot create', async () => {
    host.cmd('ea goto');
    await sleep(2500);
    host.cmd('ea lobby');
    await host.waitForWindow(/Match Controls/);
    await host.closeWindow();
    guest.cmd(`ea join ${code}`);
    await host.waitFor(/ea_guest.*joined/i, { timeout: 10000 });
    guest.cmd(`ea create ${ARENA2}`);
    await guest.waitFor(/Leave your current private match before creating another one/);
  });

  await test('Manage Teams: lock and unlock teams from the menu', async () => {
    host.cmd('ea lobby');
    await host.waitForWindow(/Match Controls/);
    await host.clickNamed(/Manage Teams/);
    const w = await host.waitForWindow(/Manage Teams/);
    assert(host.guiFind(/Distribute Players/, w), 'no Distribute button');
    guest.mark(); host.mark();
    await host.clickNamed(/Teams Unlocked/);
    await host.waitFor(/Teams are now locked/);
    await guest.waitFor(/The host has locked the teams/);
    await host.waitForWindow(/Manage Teams/);
    host.mark();
    await host.clickNamed(/Teams Locked/);
    await host.waitFor(/Teams are now unlocked/);
    await host.waitForWindow(/Manage Teams/);
    host.mark();
    await host.clickNamed(/Distribute Players/);
    await host.waitFor(/Distributed \d+ player/);
  });

  await test('Match Rules menu cycles a rule and resets', async () => {
    host.cmd('ea lobby');
    await host.waitForWindow(/Match Controls/);
    await host.clickNamed(/Arena Modifiers/);
    await host.waitForWindow(/Arena Modifiers/);
    await host.clickNamed(/Match Rules/);
    await host.waitForWindow(/Match Rules/);
    // Rule clicks re-render the menu with the new value in the lore (no chat feedback).
    const before = host.guiFind(/Friendly Fire/).lore.join(' | ');
    await host.clickNamed(/Friendly Fire/);
    await sleep(600);
    await host.waitForWindow(/Match Rules/);
    const after = host.guiFind(/Friendly Fire/).lore.join(' | ');
    assert(before !== after, 'friendly-fire lore unchanged: ' + after);
    await host.clickNamed(/Reset All Rules/);
    await sleep(600);
    await host.waitForWindow(/Match Rules/);
    const reset = host.guiFind(/Friendly Fire/).lore.join(' | ');
    assert(reset === before, 'reset did not restore the rule: ' + reset);
  });

  await test('Event Timeline menu opens unlocked in the lobby and resets', async () => {
    host.cmd('ea lobby');
    await host.waitForWindow(/Match Controls/);
    await host.clickNamed(/Arena Modifiers/);
    await host.waitForWindow(/Arena Modifiers/);
    await host.clickNamed(/Event Timeline/);
    const w = await host.waitForWindow(/Event Timeline/);
    const add = host.guiFind(/Add Event/, w);
    assert(add && !add.lore.some((l) => /round has started|status unavailable/.test(l)), 'editor locked: ' + JSON.stringify(add && add.lore));
    host.mark();
    await host.clickNamed(/Reset to Defaults/);
    await host.waitFor(/Event timeline restored to the defaults/);
  });

  await test('admin panel: refused without permission, lists the match with it', async () => {
    await host.closeWindow();
    guest.cmd('ea admin');
    await guest.waitFor(/You don't have permission for that/);
    const off = server.logSize();
    server.exec('op ea_guest');
    await server.waitForLog(/Made ea_guest a server operator|already|opped/i, off, { timeout: 6000 }).catch(() => {});
    await sleep(1000);
    guest.cmd('ea admin');
    const w = await guest.waitForWindow(/Admin/);
    assert(guest.guiFind(new RegExp(ARENA), w), 'admin list does not show the match');
    await guest.closeWindow();
    guest.cmd('ea reload');
    await guest.waitFor(/configuration reloaded/);
    server.exec('deop ea_guest');
    await sleep(800);
  });

  await test('console can reload the add-on', async () => {
    const off = server.logSize();
    server.exec('ea reload');
    await server.waitForLog(/ExclusiveArenas reloaded/, off, { timeout: 8000 });
  });

  await test('session survives a reload; End Match from the menu', async () => {
    host.cmd('ea lobby');
    await host.waitForWindow(/Match Controls/);
    host.mark();
    await host.clickNamed(/End Match/);
    await host.waitFor(new RegExp(`Ended the private match on ${ARENA}`));
    await guest.waitFor(/The private match has been ended/);
  });

  await test('no add-on errors in the server log during the run', async () => {
    const log = server.logSince(logStart);
    const bad = log.split('\n').filter((l) => /at com\.slg\.|Missing lang key|\[ExclusiveArenas\].*(SEVERE|Exception)/.test(l));
    assert(bad.length === 0, 'log lines:\n' + bad.slice(0, 10).join('\n'));
  });

  await resetState();
  await host.quit(); await guest.quit();
  process.exit(summary(__dirname + '/reports/admin.json') ? 0 : 1);
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
