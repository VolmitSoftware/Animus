// Opens test/fixtures/gui_editors in the built site at desktop and phone
// widths and checks each GUI editor: the chest GUI, shop, hologram and menu
// open their fixture, an edit round-trips to the document JSON, refused
// actions are flagged at their field, expression completion works, the data
// folder zip and the Gloss bundle export valid files and a Gloss bundle
// imports, with no console errors.
//
//   python3 -m http.server 8098 --bind 127.0.0.1 --directory build/jaspr
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/gui_editors_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const zlib = require('node:zlib');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const fixture = path.resolve(__dirname, 'fixtures/gui_editors');
const bundle = path.resolve(__dirname, 'fixtures/gloss_bundle/gloss-workspace.json');
const shots = process.env.ANIMUS_SCREENSHOTS;
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'animus-gui-'));

/// Entries of a zip by name, inflated (stored and deflate only).
function readZip(buffer) {
  const entries = {};
  let end = buffer.length - 22;
  while (end >= 0 && buffer.readUInt32LE(end) !== 0x06054b50) end--;
  assert.ok(end >= 0, 'zip has an end of central directory');
  const count = buffer.readUInt16LE(end + 10);
  let at = buffer.readUInt32LE(end + 16);
  for (let i = 0; i < count; i++) {
    assert.equal(buffer.readUInt32LE(at), 0x02014b50);
    const method = buffer.readUInt16LE(at + 10);
    const compressed = buffer.readUInt32LE(at + 20);
    const nameLength = buffer.readUInt16LE(at + 28);
    const extra = buffer.readUInt16LE(at + 30);
    const comment = buffer.readUInt16LE(at + 32);
    const local = buffer.readUInt32LE(at + 42);
    const name = buffer.toString('utf8', at + 46, at + 46 + nameLength);
    const dataStart = local + 30 + buffer.readUInt16LE(local + 26) + buffer.readUInt16LE(local + 28);
    const data = buffer.subarray(dataStart, dataStart + compressed);
    entries[name] = (method === 0 ? data : zlib.inflateRawSync(data)).toString('utf8');
    at += 46 + nameLength + extra + comment;
  }
  return entries;
}

async function shot(page, name) {
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: true});
}

async function codeOf(page) {
  await page.getByRole('tab', {name: 'Code'}).click();
  const text = await page.locator('.animus-code textarea').inputValue();
  await page.getByRole('tab', {name: 'Visual'}).click();
  return JSON.parse(text);
}

async function open(page, sidebarText) {
  await page.locator('.animus-sidebar').getByText(sidebarText, {exact: true}).first().click();
  await page.locator('.animus-document-header').waitFor();
}

