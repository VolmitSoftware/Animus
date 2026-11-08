// The NPC item slots in the built site, at 1440x900 and 390x844 in the dark
// and light themes. Writes its own data folder (no fake server): a player
// NPC with a sword and helmet, five bread in a stored inventory and a hire
// module costing three emeralds. Then:
//   1. equipment shows the six player slots; a click selects a slot and
//      focuses its item field (with a fitting placeholder), picking an item
//      and stepping its count write `equipment`;
//   2. dragging the main hand onto the off hand trades them, a right click
//      empties a slot and one Undo brings it back, arrow keys move the
//      selection;
//   3. a llama body shows only its body slot plus the set hand slots,
//      flagged;
//   4. the inventory: pick into an empty slot, drag, right click, the size
//      dropdown;
//   5. the hire items row: an empty slot fills from the left, a drag trades.
// Every result is read from the Code tab. No console errors, no sideways
// scrolling.
//
//   python3 tool/serve_site.py --port <WEB_PORT> --directory <site> &
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<WEB_PORT> ANIMUS_SCREENSHOTS=<scratch folder> \
//     node test/inventory_browser.cjs
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {topbarAction} = require('./support/shell.cjs');
const {choose} = require('./support/select.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;

function writeFixture(folder) {
  const file = path.join(folder, 'npcs/town/smith.json');
  fs.mkdirSync(path.dirname(file), {recursive: true});
  fs.writeFileSync(file, `${JSON.stringify({
    schemaVersion: 1, revision: 1, uuid: crypto.randomUUID(), id: 'smith', group: 'town', name: 'Smith', type: 'minecraft:player', spawned: false,
    position: {world: 'world', x: 0.5, y: 64, z: 0.5, yaw: 0, pitch: 0},
    equipment: {main_hand: {id: 'minecraft:iron_sword'}, head: {id: 'minecraft:iron_helmet'}},
    inventory: {size: 9, slots: {0: {id: 'minecraft:bread', count: 5}}},
    modules: {'animus:hire': {enabled: true, price: 10, items: [{id: 'minecraft:emerald', count: 3}]}},
  }, null, 2)}\n`);
}

async function shot(page, name) {
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `inventory-${name}.png`), fullPage: true});
}

async function noSidewaysScroll(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 1, `${where}: the page scrolls sideways by ${overflow}px`);
}

async function codeOf(page) {
  await page.getByRole('tab', {name: 'Code'}).click();
  const text = await page.locator('.animus-code textarea').first().inputValue();
  await page.getByRole('tab', {name: 'Visual'}).click();
  await page.locator('details[data-section="appearance"]').waitFor();
  return JSON.parse(text);
}

async function part(page, id) {
  const details = page.locator(`details[data-section="${id}"]`);
  if (!(await details.evaluate((element) => element.open))) await details.locator('summary').first().click();
  return details;
}

const settle = (page) => page.waitForTimeout(300);

const slotsOf = (scope, grid) => scope.locator(`[data-slot-grid="${grid}"] [data-slot]`);
const slot = (scope, grid, key) => scope.locator(`[data-slot-grid="${grid}"] [data-slot="${key}"]`);

async function keys(scope, grid) {
  return slotsOf(scope, grid).evaluateAll((elements) => elements.map((element) => element.getAttribute('data-slot')));
}

async function drag(page, from, to) {
  await from.scrollIntoViewIfNeeded();
  const a = await from.boundingBox();
  const b = await to.boundingBox();
  await page.mouse.move(a.x + a.width / 2, a.y + a.height / 2);
  await page.mouse.down();
  await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, {steps: 8});
  await page.mouse.up();
  await settle(page);
}

async function focusedId(page) {
  return page.evaluate(() => document.activeElement && document.activeElement.id);
}

