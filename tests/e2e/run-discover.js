// Discovery run: connects bots, runs the core create/join flow, and dumps everything the
// server said plus the GUI contents. Used to learn the exact message shapes before the
// real scenarios assert on them.
'use strict';
const { spawnBot, sleep, server, plain, windowTitle } = require('./lib');

const ARENA = process.env.ARENA || 'Acropolis-duels-1';

(async () => {
  const host = await spawnBot('ea_host');
  const guest = await spawnBot('ea_guest');
  console.log('spawned at', host.pos(), guest.pos());
  console.log('host sidebar:', host.sidebar());

  host.cmd(`ea create ${ARENA}`);
  await sleep(3000);
  console.log('--- host after create:'); host.recent().forEach((m) => console.log(`  [${m.position}] ${m.text}`));
  console.log('host pos', host.pos(), 'sidebar', host.sidebar(), 'bossbars', host.bossBarTitles());

  guest.cmd(`bw join ${ARENA}`);
  await sleep(2000);
  console.log('--- guest after bw join:'); guest.recent().forEach((m) => console.log(`  [${m.position}] ${m.text}`));

  const code = (host.messages.map((m) => m.text).join('\n').match(/Join code: (\S+)/) || [])[1];
  console.log('code =', code);
  guest.cmd(`ea join ${code}`);
  await sleep(3000);
  console.log('--- guest after ea join:'); guest.recent().forEach((m) => console.log(`  [${m.position}] ${m.text}`));
  console.log('--- host meanwhile:'); host.recent().forEach((m) => console.log(`  [${m.position}] ${m.text}`));
  console.log('guest pos', guest.pos(), 'sidebar', guest.sidebar(), 'bossbars', guest.bossBarTitles());

  host.cmd('ea');
  try {
    const w = await host.waitForWindow();
    console.log('--- main menu title:', plain(windowTitle(w)), 'slots', w.slots.length, 'inventoryStart', w.inventoryStart);
    host.guiItems(w).forEach((it) => console.log(`   ${it.slot} ${it.type} "${it.name}" | ${it.lore.join(' / ')}`));
  } catch (e) { console.log('no window:', e.message); }
  await host.closeWindow();

  host.cmd('ea end');
  await sleep(2000);
  console.log('--- host after end:'); host.recent().forEach((m) => console.log(`  [${m.position}] ${m.text}`));
  console.log('--- guest after end:'); guest.recent().forEach((m) => console.log(`  [${m.position}] ${m.text}`));

  await host.quit(); await guest.quit();
  process.exit(0);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
