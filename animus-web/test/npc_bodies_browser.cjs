// Opens test/fixtures/npc_bodies as a data folder in the built site and
// checks that the Preview stage draws every body type a definition can name
// (types/) and the body settings that change the look (settings/): each
// stage draws at least one mesh with nothing left waiting for a model or
// texture, no texture falls back to plain white, no note says the preview
// has no model, the canvas is not one flat colour, and a text display shows
// its text. Desktop width in the dark theme and phone width in the light
// one, with no horizontal overflow and no console errors. Needs the asset
// pack (tool/extract_minecraft_world_assets.dart) in the build.
//
//   python3 tool/serve_site.py --port <port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_SCREENSHOTS=<folder> node test/npc_bodies_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {closeDrawer, sidebar, topbarAction} = require('./support/shell.cjs');

const folder = path.resolve(__dirname, 'fixtures/npc_bodies');
const shots = process.env.ANIMUS_SCREENSHOTS;
const only = process.env.ANIMUS_BODIES ? new RegExp(process.env.ANIMUS_BODIES) : null;

/// Every sample by its sidebar name and document id.
function samples() {
  const found = [];
  for (const group of ['types', 'settings']) {
    for (const file of fs.readdirSync(path.join(folder, 'npcs', group)).sort()) {
      const definition = JSON.parse(fs.readFileSync(path.join(folder, 'npcs', group, file), 'utf8'));
      const id = `${group}/${file.replace(/\.json$/, '')}`;
      if (!only || only.test(id)) found.push({id, name: definition.name, type: definition.type});
    }
  }
  return found;
}

// Distinct colours in a PNG, counted in the page.
async function colours(page, png) {
  return page.evaluate(async base64 => {
    const image = new Image();
    image.src = `data:image/png;base64,${base64}`;
    await image.decode();
    const canvas = document.createElement('canvas');
    canvas.width = image.naturalWidth;
    canvas.height = image.naturalHeight;
    const context = canvas.getContext('2d');
    context.drawImage(image, 0, 0);
    const data = context.getImageData(0, 0, canvas.width, canvas.height).data;
    const seen = new Set();
    for (let i = 0; i < data.length; i += 16) seen.add((data[i] << 16) | (data[i + 1] << 8) | data[i + 2]);
    return seen.size;
  }, png.toString('base64'));
}

async function run(browser, viewport, label, dark) {
  const page = await browser.newPage({viewport});
  page.setDefaultTimeout(20000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })());
  await page.getByRole('heading', {name: 'From a data folder'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(folder);
  await (await sidebar(page)).getByText('Body player', {exact: true}).waitFor();
  await closeDrawer(page);
  const isDark = await page.evaluate(() => document.documentElement.classList.contains('dark'));
  if (isDark !== dark) await topbarAction(page, dark ? 'Use dark theme' : 'Use light theme');
  await page.waitForFunction(want => document.documentElement.classList.contains(want ? 'dark' : 'light'), dark);
  const workspace = page.url().split('#')[1].split('?')[0];
  const all = samples();
  assert.ok(all.length > 0, 'no samples');
  for (const sample of all) {
    await page.goto(`${page.url().split('#')[0]}#${workspace}/npc/${encodeURIComponent(sample.id)}?view=preview`);
    await page.locator('h1').getByText(sample.name, {exact: true}).waitFor();
    await page.waitForFunction(() => {
      const element = document.querySelector('.animus-mc-gl-host');
      return element && Number(element.getAttribute('data-frames')) > 0 && element.getAttribute('data-unbuilt') === '0';
    });
    const host = page.locator('.animus-mc-gl-host');
    const stage = page.locator('.animus-mc-stage');
    assert.equal(await stage.getAttribute('data-webgl'), 'ready', `${sample.id}: WebGL`);
    const notes = (await page.locator('.animus-npc-notes').allTextContents()).join('\n');
    assert.ok(!/no model|not a body type/.test(notes), `${sample.id}: ${notes}`);
    const status = (await page.locator('.animus-mc-status').allTextContents()).join('\n');
    assert.ok(!/drawn white|is not drawn/.test(status), `${sample.id}: ${status}`);
    if (sample.type === 'minecraft:text_display') {
      assert.ok((await page.locator('.animus-mc-text-line').allTextContents()).some(line => line.includes('Welcome')), `${sample.id}: text`);
    } else {
      // The grass patch is not a scene node: at least one body mesh drew.
      assert.ok(Number(await host.getAttribute('data-drawn')) > 0, `${sample.id}: no mesh drawn`);
    }
    const png = await stage.screenshot();
    const count = await colours(page, png);
    assert.ok(count > 40, `${sample.id}: canvas looks blank (${count} colours)`);
    if (shots) {
      fs.mkdirSync(shots, {recursive: true});
      fs.writeFileSync(path.join(shots, `${label}-${sample.id.replace('/', '-')}.png`), png);
    }
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    assert.ok(overflow <= 0, `${sample.id}: ${overflow}px of horizontal overflow`);
  }
  assert.deepEqual(errors, []);
  await page.close();
}

(async () => {
  const browser = await chromium.launch({headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
  try {
    await run(browser, {width: 1440, height: 900}, 'desktop-dark', true);
    await run(browser, {width: 390, height: 844}, 'phone-light', false);
    console.log('npc_bodies_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