async function run(browser, folder, viewport, theme, tag) {
  const context = await browser.newContext({viewport});
  await context.addInitScript((mode) => window.localStorage.setItem('animus.theme', mode), theme);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  page.on('console', (message) => { if (message.type() === 'error' && !/404|Failed to load resource/.test(message.text())) errors.push(message.text()); });

  await page.goto(`${site}/`);
  await page.getByRole('heading', {name: 'From a data folder'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(folder);
  await page.waitForFunction(() => /#\/workspace\/[^/?]+/.test(location.hash));
  const workspace = /#\/workspace\/([^/?]+)/.exec(page.url())[1];
  await page.evaluate((hash) => { location.hash = hash; }, `#/workspace/${workspace}/npc/${encodeURIComponent('town/smith')}?view=visual`);
  await page.locator('#animus-main h1').waitFor();

  // 1. Equipment: six player slots, select, pick, count.
  let look = await part(page, 'appearance');
  const eq = 'animus-equipment';
  assert.deepEqual(await keys(look, eq), ['head', 'chest', 'legs', 'feet', 'off_hand', 'main_hand'], `${tag}: player slots, armor first`);
  await slot(look, eq, 'off_hand').scrollIntoViewIfNeeded();
  await slot(look, eq, 'off_hand').click();
  await page.waitForFunction(() => document.activeElement && document.activeElement.id === 'animus-equipment-item');
  const picker = look.getByLabel('equipment.off_hand', {exact: true});
  assert.equal(await picker.getAttribute('placeholder'), 'minecraft:shield', `${tag}: off hand placeholder`);
  await picker.fill('minecraft:shield');
  await picker.blur();
  await settle(page);
  await slot(look, eq, 'head').click();
  assert.equal(await look.getByLabel('equipment.head', {exact: true}).getAttribute('placeholder'), 'minecraft:iron_helmet', `${tag}: head placeholder`);
  await slot(look, eq, 'main_hand').click();
  const editor = look.locator('.animus-item-slot-editor[data-editing="main_hand"]');
  await editor.getByRole('button', {name: 'One more'}).click();
  await editor.getByRole('button', {name: 'One more'}).click();
  await settle(page);
  assert.equal(await slot(look, eq, 'main_hand').locator('.animus-chest-count').textContent(), '3', `${tag}: the stack count shows`);
  let json = await codeOf(page);
  assert.deepEqual(json.equipment, {main_hand: {id: 'minecraft:iron_sword', count: 3}, head: {id: 'minecraft:iron_helmet'}, off_hand: {id: 'minecraft:shield'}});
  await shot(page, `${tag}-01-equipment`);

  // 2. Drag, right click, undo, arrows.
  look = await part(page, 'appearance');
  await drag(page, slot(look, eq, 'main_hand'), slot(look, eq, 'off_hand'));
  json = await codeOf(page);
  assert.deepEqual(json.equipment.off_hand, {id: 'minecraft:iron_sword', count: 3}, `${tag}: the drag moved the sword`);
  assert.deepEqual(json.equipment.main_hand, {id: 'minecraft:shield'}, `${tag}: and the shield back`);
  look = await part(page, 'appearance');
  await slot(look, eq, 'head').click({button: 'right'});
  await settle(page);
  assert.equal((await codeOf(page)).equipment.head, undefined, `${tag}: right click empties`);
  await topbarAction(page, 'Undo');
  await settle(page);
  // Edits of one field in a row share an undo step, as typing does.
  const undone = (await codeOf(page)).equipment;
  assert.deepEqual(undone.head, {id: 'minecraft:iron_helmet'}, `${tag}: one Undo brings it back`);
  look = await part(page, 'appearance');
  await slot(look, eq, 'head').focus();
  await page.keyboard.press('ArrowDown');
  assert.equal(await page.evaluate(() => document.activeElement.getAttribute('data-slot')), 'chest', `${tag}: ArrowDown`);
  await page.keyboard.press('ArrowRight');
  assert.equal(await page.evaluate(() => document.activeElement.getAttribute('data-slot')), 'off_hand', `${tag}: ArrowRight`);
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => document.activeElement && document.activeElement.id === 'animus-equipment-item');
  await noSidewaysScroll(page, `${tag} equipment`);

  // 3. A llama shows its body slot and the set slots it cannot draw.
  const body = await part(page, 'body');
  const type = body.getByLabel('Body type', {exact: true});
  await type.fill('minecraft:llama');
  await type.press('Enter');
  await page.keyboard.press('Escape');
  await body.locator(':scope > summary', {hasText: 'llama'}).waitFor();
  look = await part(page, 'appearance');
  await look.locator(`[data-slot-grid="${eq}"] [data-slot="body"]`).waitFor();
  const hands = ['off_hand', 'main_hand'].filter((key) => undone[key]);
  assert.deepEqual(await keys(look, eq), ['head', 'body', ...hands], `${tag}: llama slots`);
  // Flagged as warnings here; the definition's own check makes them errors.
  assert.equal(await look.locator('.animus-item-slot.is-warning, .animus-item-slot.is-error').count(), hands.length + 1, `${tag}: the set player slots are flagged`);
  await look.getByText('minecraft:llama bodies do not show', {exact: false}).waitFor();
  await slot(look, eq, 'body').click();
  assert.equal(await look.getByLabel('equipment.body', {exact: true}).getAttribute('placeholder'), 'minecraft:white_carpet', `${tag}: llama body placeholder`);
  await shot(page, `${tag}-02-llama`);
  await noSidewaysScroll(page, `${tag} llama`);
  await topbarAction(page, 'Undo');
  await settle(page);
  assert.equal((await codeOf(page)).type, 'minecraft:player');

  // 4. Inventory.
  let behaviour = await part(page, 'behaviour');
  const inv = 'animus-npc-inventory';
  assert.equal(await slotsOf(behaviour, inv).count(), 9, `${tag}: one row of 9`);
  await slot(behaviour, inv, '3').scrollIntoViewIfNeeded();
  await slot(behaviour, inv, '3').click();
  const item = behaviour.getByLabel('Slot 3 item', {exact: true});
  await item.fill('minecraft:arrow');
  await item.blur();
  await settle(page);
  behaviour = await part(page, 'behaviour');
  await drag(page, slot(behaviour, inv, '0'), slot(behaviour, inv, '5'));
  behaviour = await part(page, 'behaviour');
  await slot(behaviour, inv, '3').click({button: 'right'});
  await settle(page);
  json = await codeOf(page);
  assert.deepEqual(json.inventory, {size: 9, slots: {5: {id: 'minecraft:bread', count: 5}}}, `${tag}: inventory edits`);
  behaviour = await part(page, 'behaviour');
  await choose(behaviour.locator('button#animus-npc-inventory-size'), '18');
  await settle(page);
  behaviour = await part(page, 'behaviour');
  assert.equal(await slotsOf(behaviour, inv).count(), 18, `${tag}: two rows`);
  assert.equal((await codeOf(page)).inventory.size, 18);
  behaviour = await part(page, 'behaviour');
  await slot(behaviour, inv, '5').scrollIntoViewIfNeeded();
  await shot(page, `${tag}-03-inventory`);
  await noSidewaysScroll(page, `${tag} inventory`);

  // 5. Hire items.
  let modules = await part(page, 'modules');
  const hire = 'animus-hire-items';
  assert.equal(await slotsOf(modules, hire).count(), 6, `${tag}: six hire slots`);
  await slot(modules, hire, '5').scrollIntoViewIfNeeded();
  await slot(modules, hire, '5').click();
  const hireItem = modules.getByLabel('Hire item 2', {exact: true});
  await hireItem.fill('minecraft:gold_ingot');
  await hireItem.blur();
  await settle(page);
  modules = await part(page, 'modules');
  await drag(page, slot(modules, hire, '0'), slot(modules, hire, '1'));
  json = await codeOf(page);
  assert.deepEqual(json.modules['animus:hire'], {enabled: true, price: 10, items: [{id: 'minecraft:gold_ingot'}, {id: 'minecraft:emerald', count: 3}]}, `${tag}: hire items`);
  modules = await part(page, 'modules');
  await slot(modules, hire, '1').scrollIntoViewIfNeeded();
  await shot(page, `${tag}-04-hire`);
  await noSidewaysScroll(page, `${tag} hire`);

  assert.deepEqual(errors, [], `${tag}: console errors`);
  await context.close();
}

(async () => {
  const folder = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'animus-inventory-')), 'data');
  writeFixture(folder);
  const browser = await chromium.launch({headless: true});
  try {
    await run(browser, folder, {width: 1440, height: 900}, 'dark', 'desktop-dark');
    await run(browser, folder, {width: 1440, height: 900}, 'light', 'desktop-light');
    await run(browser, folder, {width: 390, height: 844}, 'dark', 'phone-dark');
    await run(browser, folder, {width: 390, height: 844}, 'light', 'phone-light');
    console.log('inventory_browser: ok');
  } finally {
    await browser.close();
    fs.rmSync(path.dirname(folder), {recursive: true, force: true});
  }
})().catch((error) => { console.error(error); process.exit(1); });
