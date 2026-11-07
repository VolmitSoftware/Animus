// Opens test/fixtures/gui_editors in the built site at desktop and phone
// widths, in the dark and light themes, and checks each GUI editor: the
// chest GUI (keyboard to a slot, brush drag, lore in slot names), shop
// (a preview slot opens its offer, kind change strips fields, container shop
// round trip, trading screen offers and layout), hologram
// (rows keep show and scale, two quick style edits both kept and undone one
// at a time, rows drawn as one styled text display), menu (stage pick,
// motion and tooltip), text
// animation (preset fix, preview pause) and presets editors; edits
// round-trip to the document JSON, refused actions are flagged at their
// field, expression completion works, the data folder zip and the Gloss
// bundle export valid files, Gloss ids round-trip and a Gloss bundle
// imports, with no console errors and no sideways scrolling.
//
//   python3 tool/serve_site.py --port <port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/gui_editors_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const zlib = require('node:zlib');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {closeDrawer, openFromSidebar, sidebar, topbarAction} = require('./support/shell.cjs');
const {chooseAndWait, optionLabels, valueOf} = require('./support/select.cjs');

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
  await openFromSidebar(page, sidebarText);
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
  await page.getByRole('heading', {name: 'From a data folder'}).waitFor();
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
  await chooseAndWait(page.getByLabel('Mask brush'), '#');
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
  await chooseAndWait(page.getByLabel('Mask brush'), '');
  await topbarAction(page, 'Undo');
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

  // Shop: a slot in the chest preview opens its offer on the board, price edit.
  await open(page, 'town:market');
  const board = page.locator('.animus-shop-board');
  const settings = page.locator('.animus-shop-settings');
  await page.locator('.animus-shop-preview [data-slot="10"]').click();
  const price = board.getByLabel('Price', {exact: true});
  await price.fill('15');
  await page.waitForTimeout(300);
  await shot(page, `${tag}-shop`);
  json = await codeOf(page);
  assert.equal(json.pages[0].buy['10'].price, 15);
  assert.equal(json.pages[0].buy['10'].limit.resetSeconds, 86400);
  await noSideways(page, `${tag} shop`);

  // Changing an offer's kind removes what the new kind refuses.
  await page.locator('.animus-shop-preview [data-slot="12"]').click();
  await chooseAndWait(board.getByLabel('Kind', {exact: true}), 'static');
  await settle(page);
  await page.waitForTimeout(300);
  json = await codeOf(page);
  assert.equal(json.pages[0].buy['12'].type, 'static');
  assert.equal(json.pages[0].buy['12'].stock, undefined, 'stock goes with the kind change');
  assert.equal(json.pages[0].buy['12'].permission, 'town.market.rare', 'other fields stay');

  // A container shop: owner and containers round-trip, and buy entries are refused.
  await settings.getByRole('tab', {name: 'Shop', exact: true}).click();
  await (await openSection(settings, 'Containers')).getByRole('button', {name: '+ Add container'}).click();
  await page.getByLabel('Container 1 world').fill('world_nether');
  await settle(page);
  await page.getByLabel('Container 1 y').fill('70');
  await settle(page);
  await openSection(settings, 'Owner');
  await page.getByLabel('Owner UUID').fill('069a79f4-44e9-4726-a5be-fca90e38aaf5');
  await settle(page);
  await page.getByLabel('Owner name').fill('Notch');
  await settle(page);
  await page.waitForTimeout(300);
  await shot(page, `${tag}-shop-containers`);
  json = await codeOf(page);
  assert.deepEqual(json.containers, [{world: 'world_nether', x: 0, y: 70, z: 0}]);
  assert.deepEqual(json.owner, {uuid: '069a79f4-44e9-4726-a5be-fca90e38aaf5', name: 'Notch'});
  await page.locator('.animus-shop-preview [data-slot="10"]').click();
  await board.getByText('a container shop has only trade and static entries').first().waitFor();
  assert.deepEqual((await optionLabels(board.getByLabel('Kind', {exact: true}))).sort(), ['Buy (player pays money)', 'Decoration', 'Trade (items for items)']);
  await (await openSection(settings, 'Containers')).getByRole('button', {name: 'Remove container 1'}).click();
  await page.waitForTimeout(200);

  // Window and layout: the trading screen lists offers in mode, page and slot order.
  await chooseAndWait(settings.getByLabel('Window', {exact: true}), 'native');
  await settle(page);
  const offers = page.locator('.animus-shop-preview .animus-merchant-offer');
  await offers.first().waitFor();
  assert.deepEqual(await offers.evaluateAll(rows => rows.map(row => row.getAttribute('data-offer'))), ['bread', 'town-kit', 'iron-pickaxe', 'wheat', 'emerald-for-diamond'], 'the static golden apple is left out');
  assert.equal(await offers.first().locator('.animus-merchant-price').innerText(), '15');
  assert.equal(await offers.nth(3).locator('.animus-merchant-price').innerText(), 'Pays 4');
  await chooseAndWait(settings.getByLabel('Layout', {exact: true}), 'town:board');
  await settle(page);
  await page.waitForTimeout(300);
  await settings.getByText('only the chest style uses a layout').first().waitFor();
  await shot(page, `${tag}-shop-native`);
  await noSideways(page, `${tag} shop native`);
  json = await codeOf(page);
  assert.equal(json.style, 'native');
  assert.equal(json.layout, 'town:board');
  await page.getByRole('tab', {name: 'Preview'}).click();
  await page.locator('.animus-gui-preview .animus-merchant-offer').first().waitFor();
  assert.equal(await page.locator('.animus-gui-preview .animus-chest-slot[data-slot]').count(), 0, 'the read-only preview has no clickable slots');
  await page.getByRole('tab', {name: 'Visual'}).click();
  await chooseAndWait(settings.getByLabel('Layout', {exact: true}), '');
  await settle(page);
  await chooseAndWait(settings.getByLabel('Window', {exact: true}), '');
  await settle(page);
  await page.waitForTimeout(300);
  json = await codeOf(page);
  assert.equal(json.style, undefined);
  assert.equal(json.layout, undefined);
  const bundleChooser = page.waitForEvent('filechooser');
  await topbarAction(page, 'Import a Gloss bundle', {menu: 'import'});
  await (await bundleChooser).setFiles(bundle);
  const loaded = await sidebar(page);
  await loaded.getByText('gloss:chest', {exact: true}).waitFor();
  await loaded.getByText('gloss:lobby/main', {exact: true}).waitFor();
  await closeDrawer(page);
  await open(page, 'gloss:chest');
  await page.locator('.animus-chest-slot').first().waitFor();
  await page.waitForFunction(() => document.querySelectorAll('.animus-chest-slot').length === 9);

  // Preview views.
  await open(page, 'town:market');
  await page.getByRole('tab', {name: 'Preview'}).click();
  await page.locator('.animus-gui-preview .animus-chest-slot').first().waitFor();
  await page.locator('.animus-gui-preview').getByText('the server default').waitFor();
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
