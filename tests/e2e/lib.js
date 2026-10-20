// Shared helpers for the ExclusiveArenas end-to-end bot tests.
//
// Drives real Mineflayer bots against a locally running Paper server (online-mode=false)
// and talks to its console through a FIFO + log file. Nothing here is specific to a
// scenario; see the run-*.js files for the actual tests.
'use strict';

const fs = require('fs');
const mineflayer = require('mineflayer');

const cfg = {
  host: process.env.MC_HOST || '127.0.0.1',
  port: Number(process.env.MC_PORT || 25589),
  version: process.env.MC_VERSION || '1.21.5',
  consoleFifo: process.env.MC_CONSOLE,   // path to the server's console FIFO (optional)
  serverLog: process.env.MC_LOG,         // path to the server's stdout log (optional)
};

/** Strips § colour codes so assertions can match on plain text. */
function plain(s) {
  return String(s).replace(/§[0-9a-fk-orx]/gi, '');
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── Bots ───────────────────────────────────────────────────────────────────────

/**
 * Connects a bot and wraps it with a chat buffer and wait helpers. Every chat/system
 * message is recorded as {text, position, at} so a test can assert on anything the
 * server said, including messages that arrived before the assertion was written.
 */
async function spawnBot(username) {
  const bot = mineflayer.createBot({
    host: cfg.host, port: cfg.port, username, auth: 'offline', version: cfg.version,
    checkTimeoutInterval: 60_000,
  });
  const wrapper = {
    bot,
    name: username,
    messages: [],
    windows: [],       // every window opened, newest last
    bossBars: new Map(),
    cursor: 0,         // messages[] index where "since last mark" starts
    mark() { this.cursor = this.messages.length; return this.cursor; },
    /** Messages received since the last mark(). */
    recent() { return this.messages.slice(this.cursor); },
    _nextSend: 0,
    /**
     * Sends chat/commands no faster than one per 1.2s: vanilla's spam counter adds 20 per
     * message and decays 1 per tick, kicking at 200 — a bot firing commands back-to-back
     * gets "Kicked for spamming" after about ten of them.
     */
    _send(text) {
      const wait = Math.max(0, this._nextSend - Date.now());
      this._nextSend = Date.now() + wait + 1200;
      setTimeout(() => bot.chat(text), wait);
    },
    say(text) { this._send(text); },
    /** Runs a command and marks the buffer first so recent() shows only its output. */
    cmd(text) { this.mark(); this._send(text.startsWith('/') ? text : '/' + text); },
    /**
     * Waits for a message matching `re` to arrive after the current cursor. Resolves with
     * the match; rejects on timeout with the recent buffer in the error for diagnosis.
     */
    async waitFor(re, { timeout = 8000, since } = {}) {
      const start = since ?? this.cursor;
      const deadline = Date.now() + timeout;
      while (Date.now() < deadline) {
        for (let i = start; i < this.messages.length; i++) {
          const m = this.messages[i].text.match(re);
          if (m) return m;
        }
        await sleep(100);
      }
      const seen = this.messages.slice(start).map((m) => `    [${m.position}] ${m.text}`).join('\n');
      throw new Error(`${username}: no message matching ${re} within ${timeout}ms. Seen:\n${seen || '    (nothing)'}`);
    },
    /** True if a matching message arrived since the cursor (no waiting). */
    saw(re) { return this.recent().some((m) => re.test(m.text)); },
    /** Current open window (GUI) or null. */
    get window() { return bot.currentWindow || null; },
    /** Waits for a GUI to open (optionally with a title matching `titleRe`). */
    async waitForWindow(titleRe, { timeout = 6000 } = {}) {
      const deadline = Date.now() + timeout;
      while (Date.now() < deadline) {
        const w = bot.currentWindow;
        if (w && (!titleRe || titleRe.test(plain(windowTitle(w))))) return w;
        await sleep(100);
      }
      const w = bot.currentWindow;
      throw new Error(`${username}: no window${titleRe ? ' titled ' + titleRe : ''} within ${timeout}ms`
        + (w ? ` (open: "${plain(windowTitle(w))}")` : ' (none open)'));
    },
    /** Items in the open window's top inventory as [{slot, name, lore}] (empty slots skipped). */
    guiItems(w = bot.currentWindow) {
      if (!w) return [];
      const out = [];
      const top = w.inventoryStart ?? w.slots.length;
      for (let i = 0; i < top; i++) {
        const it = w.slots[i];
        if (!it) continue;
        out.push({ slot: i, type: it.name, name: plain(itemName(it)), lore: itemLore(it).map(plain) });
      }
      return out;
    },
    /** Finds a GUI item whose display name matches `re`. */
    guiFind(re, w = bot.currentWindow) {
      return this.guiItems(w).find((it) => re.test(it.name)) || null;
    },
    /** Clicks a GUI slot (left click), optionally shift. */
    async click(slot, { shift = false } = {}) {
      const w = bot.currentWindow;
      if (!w) throw new Error(`${username}: no window open to click slot ${slot}`);
      await bot.clickWindow(slot, 0, shift ? 1 : 0);
      await sleep(250);
    },
    /** Clicks the first GUI item whose name matches `re`; throws listing what IS there. */
    async clickNamed(re, opts) {
      const it = this.guiFind(re);
      if (!it) {
        const names = this.guiItems().map((i) => `${i.slot}:${i.name}`).join(', ');
        throw new Error(`${username}: no GUI item matching ${re}. Items: ${names}`);
      }
      await this.click(it.slot, opts);
      return it;
    },
    async closeWindow() { if (bot.currentWindow) bot.closeWindow(bot.currentWindow); await sleep(150); },
    pos() { return bot.entity ? bot.entity.position.clone() : null; },
    /** Sidebar scoreboard lines (plain text), top to bottom. */
    sidebar() {
      const sb = bot.scoreboard && bot.scoreboard.sidebar;
      if (!sb) return [];
      return sb.items.slice().sort((a, b) => b.value - a.value).map((i) => plain(i.displayName ? i.displayName.toString() : i.name));
    },
    bossBarTitles() { return [...this.bossBars.values()].map((b) => plain(b.title ? b.title.toString() : '')); },
    async quit() { bot.quit(); await sleep(300); },
  };

  bot.on('message', (jsonMsg, position) => {
    wrapper.messages.push({ text: plain(jsonMsg.toString()), position, at: Date.now() });
  });
  bot.on('windowOpen', (w) => wrapper.windows.push(w));
  bot.on('bossBarCreated', (b) => wrapper.bossBars.set(b.entityUUID, b));
  bot.on('bossBarUpdated', (b) => wrapper.bossBars.set(b.entityUUID, b));
  bot.on('bossBarDeleted', (b) => wrapper.bossBars.delete(b.entityUUID));
  bot.on('kicked', (reason) => wrapper.messages.push({ text: 'KICKED: ' + plain(JSON.stringify(reason)), position: 'kick', at: Date.now() }));
  bot.on('error', (err) => wrapper.messages.push({ text: 'ERROR: ' + err.message, position: 'error', at: Date.now() }));

  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`${username}: spawn timeout`)), 30_000);
    bot.once('spawn', () => { clearTimeout(t); resolve(); });
    bot.once('kicked', (r) => { clearTimeout(t); reject(new Error(`${username}: kicked during login: ${JSON.stringify(r)}`)); });
    bot.once('error', (e) => { clearTimeout(t); reject(e); });
  });
  await sleep(1500); // let join commands / spawn plugins settle
  return wrapper;
}

