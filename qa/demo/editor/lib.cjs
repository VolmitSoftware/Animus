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
// Clips are 1920x1080 like the in-game ones (qa/demo/clip_check.py).
const W = 1920, H = 1080;
/** The nav column's width; targets left of it sit in the nav, which does not scroll with the page. */
const NAV_W = 240;
/** The top bar's and the save bar's height; the page body scrolls between them. */
const BAR_H = 48;
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

/** Shows [text] as the closing caption, bottom left like the in-game clips, above the save bar. */
const CAPTION = (text) => {
  let box = document.getElementById('__caption');
  if (!box) {
    box = document.createElement('div');
    box.id = '__caption';
    box.style.cssText = 'position:fixed;left:32px;bottom:80px;z-index:2147483646;pointer-events:none;max-width:calc(100vw - 64px);padding:10px 16px;background:rgba(16,16,16,.82);color:#fff;font:600 22px/1.3 system-ui,-apple-system,Segoe UI,sans-serif;border-radius:6px';
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

/** The editor's own frame helpers from its browser checks (Search, Mode, tabs, the start page). */
const shell = require(path.join(REPO, 'animus-web', 'test', 'support', 'shell.cjs'));

/**
 * Pairs [page] with [code] (default: a fresh code from the fake server) and
 * waits for the server's Home, online. [remember] is the pairing card's Stay paired.
 */
async function pair(page, {code, remember = true} = {}) {
  if (!code) code = (await control('pair?paths=both', {})).code;
  await page.goto(`${SITE}/#/pair/${code}`);
  const sw = page.getByRole('switch', {name: 'Stay paired'});
  await sw.waitFor();
  if ((await sw.getAttribute('aria-checked')) !== String(remember)) await sw.click();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => /^#\/(server|workspace)\//.test(location.hash));
  await shell.waitOnline(page, 30000);
  return code;
}

/**
 * The open workspace's address, such as #/workspace/server-a18112b0b7b4225f,
 * read from the nav's Home link (a server's Home is also reachable as #/server/<id>).
 */
async function workspaceHash(page) {
  const home = page.getByRole('complementary').getByRole('navigation').getByRole('link', {name: 'Home', exact: true});
  await home.waitFor();
  return home.getAttribute('href');
}

/** Closes every toast that is showing, so none sits in the clip. */
async function clearToasts(page) {
  const toasts = page.getByRole('region', {name: 'Notifications'}).getByRole('button', {name: 'Dismiss'});
  for (let i = 0; i < 8 && await toasts.count(); i++) await toasts.first().click({timeout: 3000}).catch(() => {});
}

/**
 * A dropdown option by value, in whichever list is open; data-arcane-value is
 * the select component's contract (see animus-web/test/support/select.cjs).
 */
const option = (page, value) => page.locator(`[role="option"][data-arcane-value="${value.replace(/"/g, '\\"')}"]:visible`);

/** A dropdown option by its label (data-label), in whichever list is open. */
const optionLabelled = (page, label) => page.locator(`[role="option"][data-label="${label.replace(/"/g, '\\"')}"]:visible`);

/** The list of a dropdown trigger, open or not. */
async function listboxOf(trigger) {
  const id = await trigger.first().getAttribute('aria-controls');
  return trigger.first().page().locator(`[id="${id}"]`);
}

/**
 * Makes a workspace from the start page's Try the examples (unfilmed) and
 * returns its address. The film page reopens it from browser storage.
 */
async function examplesWorkspace(page) {
  await page.goto(`${SITE}/#/`);
  await shell.startAction(page, 'Open examples');
  await shell.workspaceOpen(page);
  const ws = await workspaceHash(page);
  await page.waitForTimeout(1200);
  return ws;
}

/** Makes a blank workspace named [name] (unfilmed) and returns its address. */
async function blankWorkspace(page, name) {
  await page.goto(`${SITE}/#/`);
  await shell.blankWorkspace(page, name);
  const ws = await workspaceHash(page);
  await page.waitForTimeout(1200);
  return ws;
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
        if (b.x < NAV_W) { await loc.first().scrollIntoViewIfNeeded(); return; }
        const mid = b.y + b.height / 2;
        // A target that fits between the top bar and the save bar must show whole, not just its middle.
        const fits = b.height <= H - 2 * BAR_H - 140;
        const under = fits && b.y < BAR_H + 8, over = fits && b.y + b.height > H - BAR_H - 8;
        if (mid > 170 && mid < H - 120 && !under && !over) return;
        // Already fully visible and the wheel has stopped moving it (top bar, tabs, a page that cannot scroll): stop.
        if (i > 0 && b.y >= 0 && b.y + b.height <= H && last !== null && Math.abs(last - b.y) < 1) return;
        last = b.y;
        const dy = under ? b.y - BAR_H - 60 : over ? b.y + b.height - (H - BAR_H - 60) : mid <= 170 ? -(170 - mid + 100) : (mid - (H - 120) + 150);
        const step = Math.max(-220, Math.min(220, dy));
        // The wheel scrolls only the page body: come off the nav, the top bar and the save bar first.
        if (film.pos.x < NAV_W + 80 || film.pos.y < BAR_H + 40 || film.pos.y > H - BAR_H - 40) {
          await h.glide(Math.max(NAV_W + 500, film.pos.x), Math.max(BAR_H + 120, Math.min(H - BAR_H - 120, film.pos.y)));
        }
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
      // Typing over the selection replaces it; a field emptied first may put its default back before the typing lands.
      if (clear) await film.page.keyboard.press('ControlOrMeta+a');
      await loc.first().pressSequentially(text, {delay: slow(delay)});
      await sleep(Math.max(after, TYPED_HOLD_MS));
    },
    async key(k, after = 500) { await film.page.keyboard.press(k); await sleep(after); },
    async hover(loc, after = 900) { await h.moveTo(loc); await sleep(after); },
    /**
     * Opens the dropdown [trigger] and clicks the option whose value is [value]
     * ({label} picks by its text instead), resting on it first.
     */
    async pick(trigger, value, {after = 700} = {}) {
      await h.click(trigger, {after: 500});
      const opt = typeof value === 'string' ? option(film.page, value) : optionLabelled(film.page, value.label);
      await opt.first().waitFor();
      await opt.first().scrollIntoViewIfNeeded();
      await h.click(opt, {after, noReveal: true});
    },
    /** Opens a nav job (Home, NPCs, Shops, Movement...) by its label. */
    async job(label, {after = 900} = {}) {
      await h.click(film.page.getByRole('complementary').getByRole('navigation').getByRole('link', {name: new RegExp(`^${label}( \\d+)?$`)}), {after});
    },
    /** Switches the Mode in the top bar (simple or advanced). */
    async mode(mode, {after = 900} = {}) {
      const label = mode === 'advanced' ? 'Advanced' : 'Simple';
      await h.click(film.page.getByRole('banner').getByRole('group', {name: 'Mode'}).getByRole('button', {name: label, exact: true}), {after, noReveal: true});
    },
    /** The closing caption: one line naming what changed, held for [ms]. */
    async caption(text, ms = 3000) {
      await film.page.evaluate(CAPTION, text);
      await sleep(ms);
    },
  };
  return h;
}

