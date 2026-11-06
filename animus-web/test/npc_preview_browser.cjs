// Opens test/fixtures/npc_preview as a data folder in the built site and
// checks that the Preview stage draws every sample NPC: frames rendered,
// nothing left waiting for a texture, a canvas that is not one flat colour,
// and no console errors, at desktop width in the dark theme and phone width
// in the light one, with no horizontal overflow. It also orbits the stage
// from the keyboard and checks that a skin PNG of the wrong size says why
// under the stage. A player with no skin wears its built-in placeholder in
// classic and slim arms (served, decoded, not blank), and the inspector's
// placeholder grid swaps it. Needs the asset pack (tool/extract_minecraft_world_assets.dart)
// in the build.
//
//   python3 tool/serve_site.py --port <port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_SCREENSHOTS=<folder> node test/npc_preview_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {closeDrawer, sidebar, topbarAction} = require('./support/shell.cjs');

const folder = path.resolve(__dirname, 'fixtures/npc_preview');
const shots = process.env.ANIMUS_SCREENSHOTS;
const samples = [
  ['Hero', 'players/hero'],
  ['&6The Very Long Named Merchant', 'players/merchant'],
  ['Sleeper', 'players/sleeper'],
  ['Swimmer', 'players/swimmer'],
  ['Sitter', 'players/sitter'],
  ['Posed stand', 'stands/posed'],
  ['Tiny', 'stands/tiny'],
  ['Steed', 'animals/horse'],
  ['Shelly', 'animals/turtle'],
  ['Ribbit', 'animals/frog'],
  ['Spitter', 'animals/llama'],
  ['Roller', 'animals/armadillo'],
  ['Ender', 'animals/dragon'],
  ['Leather', 'armour/leather'],
  ['Chainmail', 'armour/chainmail'],
  ['Netherite', 'armour/netherite'],
  ['Copper', 'armour/copper'],
  ['Leather stand', 'armour/leather-stand'],
  ['Iron stand', 'armour/iron-stand'],
  ['Diamond stand', 'armour/diamond-stand'],
];