function windowTitle(w) {
  const t = w.title;
  if (!t) return '';
  try { return componentText(typeof t === 'string' ? JSON.parse(t) : t); } catch { return String(t); }
}

function itemName(it) {
  // 1.20.5+ item components: mineflayer exposes custom_name/item_name as JSON text components.
  const raw = it.customName ?? it.displayName;
  if (raw == null) return it.displayName || it.name;
  try { return componentText(typeof raw === 'string' ? JSON.parse(raw) : raw); } catch { return String(raw); }
}

function itemLore(it) {
  const raw = it.customLore;
  if (!raw) return [];
  const arr = Array.isArray(raw) ? raw : [raw];
  return arr.map((l) => { try { return componentText(typeof l === 'string' ? JSON.parse(l) : l); } catch { return String(l); } });
}

/** Flattens a chat component (string | {text, extra} | array | NBT-ish) to plain text. */
function componentText(c) {
  if (c == null) return '';
  if (typeof c === 'string') return c;
  if (Array.isArray(c)) return c.map(componentText).join('');
  if (typeof c === 'object') {
    // NBT-style {type:'compound', value:{...}} from prismarine-nbt
    if (c.type && c.value !== undefined && typeof c.value === 'object' && !('text' in c)) return componentText(nbtToPlain(c));
    let s = '';
    if (c.text != null) s += componentText(c.text);
    if (c.translate && !c.text) s += c.translate;
    if (c.extra) s += componentText(c.extra);
    return s;
  }
  return String(c);
}