/**
 * Pairs a remembered browser before filming: with a fresh fake server code, or
 * with [real] the server link from ANIMUS_PAIR_LINK. The film page opens on the
 * server's Home (or [path] under the workspace, such as /npcs), online and with
 * no toasts. The catalog lists every document, so nothing needs opening first.
 */
async function pairedSession(id, {path: sub = '', mode = 'simple', real = false} = {}) {
  if (!real) await resetFake();
  const film = await session({id});
  if (mode === 'advanced') await film.context.addInitScript(() => { try { localStorage.setItem('animus.mode', 'advanced'); } catch (e) {} });
  await pair(film.setup, real ? {code: pairCodeFromEnv()} : {});
  const ws = await workspaceHash(film.setup);
  const page = await film.start(`${SITE}/${ws}${sub}`);
  if (real) page.setDefaultTimeout(30000);
  await shell.waitOnline(page, 30000);
  await page.getByRole('main').first().waitFor();
  await clearToasts(page);
  return {film, page, h: human(film), ws};
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
  args.push('-c:v', 'libvpx-vp9', '-b:v', '0', '-crf', '34', '-deadline', 'good', '-cpu-used', '2', '-row-mt', '1', '-an', out);
  execFileSync(FFMPEG, args);
  return {out, secs, errors: film.errors};
}

/**
 * Quest clips (11, 12): a real server from ANIMUS_PAIR_LINK in Advanced, filmed from
 * its Quests list, then New quest is filled on camera: the title and the giver
 * (the town's baker). Returns once the new quest is open.
 */
async function questSession(id) {
  const {film, page, h} = await pairedSession(id, {real: true, mode: 'advanced', path: '/list/quest'});
  await page.getByRole('button', {name: 'New quest'}).waitFor();
  await page.mouse.move(film.pos.x, film.pos.y);
  await sleep(1200);
  film.mark();
  await h.pause(300);
  await h.click(page.getByRole('button', {name: 'New quest'}), {after: 700});
  const dialog = page.getByRole('dialog', {name: 'New quest'});
  await h.type(dialog.getByRole('textbox', {name: 'Title'}), '&6Bread for the baker', {delay: 40, after: 250});
  await h.pick(dialog.getByRole('combobox', {name: /Giver/}), 'baker', {after: 300});
  await h.click(dialog.getByRole('button', {name: 'Create', exact: true}), {after: 900});
  await page.waitForFunction(() => location.hash.includes('/quest/'));
  await clearToasts(page);
  return {film, page, h};
}

/** Typing runs 25% slower than the raw delays in the clip scripts. */
const TYPE_SLOW = 1.25;
/** Pause after typing finishes, so the text is readable before the next action. */
const TYPED_HOLD_MS = 1000;
function slow(delay) { return Math.round(delay * TYPE_SLOW); }

module.exports = {
  slow, TYPED_HOLD_MS, CLICK_REST_MS, glidePath, glideMillis, seeded,
  REPO, SITE, FAKE, OUT, FFMPEG, W, H, sleep, shell,
  control, resetFake, pairCodeFromEnv, session, pair, workspaceHash, clearToasts, option, optionLabelled, listboxOf,
  examplesWorkspace, blankWorkspace, human, pairedSession, finish, questSession,
};
