// The NPC stage in the scrolling page body, under the top bar and the
// toasts. Pairs with the fake server and opens its first NPC (the preview
// sits above the form at 960 px and behind the Preview button at 390 px).
// A name typed while the stage is scrolled out of view still shows the
// ground once the stage scrolls half back under the top bar. A nameplate
// scrolled under the top bar is hidden by it. A toast ("Nothing to apply."
// from the save shortcut) over part of the stage leaves the rest of the
// picture as it is. 960 x 460 px dark and 390 x 560 px light at device
// scale 2, no console errors.
//
// Pictures come from full viewport screenshots cut in the page: a clipped
// screenshot composites the page again and hides what the screen shows.
//
//   python3 tool/serve_site.py --port <port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/stage_scroll_browser.cjs
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const {showPreview} = require('./support/stage.cjs');

const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}
const modKey = process.platform === 'darwin' ? 'Meta' : 'Control';

/** RGB bytes of [box] (CSS px) from a full viewport screenshot, once two in a row agree. */
async function pixels(page, box) {
  let png = await page.screenshot();
  for (let tries = 0; tries < 4; tries++) {
    await page.waitForTimeout(100);
    const next = await page.screenshot();
    if (next.equals(png)) break;
    png = next;
  }
  return page.evaluate(async ([base64, area]) => {
    const image = new Image();
    image.src = `data:image/png;base64,${base64}`;
    await image.decode();
    const canvas = document.createElement('canvas');
    canvas.width = image.naturalWidth;
    canvas.height = image.naturalHeight;
    const context = canvas.getContext('2d');
    context.drawImage(image, 0, 0);
    const scale = image.naturalWidth / innerWidth;
    const data = context.getImageData(Math.round(area.x * scale), Math.round(area.y * scale), Math.max(1, Math.round(area.width * scale)), Math.max(1, Math.round(area.height * scale))).data;
    const out = [];
    for (let i = 0; i < data.length; i += 4) out.push(data[i], data[i + 1], data[i + 2]);
    return out;
  }, [png.toString('base64'), box]);
}

/**
 * Share of grey sky pixels in the stage rows from 55 to 85 percent of its
 * height that are on screen below the top bar; the default ground fills
 * those rows. Null when none of them is on screen.
 */
async function skyShare(page) {
  const box = await page.locator('.animus-mc-stage').evaluate(el => {
    const r = el.getBoundingClientRect();
    const bar = document.querySelector('.ui-topbar').getBoundingClientRect().bottom;
    const top = Math.max(r.top + r.height * 0.55, bar + 2);
    const bottom = Math.min(r.top + r.height * 0.85, innerHeight);
    return bottom - top < 8 ? null : {x: r.left + 2, y: top, width: r.width - 4, height: bottom - top};
  });
  if (box == null) return null;
  const rgb = await pixels(page, box);
  let grey = 0;
  for (let i = 0; i < rgb.length; i += 3) {
    if (Math.max(rgb[i], rgb[i + 1], rgb[i + 2]) - Math.min(rgb[i], rgb[i + 1], rgb[i + 2]) < 14) grey++;
  }
  return grey / (rgb.length / 3);
}

/**
 * Waits until the stage has nothing left to build and draws no new frame
 * for 600 ms (terrain and skins can still arrive on a busy machine).
 */
async function stageQuiet(page) {
  await page.waitForFunction(() => {
    const host = document.querySelector('.animus-mc-gl-host');
    if (!host || host.getAttribute('data-unbuilt') !== '0') return false;
    const frames = host.getAttribute('data-frames');
    const now = performance.now();
    if (window.__quietFrames !== frames) { window.__quietFrames = frames; window.__quietSince = now; }
    return now - window.__quietSince >= 600;
  }, null, {polling: 100, timeout: 15000});
}

/** Scrolls the page body so the stage's top sits [offset] px from the viewport top. */
async function stageTopAt(page, offset) {
  await page.locator('.animus-mc-stage').evaluate((el, at) => {
    let scroller = el.parentElement;
    while (scroller && !(scroller.scrollHeight > scroller.clientHeight && /(auto|scroll)/.test(getComputedStyle(scroller).overflowY))) scroller = scroller.parentElement;
    (scroller || document.scrollingElement).scrollBy(0, el.getBoundingClientRect().top - at);
  }, offset);
  await page.waitForTimeout(400);
}

async function dismissToasts(page) {
  for (const close of await page.locator('.arcane-toast-close').all()) await close.click().catch(() => {});
  await page.waitForFunction(() => !document.querySelector('.arcane-toast'));
}