function nbtToPlain(n) {
  if (n == null) return n;
  if (Array.isArray(n)) return n.map(nbtToPlain);
  if (typeof n !== 'object') return n;
  if ('type' in n && 'value' in n) {
    const v = n.value;
    if (n.type === 'compound') { const o = {}; for (const k of Object.keys(v)) o[k] = nbtToPlain(v[k]); return o; }
    if (n.type === 'list') return (v.value || []).map((x) => nbtToPlain({ type: v.type, value: x }));
    return v;
  }
  const o = {}; for (const k of Object.keys(n)) o[k] = nbtToPlain(n[k]); return o;
}

// ── Server console / log ───────────────────────────────────────────────────────

const server = {
  /** Sends a console command through the FIFO (no-op with a warning if not configured). */
  exec(command) {
    if (!cfg.consoleFifo) { console.warn('  (no MC_CONSOLE configured; skipped console command: ' + command + ')'); return; }
    fs.writeFileSync(cfg.consoleFifo, command + '\n');
  },
  logSize() { return cfg.serverLog && fs.existsSync(cfg.serverLog) ? fs.statSync(cfg.serverLog).size : 0; },
  /** Log text appended since `offset` (a prior logSize()). */
  logSince(offset) {
    if (!cfg.serverLog) return '';
    const fd = fs.openSync(cfg.serverLog, 'r');
    try {
      const size = fs.fstatSync(fd).size;
      if (size <= offset) return '';
      const buf = Buffer.alloc(size - offset);
      fs.readSync(fd, buf, 0, buf.length, offset);
      return buf.toString('utf8');
    } finally { fs.closeSync(fd); }
  },
  async waitForLog(re, offset, { timeout = 8000 } = {}) {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      const m = this.logSince(offset).match(re);
      if (m) return m;
      await sleep(150);
    }
    throw new Error(`server log: no line matching ${re} within ${timeout}ms`);
  },
};

// ── Tiny test runner ───────────────────────────────────────────────────────────

const results = [];
async function test(name, fn) {
  const started = Date.now();
  process.stdout.write(`▶ ${name} … `);
  try {
    await fn();
    results.push({ name, ok: true, ms: Date.now() - started });
    console.log(`ok (${Date.now() - started}ms)`);
  } catch (err) {
    results.push({ name, ok: false, ms: Date.now() - started, error: err.message });
    console.log(`FAIL\n    ${err.message.split('\n').join('\n    ')}`);
  }
}

function assert(cond, msg) { if (!cond) throw new Error(msg || 'assertion failed'); }

function summary(file) {
  const ok = results.filter((r) => r.ok).length;
  console.log(`\n${ok}/${results.length} passed`);
  if (file) {
    fs.mkdirSync(require('path').dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify(results, null, 2));
  }
  return results.every((r) => r.ok);
}

module.exports = { cfg, plain, sleep, spawnBot, server, test, assert, summary, windowTitle, itemName, itemLore };
