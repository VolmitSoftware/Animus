// Opens test/fixtures/gui_editors in the built site at desktop and phone
// widths, in the dark and light themes, and checks each GUI editor: the
// chest GUI (keyboard to a slot, brush drag, lore in slot names), shop
// (entry type change strips fields, container shop round trip), hologram
// (rows keep show and scale), menu (stage pick, motion and tooltip), text
// animation (preset fix, preview pause) and presets editors; edits
// round-trip to the document JSON, refused actions are flagged at their
// field, expression completion works, the data folder zip and the Gloss
// bundle export valid files, Gloss ids round-trip and a Gloss bundle
// imports, with no console errors and no sideways scrolling.
//
//   python3 -m http.server <port> --bind 127.0.0.1 --directory build/jaspr
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/gui_editors_browser.cjs
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

/// Lets the store commit an edit before the next field reads the document.
async function settle(page) {
  await page.waitForTimeout(150);
}

async function noSideways(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 0, `${where} scrolls sideways by ${overflow}px`);
}

/// The GUI section (a details element) whose summary starts with [title].
function section(scope, title) {
  return scope.locator('details.animus-gui-section').filter({has: scope.page().locator(`:scope > summary`, {hasText: new RegExp(`^${title}`)})}).first();
}

async function openSection(scope, title) {
  const details = section(scope, title);
  if (!(await details.evaluate(el => el.open))) await details.locator(':scope > summary').click();
  return details;
}

