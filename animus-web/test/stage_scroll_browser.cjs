// The NPC stage under the sticky top bar and the toasts. A blank workspace
// gets one NPC on the default ground. A name typed while the stage is off
// screen still shows the ground once the stage scrolls half back under the
// top bar. A nameplate scrolled under the top bar is covered by it. A toast
// (Saved, from the save shortcut) over part of the stage leaves the rest of
// the picture as it is. 960 px
// dark and 390 px light at device scale 2, no console errors. Needs no fake
// server.
//
// Pictures come from full viewport screenshots cut in the page: a clipped
// screenshot composites the page again and hides what the screen shows.
//
//   python3 tool/serve_site.py --port <port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/stage_scroll_browser.cjs
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
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
    const bar = document.querySelector('.animus-topbar').getBoundingClientRect().bottom;
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

/** Scrolls the page so the stage's top sits [offset] px from the viewport top. */
async function stageTopAt(page, offset) {
  await page.locator('.animus-mc-stage').evaluate((el, at) => scrollBy(0, el.getBoundingClientRect().top - at), offset);
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
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });

  await page.goto(url);
  await page.getByLabel('Workspace name').fill(`Stage ${label}`);
  await page.getByRole('button', {name: 'Create blank workspace'}).click();
  await page.getByRole('heading', {name: 'New document'}).waitFor();
  const workspace = await page.evaluate(() => location.hash.split('/')[2]);
  await page.goto(`${url}/#/workspace/${workspace}/new/npc`);
  await page.getByRole('heading', {name: 'New NPC'}).waitFor();
  await page.getByLabel('Name', {exact: true}).fill('Baker');
  await page.getByRole('button', {name: 'Create NPC'}).click();
  const name = page.locator('#animus-field--name');
  await name.waitFor();
  await page.waitForFunction(() => Number(document.querySelector('.animus-mc-gl-host')?.dataset.frames) > 0 && document.querySelector('[data-text]'));
  await page.waitForTimeout(500);

  // A toast over part of the stage changes nothing outside the toast.
  const stage = page.locator('.animus-mc-stage');
  const height = await stage.evaluate(el => el.getBoundingClientRect().height);
  await stageTopAt(page, viewport.height - height * 0.75);
  await dismissToasts(page);
  await page.keyboard.press(`${modKey}+s`);
  await page.locator('.arcane-toast').waitFor();
  await page.waitForTimeout(400);
  const toast = await page.evaluate(() => {
    const t = document.querySelector('.arcane-toast');
    return t ? t.getBoundingClientRect().toJSON() : null;
  });
  assert.ok(toast != null, `${label}: the Saved toast is up`);
  const stageBox = await stage.evaluate(el => el.getBoundingClientRect().toJSON());
  assert.ok(toast.top < stageBox.bottom && toast.bottom > stageBox.top && toast.left < stageBox.right, `${label}: the toast overlaps the stage`);
  const above = {x: stageBox.left + 2, y: Math.max(stageBox.top + 2, 60), width: stageBox.width - 4, height: toast.top - 4 - Math.max(stageBox.top + 2, 60)};
  const withToast = await pixels(page, above);
  assert.equal(await page.locator('.arcane-toast').count(), 1, `${label}: the toast stayed up while the stage was read`);
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
  const barBottom = await page.locator('.animus-topbar').evaluate(el => el.getBoundingClientRect().bottom);
  const plateTop = await plate.evaluate(el => el.getBoundingClientRect().top - el.closest('.animus-mc-stage').getBoundingClientRect().top);
  const plateHeight = await plate.evaluate(el => el.getBoundingClientRect().height);
  await stageTopAt(page, barBottom - plateTop - plateHeight / 2);
  const strip = await plate.evaluate(el => {
    const r = el.getBoundingClientRect();
    const bar = document.querySelector('.animus-topbar').getBoundingClientRect();
    const top = Math.max(r.top, bar.top);
    const left = Math.max(r.left, 0);
    return top + 4 >= bar.bottom ? null : {x: left, y: top, width: Math.min(r.right, innerWidth) - left, height: bar.bottom - 1 - top};
  });
  assert.ok(strip != null, `${label}: the nameplate reaches under the top bar`);
  const shown = await pixels(page, strip);
  await page.evaluate(() => document.querySelectorAll('.animus-mc-text-layer').forEach(el => { el.style.visibility = 'hidden'; }));
  const hidden = await pixels(page, strip);
  await page.evaluate(() => document.querySelectorAll('.animus-mc-text-layer').forEach(el => { el.style.visibility = ''; }));
  assert.deepEqual(shown, hidden, `${label}: the top bar covers a nameplate scrolled under it`);

  assert.deepEqual(errors, [], `${label}: console errors`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch();
  try {
    await run(browser, {viewport: {width: 960, height: 600}, theme: 'dark'});
    await run(browser, {viewport: {width: 390, height: 844}, theme: 'light'});
    console.log('stage scroll: ok');
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exit(1);
});