async function run(browser, viewport, tag) {
  const context = await browser.newContext({viewport, acceptDownloads: true});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })());
  await page.getByRole('heading', {name: 'Open an Animus data folder'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(fixture);
  await page.getByRole('heading', {name: '1 documents need repair'}).waitFor();

  // Chest GUI: painted window with item icons, slot inspector, title edit, completion.
  await open(page, 'town:board');
  const slots = page.locator('.animus-chest-slot');
  await slots.first().waitFor();
  assert.equal(await slots.count(), 36);
  await page.locator('.animus-chest-face img').first().waitFor();
  assert.ok(await page.locator('.animus-chest-face img').count() >= 20, 'item icons are drawn');
  await page.locator('[data-slot="31"]').click();
  await page.locator('.animus-gui-inspector').getByText('Drawn by mask key T').waitFor();
  await shot(page, `${tag}-chest-gui`);
  await page.getByRole('tab', {name: 'Window'}).click();
  const title = page.getByLabel('Title', {exact: true});
  await title.fill('&2Edited board ');
  await title.press('End');
  await title.pressSequentially('{{ vie');
  const option = page.getByRole('option').filter({hasText: 'viewer.name'}).first();
  await option.waitFor();
  await shot(page, `${tag}-chest-completion`);
  await option.click();
  await title.pressSequentially(' }}');
  await page.waitForTimeout(300);
  let json = await codeOf(page);
  assert.equal(json.title, '&2Edited board {{ viewer.name }}');
  assert.ok(json.list && json.variants, 'untouched fields survive the edit');

  // Refused Gloss action flagged at its exact field.
  await open(page, 'town:gloss-only');
  await page.locator('[data-slot="0"]').click();
  const refused = page.locator('.animus-gui-refused').first();
  await refused.waitFor();
  assert.match(await refused.innerText(), /\$\.keys\.X\.actions\[1\]\.type/);
  await shot(page, `${tag}-chest-refused`);

  // Shop: entry inspector, price edit.
  await open(page, 'town:market');
  await page.locator('[data-slot="10"]').click();
  const price = page.getByLabel('Price', {exact: true});
  await price.fill('15');
  await page.waitForTimeout(300);
  await shot(page, `${tag}-shop`);
  json = await codeOf(page);
  assert.equal(json.pages[0].buy['10'].price, 15);
  assert.equal(json.pages[0].buy['10'].limit.resetSeconds, 86400);

  // Hologram: 3D stage, add a line.
  await open(page, 'town:welcome');
  const hologramCanvas = page.locator('.animus-mc-gl-host');
  await page.waitForFunction(() => Number(document.querySelector('.animus-mc-gl-host')?.getAttribute('data-frames') || 0) > 0);
  assert.ok(await hologramCanvas.count() === 1);
  await page.locator('.animus-mc-text').first().waitFor();
  await page.getByRole('button', {name: 'Add text'}).click();
  await page.waitForTimeout(300);
  await shot(page, `${tag}-hologram`);
  json = await codeOf(page);
  assert.equal(json.lines.length, 6);
  assert.equal(json.lines[5], '&fText');

  // Menu: 3D stage, rename a component.
  await open(page, 'town:hub');
  await page.waitForFunction(() => Number(document.querySelector('.animus-mc-gl-host')?.getAttribute('data-frames') || 0) > 0);
  await page.getByRole('button', {name: /^market \(button\)/}).click();
  const componentId = page.getByLabel('Component id');
  await componentId.fill('shop');
  await page.waitForTimeout(300);
  await shot(page, `${tag}-menu`);
  json = await codeOf(page);
  assert.equal(json.components[1].id, 'shop');

  // Undo reverts the last edit.
  await page.getByRole('button', {name: 'Undo'}).click();
  await page.waitForTimeout(200);
  json = await codeOf(page);
  assert.equal(json.components[1].id, 'market');
  await page.getByRole('button', {name: /^market \(button\)/}).click();
  await componentId.fill('shop');
  await page.waitForTimeout(300);

  // Data folder zip export.
  const zipDownload = page.waitForEvent('download');
  await page.getByRole('button', {name: 'Export zip'}).click();
  const zipFile = path.join(scratch, `${tag}.zip`);
  await (await zipDownload).saveAs(zipFile);
  const entries = readZip(fs.readFileSync(zipFile));
  assert.deepEqual(Object.keys(entries).sort(), [
    'gui/holograms/town/welcome.json',
    'gui/inventories/town/board.json',
    'gui/inventories/town/gloss-only.json',
    'gui/menus/town/hub.json',
    'shops/town/market.json',
  ]);
  for (const text of Object.values(entries)) JSON.parse(text);
  assert.equal(JSON.parse(entries['gui/inventories/town/board.json']).title, '&2Edited board {{ viewer.name }}');
  assert.equal(JSON.parse(entries['shops/town/market.json']).pages[0].buy['10'].price, 15);
  assert.equal(JSON.parse(entries['gui/menus/town/hub.json']).components[1].id, 'shop');

  // Gloss bundle export and import.
  const glossDownload = page.waitForEvent('download');
  await page.getByRole('button', {name: 'Export Gloss bundle'}).click();
  const glossFile = path.join(scratch, `${tag}.gloss.json`);
  await (await glossDownload).saveAs(glossFile);
  const exported = JSON.parse(fs.readFileSync(glossFile, 'utf8'));
  assert.equal(exported.format, 'gloss-editor-workspace');
  assert.equal(exported.version, 1);
  assert.equal(exported.workspace.schemaVersion, 2);
  assert.deepEqual(exported.workspace.documents.map(d => d.runtimeId).sort(), ['town.gloss-only', 'town.hub', 'town.welcome']);
  for (const document of exported.workspace.documents) JSON.parse(document.json);
  const bundleChooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Import Gloss bundle'}).click();
  await (await bundleChooser).setFiles(bundle);
  await page.locator('.animus-sidebar').getByText('gloss:chest', {exact: true}).waitFor();
  await page.locator('.animus-sidebar').getByText('gloss:lobby/main', {exact: true}).waitFor();
  await open(page, 'gloss:chest');
  await page.locator('.animus-chest-slot').first().waitFor();
  assert.equal(await page.locator('.animus-chest-slot').count(), 9);

  // Preview views.
  await open(page, 'town:market');
  await page.getByRole('tab', {name: 'Preview'}).click();
  await page.locator('.animus-gui-preview .animus-chest-slot').first().waitFor();
  await shot(page, `${tag}-shop-preview`);

  assert.deepEqual(errors, []);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    await run(browser, {width: 1440, height: 1000}, 'desktop');
    await run(browser, {width: 390, height: 844}, 'phone');
    console.log('gui_editors_browser: ok');
  } finally {
    await browser.close();
    fs.rmSync(scratch, {recursive: true, force: true});
  }
})().catch(error => { console.error(error); process.exit(1); });