async function run(browser, viewport, tag, theme) {
  const context = await browser.newContext({viewport, acceptDownloads: true});
  await context.addInitScript(mode => window.localStorage.setItem('animus.theme', mode), theme);
  const page = await context.newPage();
  page.on('dialog', dialog => dialog.accept());
  const phone = viewport.width < 520;
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })());
  await page.getByRole('heading', {name: 'Open an Animus data folder'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(fixture);
  await page.getByRole('heading', {name: '2 documents need repair'}).waitFor();
  assert.ok(await page.evaluate(mode => document.documentElement.classList.contains(mode), theme), `${theme} theme is on`);

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
  await noSideways(page, `${tag} chest GUI`);
  // Keyboard: Tab from one slot to the next, Enter opens it in the inspector.
  await page.locator('[data-slot="0"]').focus();
  await page.keyboard.press('Tab');
  assert.equal(await page.evaluate(() => document.activeElement.getAttribute('data-slot')), '1');
  await page.keyboard.press('Enter');
  await page.getByRole('tab', {name: 'Slot 1', selected: true}).waitFor();
  // A slot's accessible name carries its name and lore.
  assert.match(await page.locator('[data-slot="29"]').getAttribute('aria-label'), /Previous\. Page 1 of 1/);
  if (phone) {
    const box = await page.locator('[data-slot="0"]').boundingBox();
    assert.ok(box.width >= 44 && box.height >= 44, `phone slots are ${box.width}x${box.height}`);
  }
  // The brush paints every slot a drag crosses.
  await page.getByLabel('Mask brush').selectOption('#');
  await page.locator('[data-slot="10"]').scrollIntoViewIfNeeded();
  // Each slot is measured as the pointer reaches it, as a hand follows the
  // window when a stroke reflows the page.
  const centre = async slot => {
    const box = await page.locator(`[data-slot="${slot}"]`).boundingBox();
    return [box.x + box.width / 2, box.y + box.height / 2];
  };
  await page.mouse.move(...(await centre(10)));
  await page.mouse.down();
  for (const slot of [11, 12]) {
    await settle(page);
    const [x, y] = await centre(slot);
    await page.mouse.move(x, y, {steps: 4});
  }
  await page.mouse.up();
  await page.waitForTimeout(300);
  let painted = await codeOf(page);
  assert.equal(painted.mask[1], '####LLLL#', `${tag} brush stroke`);
  await page.getByLabel('Mask brush').selectOption('');
  await page.getByRole('button', {name: 'Undo'}).click();
  await page.waitForTimeout(200);
  painted = await codeOf(page);
  assert.equal(painted.mask[1], '#LLLLLLL#');
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
  await noSideways(page, `${tag} shop`);

  // Changing an entry's type removes what the new type refuses.
  await page.locator('[data-slot="12"]').click();
  await page.getByLabel('Type', {exact: true}).selectOption('static');
  await settle(page);
  await page.waitForTimeout(300);
  json = await codeOf(page);
  assert.equal(json.pages[0].buy['12'].type, 'static');
  assert.equal(json.pages[0].buy['12'].stock, undefined, 'stock goes with the type change');
  assert.equal(json.pages[0].buy['12'].permission, 'town.market.rare', 'other fields stay');

  // A container shop: owner and containers round-trip, and buy entries are refused.
  await page.getByRole('tab', {name: 'Shop', exact: true}).click();
  await (await openSection(page.locator('.animus-gui-inspector'), 'Containers')).getByRole('button', {name: 'Add container'}).click();
  await page.getByLabel('Container 1 world').fill('world_nether');
  await settle(page);
  await page.getByLabel('Container 1 y').fill('70');
  await settle(page);
  await openSection(page.locator('.animus-gui-inspector'), 'Owner');
  await page.getByLabel('Owner UUID').fill('069a79f4-44e9-4726-a5be-fca90e38aaf5');
  await settle(page);
  await page.getByLabel('Owner name').fill('Notch');
  await settle(page);
  await page.waitForTimeout(300);
  await shot(page, `${tag}-shop-containers`);
  json = await codeOf(page);
  assert.deepEqual(json.containers, [{world: 'world_nether', x: 0, y: 70, z: 0}]);
  assert.deepEqual(json.owner, {uuid: '069a79f4-44e9-4726-a5be-fca90e38aaf5', name: 'Notch'});
  await page.locator('[data-slot="10"]').click();
  await page.locator('.animus-gui-inspector').getByText('a container shop has only trade and static entries').first().waitFor();
  assert.deepEqual((await page.getByLabel('Type', {exact: true}).locator('option').allTextContents()).sort(), ['buy (the player pays), not allowed here', 'static (decoration)']);
  await page.getByRole('tab', {name: 'Shop', exact: true}).click();
  await (await openSection(page.locator('.animus-gui-inspector'), 'Containers')).getByRole('button', {name: 'Remove container 1'}).click();
  await page.waitForTimeout(200);

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
  // An item row keeps show and scale through its edits.
  const itemRow = page.locator('[data-path="$.lines[2]"]');
  await itemRow.getByLabel('Scale').fill('2');
  await settle(page);
  await itemRow.getByLabel('Row 3 show when').fill('viewer.level > 1');
  await settle(page);
  await itemRow.getByLabel('Row 3 item').fill('minecraft:diamond');
  await settle(page);
  await itemRow.getByLabel('Row 3 count').fill('3');
  await settle(page);
  await page.waitForTimeout(300);
  json = await codeOf(page);
  assert.deepEqual(json.lines[2], {item: {type: 'item', item: 'minecraft:diamond', count: 3}, scale: 2, show: 'viewer.level > 1'});
  await page.locator('[data-path="$.lines[2]"]').getByLabel('Row 3 kind').selectOption('head');
  await settle(page);
  await page.waitForTimeout(300);
  json = await codeOf(page);
  assert.deepEqual(json.lines[2], {head: '{{ viewer.name }}', scale: 2, show: 'viewer.level > 1'});
  await noSideways(page, `${tag} hologram`);

  // Menu: 3D stage, rename a component.
  await open(page, 'town:hub');
  await page.waitForFunction(() => Number(document.querySelector('.animus-mc-gl-host')?.getAttribute('data-frames') || 0) > 0);
  await page.getByRole('button', {name: /^market \(button\)/}).click();
  const componentId = page.getByLabel('Component id');
  await componentId.fill('shop');
  await settle(page);
  await page.waitForTimeout(300);
  await shot(page, `${tag}-menu`);
  json = await codeOf(page);
  assert.equal(json.components[1].id, 'shop');
  await noSideways(page, `${tag} menu`);

  // A click on the stage selects the component under it.
  await page.locator('[data-pick="stone"]').click();
  assert.equal(await page.getByLabel('Component id').inputValue(), 'stone');
  await page.getByRole('button', {name: /^shop \(button\)/}).click();
  // Motion and tooltip fields write into the component.
  const inspector = page.locator('.animus-gui-inspector');
  await openSection(inspector, 'Motion');
  await page.getByLabel('Motion Y').fill('0.5');
  await settle(page);
  await page.getByLabel('Motion ticks').fill('6');
  await settle(page);
  const tooltip = await openSection(inspector, 'Tooltip');
  await tooltip.getByRole('button', {name: 'Add line'}).click();
  await tooltip.getByLabel('Line 1', {exact: true}).fill('&7Opens the market');
  await settle(page);
  await tooltip.getByLabel('Tooltip delay in ticks').fill('5');
  await settle(page);
  await page.waitForTimeout(300);
  await shot(page, `${tag}-menu-fields`);
  json = await codeOf(page);
  assert.deepEqual(json.components[1].motion, {y: 0.5, ticks: 6});
  assert.deepEqual(json.components[1].data.tooltip, {lines: ['&7Opens the market'], delayTicks: 5});
  for (let i = 0; i < 4; i++) await page.getByRole('button', {name: 'Undo'}).click();
  await page.waitForTimeout(200);
  json = await codeOf(page);
  assert.equal(json.components[1].motion, undefined);
  assert.equal(json.components[1].data.tooltip, undefined);

  // Text animation: the preset fix copies the preset's fields in; the preview pauses.
  await open(page, 'town:sparkle');
  await page.getByText("Gloss presets are not available in Animus; inline the preset's fields.").waitFor();
  await page.getByRole('button', {name: "Copy this preset's fields into the document"}).click();
  await page.waitForTimeout(300);
  json = await codeOf(page);
  assert.equal(json.preset, undefined);
  assert.equal(json.mode, 'ascend_descend');
  assert.equal(json.frameIntervalMs, 300, 'the document keeps its own interval over the default');
  const toggle = page.locator('[data-anim="toggle"]');
  await toggle.click();
  assert.equal(await toggle.getAttribute('aria-pressed'), 'true');
  const held = await page.locator('[data-anim="frame"]').innerText();
  assert.match(held, /paused/);
  await page.waitForTimeout(700);
  assert.equal(await page.locator('[data-anim="frame"]').innerText(), held, 'a paused preview holds its frame');
  await page.getByRole('button', {name: 'Next frame'}).click();
  assert.notEqual(await page.locator('[data-anim="frame"]').innerText(), held);
  await page.getByRole('button', {name: 'Add frame'}).click();
  await page.waitForTimeout(300);
  await shot(page, `${tag}-text-animation`);
  json = await codeOf(page);
  assert.equal(json.frames.length, 4);
  await noSideways(page, `${tag} text animation`);

  // Presets: the kind in use opens first; a new preset is written under it.
  await open(page, 'presets');
  assert.equal(await page.getByLabel('Kind', {exact: true}).inputValue(), 'animations');
  await (await openSection(page.locator('.animus-gui-inspector'), 'Presets for animations')).getByRole('button', {name: 'Add a preset'}).click();
  await page.waitForTimeout(300);
  await shot(page, `${tag}-presets`);
  json = await codeOf(page);
  assert.deepEqual(json.presets.animations.preset2, {extends: null, values: {}});
  await noSideways(page, `${tag} presets`);
  await open(page, 'town:hub');

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
    'gui/presets.json',
    'gui/text-animations/town/sparkle.json',
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
  assert.deepEqual(exported.workspace.documents.map(d => d.runtimeId).sort(), ['presets', 'town.gloss-only', 'town.hub', 'town.sparkle', 'town.welcome']);
  assert.deepEqual(exported.workspace.documents.map(d => d.title).sort(), ['presets', 'town:gloss-only', 'town:hub', 'town:sparkle', 'town:welcome']);
  for (const document of exported.workspace.documents) JSON.parse(document.json);
  // Importing the export back keeps the Animus ids instead of gloss:town-hub.
  const backChooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Import Gloss bundle'}).click();
  await (await backChooser).setFiles(glossFile);
  await page.waitForTimeout(500);
  assert.equal(await page.locator('.animus-sidebar').getByText('gloss:town-hub', {exact: true}).count(), 0);
  await page.locator('.animus-sidebar').getByText('town:hub', {exact: true}).first().waitFor();
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
  await page.locator('.animus-gui-preview').getByRole('tab', {name: 'Sell'}).click();
  await page.locator('.animus-gui-preview').getByRole('tab', {name: 'Sell', selected: true}).waitFor();
  await shot(page, `${tag}-shop-preview`);
  await noSideways(page, `${tag} shop preview`);

  assert.deepEqual(errors, []);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    await run(browser, {width: 1440, height: 900}, 'desktop-dark', 'dark');
    await run(browser, {width: 1440, height: 900}, 'desktop-light', 'light');
    await run(browser, {width: 390, height: 844}, 'phone-dark', 'dark');
    await run(browser, {width: 390, height: 844}, 'phone-light', 'light');
    console.log('gui_editors_browser: ok');
  } finally {
    await browser.close();
    fs.rmSync(scratch, {recursive: true, force: true});
  }
})().catch(error => { console.error(error); process.exit(1); });
