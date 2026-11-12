// Shared helpers for the web editor tutorial clips: browser and video
// setup, a visible cursor, human-paced input, fake server controls and the
// final trim and encode.
//
// Settings come from the environment (record.cjs maps its flags onto these):
//   ANIMUS_WEB_URL     editor site                 default http://127.0.0.1:8191
//   ANIMUS_FAKE_URL    fake server                 default http://127.0.0.1:9991
//   ANIMUS_PAIR_LINK   pairing link or code for clips that need a real server
//   ANIMUS_DEMO_OUT    output folder               default <repo>/build/demo/editor
//   PLAYWRIGHT_MODULE  playwright module path      default playwright
//   ANIMUS_FFMPEG      ffmpeg binary               default Playwright's bundled ffmpeg, then ffmpeg on PATH
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const REPO = path.resolve(__dirname, '..', '..', '..');
const SITE = (process.env.ANIMUS_WEB_URL || 'http://127.0.0.1:8191').replace(/\/+$/, '');
const FAKE = (process.env.ANIMUS_FAKE_URL || 'http://127.0.0.1:9991').replace(/\/+$/, '');
const OUT = path.resolve(process.env.ANIMUS_DEMO_OUT || path.join(REPO, 'build', 'demo', 'editor'));
const W = 1440, H = 900;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Playwright's bundled ffmpeg (it has libvpx), else ffmpeg on PATH. */
function findFfmpeg() {
  if (process.env.ANIMUS_FFMPEG) return process.env.ANIMUS_FFMPEG;
  const cache = process.env.PLAYWRIGHT_BROWSERS_PATH || path.join(os.homedir(), process.platform === 'darwin' ? 'Library/Caches/ms-playwright' : '.cache/ms-playwright');
  const bin = {darwin: 'ffmpeg-mac', win32: 'ffmpeg-win64.exe'}[process.platform] || 'ffmpeg-linux';
  try {
    const dirs = fs.readdirSync(cache).filter((d) => d.startsWith('ffmpeg-')).sort().reverse();
    for (const d of dirs) if (fs.existsSync(path.join(cache, d, bin))) return path.join(cache, d, bin);
  } catch (e) { /* no Playwright cache */ }
  return 'ffmpeg';
}
const FFMPEG = findFfmpeg();

/** Calls a /__fake/ test control on the fake server (animus-web/tool/fake_server). */
async function control(route, body) {
  const r = await fetch(`${FAKE}/__fake/${route}`, {method: body === undefined && route === 'state' ? 'GET' : 'POST', headers: {'Content-Type': 'application/json'}, body: body === undefined ? undefined : JSON.stringify(body)});
  if (!r.ok) throw new Error(route + ' ' + r.status);
  return r.json();
}

/** Resets the fake server to its loaded data. */
async function resetFake() {
  await control('reset', {});
  await control('restore', {});
}

/** The pairing code from ANIMUS_PAIR_LINK (a full link or a bare code). */
function pairCodeFromEnv() {
  const link = (process.env.ANIMUS_PAIR_LINK || '').trim();
  if (!link) throw new Error('This clip needs a real server: set ANIMUS_PAIR_LINK or pass --pair=<link>');
  const at = link.indexOf('#/pair/');
  return at < 0 ? link : link.slice(at + 7);
}

const CURSOR = () => {
  const dot = document.createElement('div');
  dot.id = '__cursor';
  dot.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;width:22px;height:22px;border-radius:50%;background:rgba(255,200,40,.55);border:2px solid #fff;box-shadow:0 0 0 1px #000,0 2px 6px rgba(0,0,0,.5);transform:translate(-50%,-50%);transition:transform .08s';
  // The cursor starts where the recorder's mouse starts (the middle) and is never parked off screen.
  const last = window.__cursorAt || {x: innerWidth / 2, y: innerHeight / 2};
  dot.style.left = last.x + 'px'; dot.style.top = last.y + 'px';
  const add = () => { if (document.documentElement && !document.getElementById('__cursor')) document.documentElement.appendChild(dot); };
  add();
  document.addEventListener('DOMContentLoaded', add);
  window.addEventListener('mousemove', (e) => { add(); dot.style.left = e.clientX + 'px'; dot.style.top = e.clientY + 'px'; }, true);
  window.addEventListener('mousedown', () => { dot.style.transform = 'translate(-50%,-50%) scale(.7)'; }, true);
  window.addEventListener('mouseup', () => { dot.style.transform = 'translate(-50%,-50%)'; }, true);
};

