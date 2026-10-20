// GUI-driven scenarios: builds a match through the menus, drives Match Controls / Arena
// Modifiers / Quick Actions by clicking, and exercises the lobby hotbar items (spectate,
// rejoin, open controls).
'use strict';
const { spawnBot, sleep, test, assert, summary, plain, windowTitle, itemName } = require('./lib');

const ARENA = process.env.ARENA || 'Acropolis-duels-1';

(async () => {
  const host = await spawnBot('ea_host');
  const guest = await spawnBot('ea_guest');

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
  let code = null;

  await test('main menu opens with the context button and help', async () => {
    host.cmd('ea');
    const w = await host.waitForWindow(/ExclusiveArenas/);
    assert(host.guiFind(/Create New Arena/, w), 'no Create New Arena button');
    assert(host.guiFind(/Help/, w), 'no Help button');
    assert(!host.guiFind(/Admin Panel/, w), 'Admin Panel shown to a non-admin');
  });

  await test('builder → select map → pick arena → Create & Join', async () => {
    await host.clickNamed(/Create New Arena/);
    await host.waitForWindow(/New Private Match/);
    const policy = host.guiFind(/Join Policy/);
    assert(policy && policy.lore.some((l) => /No party plugin installed|aren't leading a party/.test(l)),
      'policy lore: ' + JSON.stringify(policy && policy.lore));
    assert(policy.lore.some((l) => /No party plugin installed/.test(l)), 'standalone policy hint missing: ' + JSON.stringify(policy.lore));
    await host.clickNamed(/Select Map/);
    await host.waitForWindow(/Select Map/);
    const entry = host.guiFind(new RegExp('^' + ARENA + '$'));
    assert(entry, 'arena not listed; items: ' + host.guiItems().map((i) => i.name).filter(Boolean).join(', '));
    assert(entry.lore.some((l) => /Location: Local/.test(l)), 'location lore: ' + JSON.stringify(entry.lore));
    await host.click(entry.slot);
    await host.waitForWindow(/New Private Match/);
    const create = host.guiFind(/Create & Join/);
    assert(create, 'no Create & Join button');
    host.mark();
    await host.click(create.slot);
    await host.waitFor(new RegExp(`Created your private match on ${ARENA}`));
    code = (await host.waitFor(/Join code: (\S+)/))[1];
  });

  await test('Match Controls status card and buttons', async () => {
    await sleep(2500);
    host.cmd('ea lobby');
    const w = await host.waitForWindow(/Match Controls/);
    const card = host.guiFind(new RegExp(ARENA), w);
    assert(card, 'no status card');
    const lore = card.lore.join(' | ');
    assert(/State: Lobby/.test(lore), 'state lore: ' + lore);
    assert(/Players: 1\//.test(lore), 'players lore: ' + lore);
    assert(/Access: Code/.test(lore) && lore.includes(code), 'access lore: ' + lore);
    const teams = host.guiFind(/Manage Teams/, w);
    assert(teams && teams.lore.some((l) => /Click to open/.test(l)), 'manage-teams lore: ' + JSON.stringify(teams && teams.lore));
    assert(host.guiFind(/^Start Match$/, w), 'no Start Match');
    for (const name of [/Public|Locked/, /Regenerate Code/, /Go to Arena/, /Kick All Players/, /End Match/, /Quick Actions/, /Arena Modifiers/]) {
      assert(host.guiFind(name, w), 'missing button ' + name);
    }
    // No network wording anywhere on a standalone server with a local arena.
    const all = host.guiItems(w).map((i) => i.name + ' ' + i.lore.join(' ')).join('\n');
    assert(!/another server|shared database|controlling remotely/i.test(all), 'network wording leaked: ' + all);
  });

  await test('Regenerate Code and Lock/Unlock via the menu', async () => {
    host.mark();
    await host.clickNamed(/Regenerate Code/);
    const m = await host.waitFor(/Join code regenerated: (\S+)/);
    assert(m[1] !== code, 'code unchanged'); code = m[1];
    await host.waitForWindow(/Match Controls/);
    host.mark();
    await host.clickNamed(/^Public$|^Locked$/);
    await host.waitFor(/Match locked|Match opened/);
    await host.waitForWindow(/Match Controls/);
    host.mark();
    await host.clickNamed(/^Public$|^Locked$/);
    await host.waitFor(/Match locked|Match opened/);
  });

  await test('Arena Modifiers → Environment cycles weather; Team Size +1/-1', async () => {
    await host.waitForWindow(/Match Controls/);
    await host.clickNamed(/Arena Modifiers/);
    const w = await host.waitForWindow(/Arena Modifiers/);
    const env = host.guiFind(/Environment/, w);
    assert(env && !env.lore.some((l) => /Unavailable/.test(l)), 'environment marked unavailable: ' + JSON.stringify(env && env.lore));
    await host.clickNamed(/Environment/);
    await host.waitForWindow(/Environment/);
    // The menu gives no chat feedback; it re-renders with the new value in the item's lore.
    const before = host.guiFind(/Weather/).lore.join(' | ');
    await host.clickNamed(/Weather/);
    await sleep(600);
    await host.waitForWindow(/Environment/);
    const after = host.guiFind(/Weather/).lore.join(' | ');
    assert(before !== after, 'weather lore did not change: ' + after);
    await host.clickNamed(/Weather/); await sleep(400); // cycle on (clear→rain→off→…)
    await host.clickNamed(/Weather/); await sleep(400);
    await host.waitForWindow(/Environment/);
    await host.clickNamed(/Back/);
    await host.waitForWindow(/Arena Modifiers/);
    await host.clickNamed(/Team Size/);
    await host.waitForWindow(/Team Size/);
    assert(host.guiFind(/Players Per Team/), 'no team-size display');
    host.mark();
    await host.clickNamed(/\+1/);
    await host.waitFor(/Players per team set to/);
    await host.waitForWindow(/Team Size/);
    await host.clickNamed(/Reset to Default/);
    await host.waitFor(/Players per team set to/);
  });

  await test('Quick Actions menu has no unavailable markers for a local arena', async () => {
    host.cmd('ea lobby');
    await host.waitForWindow(/Match Controls/);
    await host.clickNamed(/Quick Actions/);
    const w = await host.waitForWindow(/Quick Actions/);
    const items = host.guiItems(w).filter((i) => i.name);
    assert(items.length >= 20, 'only ' + items.length + ' quick action items');
    const bad = items.filter((i) => i.lore.some((l) => /Unavailable/.test(l)));
    assert(bad.length === 0, 'unavailable markers on: ' + bad.map((i) => i.name).join(', '));
    host.mark();
    await host.clickNamed(/Reveal Arena Border/);
    await host.waitFor(/Showing you the arena/);
  });

  await test('guest joins; lobby hotbar: spectate, then rejoin as player', async () => {
    await host.closeWindow();
    guest.cmd(`ea join ${code}`);
    await host.waitFor(/ea_guest.*joined/i, { timeout: 10000 });
    await sleep(2500);
    const names = guest.bot.inventory.slots.slice(36, 45).map((it) => (it ? plain(itemName(it)) : null));
    const spectIdx = guest.bot.inventory.slots.slice(36, 45).findIndex((it) => it && it.name === 'gray_dye');
    assert(spectIdx >= 0, 'no spectate item in hotbar: ' + JSON.stringify(names));
    guest.mark();
    guest.bot.setQuickBarSlot(spectIdx);
    await sleep(300);
    guest.bot.activateItem();
    await guest.waitFor(/You're now spectating/);
    await sleep(2000);
    const rejoinIdx = guest.bot.inventory.slots.slice(36, 45).findIndex((it) => it && it.name === 'lime_dye');
    assert(rejoinIdx >= 0, 'no rejoin item in spectator hotbar');
    host.mark(); guest.mark();
    guest.bot.setQuickBarSlot(rejoinIdx);
    await sleep(300);
    guest.bot.activateItem();
    await host.waitFor(/ea_guest.*joined/i, { timeout: 10000 });
  });

  await test('host hotbar item opens Match Controls; Kick All then End Match', async () => {
    const idx = host.bot.inventory.slots.slice(36, 45).findIndex((it) => it && it.name === 'command_block');
    assert(idx >= 0, 'no Match Controls item in host hotbar');
    host.bot.setQuickBarSlot(idx);
    await sleep(300);
    host.bot.activateItem();
    await host.waitForWindow(/Match Controls/);
    host.mark();
    await host.clickNamed(/Kick All Players/, { shift: true });
    await host.waitFor(/Kicked 1 player\(s\) from the arena — you stayed/);
    await host.waitForWindow(/Match Controls/);
    host.mark();
    await host.clickNamed(/End Match/);
    await host.waitFor(new RegExp(`Ended the private match on ${ARENA}`));
  });

  await resetState();
  await host.quit(); await guest.quit();
  process.exit(summary(__dirname + '/reports/gui.json') ? 0 : 1);
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