// Distinct colours in a PNG, counted in the page (no Node image library).
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
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  const placeholders = new Map();
  page.on('response', response => {
    const match = /\/assets\/skins\/placeholder\/([a-z-]+\.png)$/.exec(new URL(response.url()).pathname);
    if (match) placeholders.set(match[1], response.status());
  });
  await page.goto(process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })());
  await page.getByRole('heading', {name: 'From a data folder'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(folder);
  await (await sidebar(page)).getByText('Hero', {exact: true}).waitFor();
  await closeDrawer(page);
  const isDark = await page.evaluate(() => document.documentElement.classList.contains('dark'));
  if (isDark !== dark) await topbarAction(page, dark ? 'Use dark theme' : 'Use light theme');
  await page.waitForFunction(want => document.documentElement.classList.contains(want ? 'dark' : 'light'), dark);
  const workspace = page.url().split('#')[1].split('?')[0];
  for (const [title, id] of samples) {
    await page.goto(`${page.url().split('#')[0]}#${workspace}/npc/${encodeURIComponent(id)}?view=preview`);
    // The heading shows the name without its legacy colour codes.
    await page.locator('h1').getByText(title.replace(/[&§][0-9a-fk-or]/gi, ''), {exact: true}).waitFor();
    const host = page.locator('.animus-mc-gl-host');
    await page.waitForFunction(() => {
      const element = document.querySelector('.animus-mc-gl-host');
      return element && Number(element.getAttribute('data-frames')) > 0 && element.getAttribute('data-unbuilt') === '0';
    });
    const stage = page.locator('.animus-mc-stage');
    assert.equal(await stage.getAttribute('data-webgl'), 'ready', `${id}: WebGL`);
    const png = await stage.screenshot();
    const count = await colours(page, png);
    assert.ok(count > 40, `${id}: canvas looks blank (${count} colours)`);
    if (shots) {
      fs.mkdirSync(shots, {recursive: true});
      fs.writeFileSync(path.join(shots, `${label}-${id.replace('/', '-')}.png`), png);
      if (id.startsWith('armour/') || id === 'players/hero') {
        // A quarter turn round, to see the layers from the side.
        const box = await stage.boundingBox();
        const frames = Number(await host.getAttribute('data-frames'));
        await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
        await page.mouse.down();
        await page.mouse.move(box.x + box.width / 2 + 280, box.y + box.height / 2, {steps: 4});
        await page.mouse.up();
        await page.waitForFunction(n => Number(document.querySelector('.animus-mc-gl-host').getAttribute('data-frames')) > n, frames);
        fs.writeFileSync(path.join(shots, `${label}-${id.replace('/', '-')}-side.png`), await stage.screenshot());
      }
    }
    assert.ok(await host.count() === 1);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    assert.ok(overflow <= 0, `${id}: ${overflow}px of horizontal overflow`);
  }
  // A player with no skin wears its placeholder, classic then slim.
  const drawn = async n => page.waitForFunction(min => {
    const element = document.querySelector('.animus-mc-gl-host');
    return element && Number(element.getAttribute('data-frames')) > min && element.getAttribute('data-unbuilt') === '0';
  }, n);
  const wearsPlaceholder = async (file, shot) => {
    await page.waitForFunction(name => performance.getEntriesByType('resource').some(entry => entry.name.endsWith(`/assets/skins/placeholder/${name}`)), file);
    await drawn(0);
    assert.equal(placeholders.get(file), 200, `${file}: served`);
    assert.equal(await page.locator('.animus-mc-status').getByText('drawn plain white').count(), 0, `${file}: decoded`);
    const png = await page.locator('.animus-mc-stage').screenshot();
    const count = await colours(page, png);
    assert.ok(count > 40, `${file}: canvas looks blank (${count} colours)`);
    if (shots) fs.writeFileSync(path.join(shots, `${label}-${shot}.png`), png);
  };
  await page.goto(`${page.url().split('#')[0]}#${workspace}/npc/players%2Fmerchant?view=preview`);
  const skinState = page.locator('[data-testid="npc-skin-state"]');
  await skinState.getByText(/the placeholder \w+ skin \(classic\), in this preview only; the definition names no skin/).waitFor();
  const look = /placeholder (\w+) skin/.exec(await skinState.textContent())[1].toLowerCase();
  await wearsPlaceholder(`${look}.png`, `placeholder-${look}-classic`);
  await page.getByRole('button', {name: 'Slim', exact: true}).click();
  await skinState.getByText(/the placeholder \w+ skin \(slim\)/).waitFor();
  await wearsPlaceholder(`${look}-slim.png`, `placeholder-${look}-slim`);
  // The inspector's grid picks another placeholder for the stage beside it.
  const other = look === 'miner' ? 'Scholar' : 'Miner';
  await page.goto(`${page.url().split('#')[0]}#${workspace}/npc/players%2Fmerchant?view=visual`);
  const appearance = page.locator('details[data-section="appearance"]');
  if (!(await appearance.evaluate(element => element.open))) await appearance.locator('summary').first().click();
  const grid = page.getByRole('radiogroup', {name: 'Placeholder skins'});
  assert.equal(await grid.getByRole('radio').count(), 8);
  assert.equal(await grid.getByRole('radio', {name: `Placeholder ${look[0].toUpperCase()}${look.slice(1)}`}).getAttribute('aria-checked'), 'true');
  await grid.getByRole('radio', {name: `Placeholder ${other}`}).click();
  assert.equal(await grid.getByRole('radio', {name: `Placeholder ${other}`}).getAttribute('aria-checked'), 'true');
  await skinState.getByText(`the placeholder ${other} skin (classic)`).waitFor();
  await wearsPlaceholder(`${other.toLowerCase()}.png`, `placeholder-${other.toLowerCase()}-picked`);
  if (shots) fs.writeFileSync(path.join(shots, `${label}-placeholder-picker.png`), await page.screenshot({fullPage: true}));
  // Text above the hero: the hologram lines and the native nameplate.
  await page.goto(`${page.url().split('#')[0]}#${workspace}/npc/players%2Fhero?view=split`);
  await page.locator('.animus-mc-text').first().waitFor();
  assert.ok((await page.locator('.animus-mc-text-line').allTextContents()).includes('Quest Giver'));
  await page.locator('.animus-code textarea').waitFor();
  // Orbit the camera: a drag must draw new frames.
  const before = Number(await page.locator('.animus-mc-gl-host').getAttribute('data-frames'));
  const box = await page.locator('.animus-mc-stage').boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 + 80, box.y + box.height / 2 + 20, {steps: 5});
  await page.mouse.up();
  await page.waitForFunction(n => Number(document.querySelector('.animus-mc-gl-host').getAttribute('data-frames')) > n, before);
  if (shots) fs.writeFileSync(path.join(shots, `${label}-hero-split-orbit.png`), await page.screenshot({fullPage: true}));
  // Orbit from the keyboard: the focused stage takes the arrow keys.
  const keyed = Number(await page.locator('.animus-mc-gl-host').getAttribute('data-frames'));
  await page.locator('.animus-mc-stage').focus();
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('-');
  await page.waitForFunction(n => Number(document.querySelector('.animus-mc-gl-host').getAttribute('data-frames')) > n, keyed);
  await page.keyboard.press('0');
  assert.equal(await page.locator('.animus-mc-stage').getAttribute('role'), 'img');
  assert.ok((await page.locator('.animus-mc-stage').getAttribute('aria-describedby')).includes('-status'));
  // Load a skin PNG from a file: a generated 64x64 two-colour skin.
  const skin = await page.evaluate(() => {
    const canvas = document.createElement('canvas');
    canvas.width = 64;
    canvas.height = 64;
    const context = canvas.getContext('2d');
    context.fillStyle = '#d23c3c';
    context.fillRect(0, 0, 64, 32);
    context.fillStyle = '#3c64d2';
    context.fillRect(0, 32, 64, 32);
    return canvas.toDataURL('image/png').split(',')[1];
  });
  const scratch = fs.mkdtempSync(path.join(shots || require('node:os').tmpdir(), 'animus-skin-'));
  const skinFile = path.join(scratch, 'skin.png');
  fs.writeFileSync(skinFile, Buffer.from(skin, 'base64'));
  const framesBefore = Number(await page.locator('.animus-mc-gl-host').getAttribute('data-frames'));
  const skinChooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Load skin PNG'}).click();
  await (await skinChooser).setFiles(skinFile);
  await page.locator('.animus-npc-facts').getByText('skin.png (slim)').waitFor();
  await page.waitForFunction(n => {
    const element = document.querySelector('.animus-mc-gl-host');
    return Number(element.getAttribute('data-frames')) > n && element.getAttribute('data-unbuilt') === '0';
  }, framesBefore);
  if (shots) fs.writeFileSync(path.join(shots, `${label}-hero-loaded-skin.png`), await page.locator('.animus-mc-stage').screenshot());
  // A PNG that is not a skin: the reason stays under the stage.
  const tiny = await page.evaluate(() => {
    const canvas = document.createElement('canvas');
    canvas.width = 10;
    canvas.height = 10;
    canvas.getContext('2d').fillRect(0, 0, 10, 10);
    return canvas.toDataURL('image/png').split(',')[1];
  });
  const tinyFile = path.join(scratch, 'tiny.png');
  fs.writeFileSync(tinyFile, Buffer.from(tiny, 'base64'));
  const tinyChooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Load skin PNG'}).click();
  await (await tinyChooser).setFiles(tinyFile);
  await page.locator('.animus-npc-notes').getByText('this image is 10x10. Pick a 64x64 Minecraft skin PNG.').waitFor();
  if (shots) fs.writeFileSync(path.join(shots, `${label}-skin-failure.png`), await page.screenshot({fullPage: true}));
  fs.rmSync(scratch, {recursive: true, force: true});
  assert.deepEqual(errors, []);
  await page.close();
}

(async () => {
  const browser = await chromium.launch({headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
  try {
    await run(browser, {width: 1440, height: 900}, 'desktop-dark', true);
    await run(browser, {width: 390, height: 844}, 'phone-light', false);
    console.log('npc_preview_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