/** Shows [text] as the closing caption, bottom left, like the in-game clips. */
const CAPTION = (text) => {
  let box = document.getElementById('__caption');
  if (!box) {
    box = document.createElement('div');
    box.id = '__caption';
    box.style.cssText = 'position:fixed;left:32px;bottom:32px;z-index:2147483646;pointer-events:none;max-width:calc(100vw - 64px);padding:10px 16px;background:rgba(16,16,16,.82);color:#fff;font:600 22px/1.3 system-ui,-apple-system,Segoe UI,sans-serif;border-radius:6px';
    document.documentElement.appendChild(box);
  }
  box.textContent = text;
};

/** A small seeded random source, so re-records move the same way. */
function seeded(text) {
  let a = 2166136261;
  for (let i = 0; i < text.length; i++) a = Math.imul(a ^ text.charCodeAt(i), 16777619);
  return () => {
    a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const easeInOut = (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);
const bezier = (p0, p1, p2, p3, t) => {
  const u = 1 - t;
  return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3;
};

/** Fitts-like glide time for a distance in pixels, clamped to 180-900 ms. */
function glideMillis(distance) {
  if (distance < 0.5) return 0;
  return Math.max(180, Math.min(900, Math.round(110 + 150 * Math.log2(1 + distance / 12))));
}

/**
 * The points of one glide from [from] to [to]: a cubic Bezier bent a little to one side,
 * eased in and out; moves of 250 px or more pass the target by up to 6 px and settle back.
 */
function glidePath(from, to, rand, frameMs = 16) {
  const dx = to.x - from.x, dy = to.y - from.y;
  const dist = Math.hypot(dx, dy);
  const ms = glideMillis(dist);
  if (!ms) return [];
  const ux = dx / dist, uy = dy / dist;
  const side = rand() < 0.5 ? -1 : 1;
  const bend = Math.min(40, dist * (0.04 + rand() * 0.06)) * side;
  const tail = 0.5 + rand() * 0.4;
  const over = dist >= 250 ? Math.min(6, dist * 0.02) * (0.7 + rand() * 0.3) : 0;
  const end = {x: to.x + ux * over, y: to.y + uy * over};
  const c1 = {x: from.x + dx * 0.3 - uy * bend, y: from.y + dy * 0.3 + ux * bend};
  const c2 = {x: from.x + dx * 0.7 - uy * bend * tail, y: from.y + dy * 0.7 + ux * bend * tail};
  const settle = over ? 0.18 : 0;
  const frames = Math.max(2, Math.round(ms / frameMs));
  const pts = [];
  for (let i = 1; i <= frames; i++) {
    const p = i / frames;
    if (p <= 1 - settle) {
      const t = easeInOut(p / (1 - settle));
      pts.push({x: bezier(from.x, c1.x, c2.x, end.x, t), y: bezier(from.y, c1.y, c2.y, end.y, t)});
    } else {
      const t = easeInOut((p - (1 - settle)) / settle);
      pts.push({x: end.x + (to.x - end.x) * t, y: end.y + (to.y - end.y) * t});
    }
  }
  return pts;
}

/**
 * A recording session. [setup] is a page for unfilmed preparation (pairing);
 * start() closes it and opens the page whose video becomes the clip.
 */
async function session({id, theme = 'dark', w = W, h = H}) {
  const browser = await chromium.launch();
  const dir = path.join(OUT, 'raw', id);
  fs.rmSync(dir, {recursive: true, force: true});
  fs.mkdirSync(dir, {recursive: true});
  const context = await browser.newContext({viewport: {width: w, height: h}, recordVideo: {dir, size: {width: w, height: h}}});
  await context.addInitScript((mode) => { try { localStorage.setItem('animus.theme', mode); } catch (e) {} }, theme);
  await context.addInitScript(CURSOR);
  const rand = seeded(id);
  const errors = [];
  const hook = (page) => {
    page.setDefaultTimeout(15000);
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
    page.on('pageerror', (e) => errors.push(String(e)));
  };
  const setup = await context.newPage();
  hook(setup);
  const film = {
    id, browser, context, setup, errors, dir, rand,
    pos: {x: w / 2, y: h / 2},
    /**
     * Marks where the clip starts; finish() trims everything before it. Unfilmed setup may have moved
     * the real mouse (a toast's Dismiss, a locator click), so the mouse goes back to where the cursor
     * glides from; the clip starts just after that move.
     */
    mark() {
      film.tm = Date.now();
      if (film.page) film.page.mouse.move(film.pos.x, film.pos.y).catch(() => {});
    },
    async start(url) {
      await setup.close();
      const page = await context.newPage();
      film.t0 = Date.now();
      hook(page);
      if (url) await page.goto(url);
      await page.mouse.move(film.pos.x, film.pos.y);
      film.page = page;
      return page;
    },
  };
  return film;
}

/** Pairs [page] with [code] (default: a fresh code from the fake server). */
async function pair(page, {code, remember = true} = {}) {
  if (!code) code = (await control('pair?paths=both', {})).code;
  await page.goto(`${SITE}/#/pair/${code}`);
  await page.getByTestId('pair-fingerprint').filter({hasText: 'sha256:'}).waitFor();
  // Remember this browser is on by default; flip it only when it differs from [remember].
  const sw = page.getByRole('switch', {name: 'Remember this browser'});
  if ((await sw.getAttribute('aria-checked')) !== String(remember)) await sw.click();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  return code;
}

/** Opens every document the fake server lists, with the sidebar's Open all button on the server page. */
async function openAll(page) {
  if (!/#\/server\//.test(page.url())) await page.getByRole('link', {name: 'Animus fake server'}).first().click();
  await page.getByRole('complementary', {name: 'Documents'}).getByRole('button', {name: /^Open all \d+/}).click();
  await page.getByText(/Opened \d+ documents/).first().waitFor();
}

/** Closes the first toast if one is showing; only buttons inside the Notifications region, never document controls. */
async function dismiss(page) {
  await page.getByRole('region', {name: 'Notifications'}).getByRole('button', {name: 'Dismiss'}).first().click({timeout: 3000}).catch(() => {});
}

/** A collapsible NPC section; NpcSection exposes its id only as data-section. */
const section = (page, id) => page.locator(`details[data-section="${id}"]`);

/** A dropdown option by value; data-value is the select component's contract (see animus-web/test/support/select.cjs). */
const option = (page, value) => page.locator(`[role="option"][data-value="${value}"]`);

/** The open listbox of a dropdown trigger. */
async function listboxOf(trigger) {
  const id = await trigger.first().getAttribute('aria-controls');
  return trigger.first().page().locator(`[id="${id}"]`);
}

/** Clicks rest on their target this long before pressing (the in-game default is 400 ms). */
const CLICK_REST_MS = 400;

/** Human-paced input on the film page, with a cursor that glides on eased curves and never parks. */
function human(film) {
  const h = {
    async pause(ms = 600) { await sleep(ms); },
    /** Holds still on a visible result; 1500 to 2500 ms like the in-game clips. */
    async result(ms = 2000) { await sleep(Math.max(1500, Math.min(2500, ms))); },
    /** Glides the cursor from where it is to (x, y). */
    async glide(x, y) {
      const page = film.page;
      for (const p of glidePath(film.pos, {x, y}, film.rand)) {
        await page.mouse.move(p.x, p.y);
        await sleep(16);
      }
      await page.mouse.move(x, y);
      film.pos = {x, y};
    },
    /** Scrolls with the wheel until [loc] sits in the middle band. */
    async reveal(loc) {
      const page = film.page;
      await loc.first().waitFor({state: 'attached'});
      let last = null;
      for (let i = 0; i < 60; i++) {
        const b = await loc.first().boundingBox();
        if (!b) break;
        if (b.x < 320) { await loc.first().scrollIntoViewIfNeeded(); return; }
        const mid = b.y + b.height / 2;
        if (mid > 170 && mid < H - 120) return;
        // Already fully visible and the wheel has stopped moving it (top bar, tabs, a page that cannot scroll): stop.
        if (i > 0 && b.y >= 0 && b.y + b.height <= H && last !== null && Math.abs(last - b.y) < 1) return;
        last = b.y;
        const dy = mid <= 170 ? -(170 - mid + 100) : (mid - (H - 120) + 150);
        const step = Math.max(-220, Math.min(220, dy));
        if (film.pos.x < 400) await h.glide(700, film.pos.y);
        await page.mouse.wheel(0, step);
        await sleep(110);
      }
      await sleep(150);
    },
    async moveTo(loc, {ox = 0.5, oy = 0.5, noReveal = false} = {}) {
      if (noReveal) await loc.first().waitFor(); else await h.reveal(loc);
      const b = await loc.first().boundingBox();
      if (!b) throw new Error('no box for ' + loc);
      await h.glide(b.x + b.width * ox, b.y + b.height * oy);
    },
    /** Glides to [loc], rests on it, clicks. `rest` overrides the rest before the press. */
    async click(loc, opts = {}) {
      await h.moveTo(loc, opts);
      await sleep(opts.rest ?? CLICK_REST_MS);
      await film.page.mouse.down(); await sleep(60); await film.page.mouse.up();
      await sleep(opts.after ?? 600);
    },
    async type(loc, text, {delay = 50, clear = false, after = 500} = {}) {
      await h.click(loc, {after: 150});
      if (clear) { await film.page.keyboard.press('ControlOrMeta+a'); await film.page.keyboard.press('Backspace'); }
      await loc.first().pressSequentially(text, {delay: slow(delay)});
      await sleep(Math.max(after, TYPED_HOLD_MS));
    },
    async key(k, after = 500) { await film.page.keyboard.press(k); await sleep(after); },
    async hover(loc, after = 900) { await h.moveTo(loc); await sleep(after); },
    /** The closing caption: one line naming what changed, held for [ms]. */
    async caption(text, ms = 3000) {
      await film.page.evaluate(CAPTION, text);
      await sleep(ms);
    },
  };
  return h;
}

/** Resets the fake server, pairs a remembered browser and opens every document, all before filming. */
async function pairedSession(id) {
  await resetFake();
  const film = await session({id});
  await pair(film.setup);
  const page = await film.start(`${SITE}/#/`);
  await openAll(page);
  // Toasts close with a button named Dismiss; clear them so they do not sit in the clip.
  const toasts = page.getByRole('region', {name: 'Notifications'}).getByRole('button', {name: 'Dismiss'});
  for (let i = 0; i < 5 && await toasts.count(); i++) await toasts.first().click({timeout: 3000}).catch(() => {});
  return {film, page, h: human(film)};
}

/** Duration in seconds of a video file, read from ffmpeg's banner. */
function duration(file) {
  try { execFileSync(FFMPEG, ['-hide_banner', '-i', file], {stdio: 'pipe'}); } catch (e) {
    const m = /Duration: (\d+):(\d+):([\d.]+)/.exec(String(e.stderr));
    if (m) return +m[1] * 3600 + +m[2] * 60 + +m[3];
  }
  return 0;
}

/** Shows the closing caption for 3 s, closes the session and writes <OUT>/<id>-editor.webm, trimmed to run from mark() to the end of the caption. */
async function finish(film, {closing, maxLen = null} = {}) {
  if (!closing) throw new Error(`${film.id}: every clip ends on a closing caption; pass finish(film, {closing})`);
  await human(film).caption(closing);
  const video = film.page.video();
  // The page's video starts when the page opens, so mark() is measured from there; the end of the
  // raw video also holds the time the context takes to close, which an end-anchored trim would count.
  const lead = film.tm ? (film.tm - film.t0) / 1000 + 0.15 : 0;
  const shown = (Date.now() - film.t0) / 1000 - lead + 0.1;
  await film.context.close();
  const raw = await video.path();
  await film.browser.close();
  const dur = duration(raw);
  const trimStart = Math.min(lead, Math.max(0, dur - 1));
  const out = path.join(OUT, `${film.id}-editor.webm`);
  const args = ['-y', '-v', 'error', '-ss', String(trimStart), '-i', raw];
  const secs = Math.min(shown, dur - trimStart, maxLen || Infinity);
  args.push('-t', String(secs));
  args.push('-c:v', 'libvpx-vp9', '-b:v', '0', '-crf', '34', '-an', out);
  execFileSync(FFMPEG, args);
  return {out, secs, errors: film.errors};
}

/**
 * Quest clips: pairs with the real server from ANIMUS_PAIR_LINK, opens the
 * two NPCs the quest refers to (the town's baker and a live/qhealer made
 * from the console), and starts a new quest draft. Nothing here
 * is filmed; the clip calls film.mark() after.
 */
async function openQuestDraft(film, page, npcs = ['town/baker', 'live/qhealer']) {
  await pair(page, {code: pairCodeFromEnv(), remember: false});
  // The connection pill and live log have no accessible name; they are found by class.
  await page.locator('.animus-live .animus-pill[data-phase="online"]').waitFor({timeout: 30000});
  await page.getByText(/documents on the server/).waitFor();
  for (const npc of npcs) await page.getByRole('checkbox', {name: npc, exact: true}).check();
  await page.getByRole('button', {name: new RegExp(`^Open ${npcs.length}`)}).click();
  await page.locator('.animus-live-log li', {hasText: `Opened ${npcs.length} documents`}).first().waitFor({state: 'attached'});
  const ws = await page.evaluate(() => location.hash.split('/').slice(0, 3).join('/'));
  await page.goto(`${SITE}/${ws}/new/quest`);
  await page.getByLabel('Title', {exact: true}).waitFor();
  // The pairing and open toasts would sit over the form at the start of the clip.
  const toasts = page.getByRole('region', {name: 'Notifications'}).getByRole('button', {name: 'Dismiss'});
  while (await toasts.count()) await toasts.first().click();
  await sleep(800);
}

/** Typing runs 25% slower than the raw delays in the clip scripts. */
const TYPE_SLOW = 1.25;
/** Pause after typing finishes, so the text is readable before the next action. */
const TYPED_HOLD_MS = 1000;
function slow(delay) { return Math.round(delay * TYPE_SLOW); }

/**
 * human(film) for the GUI editors, whose side panels scroll on their own: click, type and hover
 * glide into the column that holds the target and wheel it until the target sits inside that
 * column. Targets with noReveal, and targets outside such a column, use h.reveal() as usual.
 */
function panelHuman(film) {
  const h = human(film);
  const column = (loc) => loc.first().evaluate((el) => {
    for (let p = el.parentElement; p; p = p.parentElement) {
      if (/(auto|scroll)/.test(getComputedStyle(p).overflowY) && p.scrollHeight > p.clientHeight + 1) {
        const b = p.getBoundingClientRect();
        return {x: b.left, y: b.top, w: b.width, h: b.height};
      }
    }
    return null;
  });
  /** True when [loc] is in a scrolling column, after wheeling it into that column's view. */
  const enter = async (loc) => {
    await loc.first().waitFor({state: 'attached'});
    let r = await column(loc);
    if (!r) return false;
    const band = () => [Math.max(r.y + 60, 180), Math.min(r.y + r.h - 60, H - 130)];
    let [top, bottom] = band();
    if (!(film.pos.x > r.x && film.pos.x < r.x + r.w && film.pos.y > top && film.pos.y < bottom)) {
      await h.glide(r.x + r.w * 0.6, Math.max(top, Math.min(bottom, film.pos.y)));
    }
    let last = null;
    for (let i = 0; i < 60; i++) {
      const b = await loc.first().boundingBox();
      r = await column(loc);
      if (!b || !r || (last !== null && Math.abs(b.y - last) < 1)) break; // in band, or the column cannot scroll further
      last = b.y;
      [top, bottom] = band();
      const mid = b.y + b.height / 2;
      if (mid > top && mid < bottom) break;
      const dy = mid <= top ? mid - top - 80 : mid - bottom + 120;
      await film.page.mouse.wheel(0, Math.max(-220, Math.min(220, dy)));
      await sleep(110);
    }
    await sleep(150);
    return true;
  };
  return {
    ...h,
    async click(loc, opts = {}) { return h.click(loc, {...opts, noReveal: opts.noReveal || await enter(loc)}); },
    async type(loc, text, opts = {}) { if (await enter(loc)) await h.moveTo(loc, {noReveal: true}); return h.type(loc, text, opts); },
    async hover(loc, after) { return (await enter(loc)) ? h.moveTo(loc, {noReveal: true}).then(() => sleep(after ?? 900)) : h.hover(loc, after); },
  };
}

module.exports = {
  panelHuman, slow, TYPED_HOLD_MS, CLICK_REST_MS, glidePath, glideMillis, seeded,
  REPO, SITE, FAKE, OUT, FFMPEG, W, H, sleep,
  control, resetFake, session, pair, openAll, dismiss, section, option, listboxOf, human, pairedSession, finish, openQuestDraft,
};