async function run(browser, {viewport, theme}) {
  const label = `${viewport.width}px ${theme}`;
  const context = await browser.newContext({viewport, colorScheme: theme, deviceScaleFactor: 2});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  // Skins the fake server names do not exist on the texture host, and its
  // rate-limit answers are retried after the hint: the browser's own 404 and
  // 429 lines are not faults.
  page.on('console', message => { if (message.type() === 'error' && !/status of (404|429)/.test(message.text())) errors.push(message.text()); });

  await control('reset', {});
  const npc = (await control('state')).documents.find(document => document.kind === 'npc');
  assert.ok(npc, 'the fake server has an NPC');
  const {code} = await control('pair?paths=both', {});
  await page.goto(`${url}/#/pair/${code}`);
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  const serverId = decodeURIComponent((await page.evaluate(() => location.hash)).split('/')[2]);
  await page.locator('[data-testid="context-state"]', {hasText: 'Online'}).waitFor({timeout: 20000});
  await page.goto(`${url}/#/workspace/${encodeURIComponent(`server-${serverId}`)}/npc/${encodeURIComponent(npc.id)}`);
  await showPreview(page);
  const name = page.getByLabel('Name', {exact: true}).first();
  await name.waitFor();
  await page.waitForFunction(() => Number(document.querySelector('.animus-mc-gl-host')?.dataset.frames) > 0 && document.querySelector('[data-text]'));
  await page.waitForTimeout(500);

  // A toast over part of the stage changes nothing outside the toast.
  const stage = page.locator('.animus-mc-stage');
  const height = await stage.evaluate(el => el.getBoundingClientRect().height);
  await stageTopAt(page, viewport.height - height * 0.75);
  // The toast closes by itself; a busy machine can outlast it while the
  // stage is read, so that read is taken again with a fresh toast.
  let toast = null;
  let above = null;
  let withToast = null;
  for (let tries = 0; tries < 3 && withToast == null; tries++) {
    await dismissToasts(page);
    await stageQuiet(page);
    await page.keyboard.press(`${modKey}+s`);
    await page.locator('.arcane-toast').waitFor();
    await page.waitForTimeout(400);
    toast = await page.evaluate(() => {
      const t = document.querySelector('.arcane-toast');
      return t ? t.getBoundingClientRect().toJSON() : null;
    });
    assert.ok(toast != null, `${label}: the "Nothing to apply." toast is up`);
    const stageBox = await stage.evaluate(el => el.getBoundingClientRect().toJSON());
    assert.ok(toast.top < stageBox.bottom && toast.bottom > stageBox.top && toast.left < stageBox.right, `${label}: the toast overlaps the stage`);
    above = {x: stageBox.left + 2, y: Math.max(stageBox.top + 2, 60), width: stageBox.width - 4, height: toast.top - 4 - Math.max(stageBox.top + 2, 60)};
    const read = await pixels(page, above);
    if (await page.locator('.arcane-toast').count() === 1) withToast = read;
  }
  assert.ok(withToast != null, `${label}: the toast stayed up while the stage was read`);
  await page.evaluate(() => document.querySelectorAll('.arcane-toaster').forEach(el => { el.style.visibility = 'hidden'; }));
  const withoutToast = await pixels(page, above);
  await page.evaluate(() => document.querySelectorAll('.arcane-toaster').forEach(el => { el.style.visibility = ''; }));
  let changed = 0;
  for (let i = 0; i < withToast.length; i++) if (Math.abs(withToast[i] - withoutToast[i]) > 24) changed++;
  assert.ok(changed / withToast.length < 0.01, `${label}: a toast leaves the stage above it alone (${changed} of ${withToast.length} channels changed)`);
  await dismissToasts(page);

  // A name typed with the stage off screen, then the stage scrolled half back.
  await stageTopAt(page, -height - 80);
  await name.click();
  await page.keyboard.press(`${modKey}+a`);
  await page.keyboard.type('Master Baker', {delay: 40});
  await page.waitForTimeout(300);
  await stageTopAt(page, -height * 0.45);
  const share = await skyShare(page);
  assert.ok(share != null && share < 0.5, `${label}: a stage partly under the top bar shows the ground (grey share ${share})`);

  // A nameplate scrolled under the top bar is covered by it.
  const plate = page.locator('[data-text]').first();
  const barBottom = await page.locator('.ui-topbar').evaluate(el => el.getBoundingClientRect().bottom);
  const plateTop = await plate.evaluate(el => el.getBoundingClientRect().top - el.closest('.animus-mc-stage').getBoundingClientRect().top);
  const plateHeight = await plate.evaluate(el => el.getBoundingClientRect().height);
  await stageTopAt(page, barBottom - plateTop - plateHeight / 2);
  const strip = await plate.evaluate(el => {
    const r = el.getBoundingClientRect();
    const bar = document.querySelector('.ui-topbar').getBoundingClientRect();
    const top = Math.max(r.top, bar.top);
    const left = Math.max(r.left, 0);
    return top + 4 >= bar.bottom ? null : {x: left, y: top, width: Math.min(r.right, innerWidth) - left, height: bar.bottom - 1 - top};
  });
  assert.ok(strip != null, `${label}: the nameplate reaches under the top bar`);
  const shown = await pixels(page, strip);
  await page.evaluate(() => document.querySelectorAll('.animus-mc-text-layer').forEach(el => { el.style.visibility = 'hidden'; }));
  const hidden = await pixels(page, strip);
  await page.evaluate(() => document.querySelectorAll('.animus-mc-text-layer').forEach(el => { el.style.visibility = ''; }));
  assert.deepEqual(shown, hidden, `${label}: the top bar hides a nameplate scrolled under it`);

  assert.deepEqual(errors, [], `${label}: console errors`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
  try {
    // Short windows, so the toast over the save bar reaches the stage at the top of the page.
    await run(browser, {viewport: {width: 960, height: 460}, theme: 'dark'});
    await run(browser, {viewport: {width: 390, height: 560}, theme: 'light'});
    console.log('stage scroll: ok');
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exit(1);
});
