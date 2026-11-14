// Opens test/fixtures/typed_forms (an NPC with combat, a pack with a module
// configuration, the presets document, a menu with named lists and a list
// component, a chest GUI with an inventory action and a shop) and checks the
// typed forms that replaced the JSON boxes, at 1440 and 390 px in both
// themes: the Advanced Combat tab's rare rules typed from the schema, the
// return point on the position grid (keyboard nudge and a grid click), the
// Victims drop loot switch, a shop entry picked by item search, the ranged potion effect, the
// pack module configuration, preset values, the inventory
// action's arguments, and the call, list and chest GUI pickers. Every edit is
// read back from the Code view. No console errors, no sideways scrolling, no
// raw JSON boxes outside an Advanced toggle. It needs no fake server.
//
//   python3 tool/serve_site.py --port <port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/typed_forms_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openFromSidebar, showView, setMode, showPreview} = require('./support/shell.cjs');
const {npcTab} = require('./support/npc_tabs.cjs');
const {chooseAndWait} = require('./support/select.cjs');

const fixture = path.resolve(__dirname, 'fixtures/typed_forms');
const shots = process.env.ANIMUS_SCREENSHOTS;

async function shot(page, name) {
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`)});
}

async function codeOf(page) {
  await showView(page, 'code');
  const text = await page.locator('.animus-code textarea').inputValue();
  await showView(page, 'visual');
  return JSON.parse(text);
}

async function open(page, sidebarText) {
  await openFromSidebar(page, sidebarText);
  await page.locator('.ui-page-header').waitFor();
}

async function settle(page) {
  await page.waitForTimeout(250);
}

async function noSideways(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 0, `${where} scrolls sideways by ${overflow}px`);
}

/// No JSON text box is showing: they only appear behind an Advanced toggle.
async function noJsonBoxes(scope, where) {
  assert.equal(await scope.locator('textarea.animus-kept-json').count(), 0, `${where} shows a raw JSON box`);
}

/** Types [text] into a suggestion field and picks the option [value]. */
async function suggest(page, field, text, value) {
  await field.fill(text);
  const option = page.getByRole('option').filter({hasText: value}).first();
  await option.waitFor();
  await option.click();
  await settle(page);
}

/** Opens a suggestion field's list without typing and returns its option texts. */
async function suggestions(page, field) {
  await field.focus();
  await field.press('ArrowDown');
  const listbox = page.locator(`[id="${await field.getAttribute('aria-controls')}"]`);
  await listbox.waitFor();
  const texts = await listbox.getByRole('option').allInnerTexts();
  await field.press('Escape');
  return texts;
}

/** Clicks the position grid [grid] at block [x], [z]. */
async function clickGrid(grid, x, z) {
  const svg = grid.locator('svg.animus-route-grid');
  await svg.scrollIntoViewIfNeeded();
  const box = await svg.boundingBox();
  const minX = Number(await svg.getAttribute('data-min-x'));
  const minZ = Number(await svg.getAttribute('data-min-z'));
  const size = Number(await svg.getAttribute('data-size'));
  await svg.click({position: {x: ((x - minX) / size) * box.width, y: ((z - minZ) / size) * box.height}});
}

async function combat(page, tag) {
  await setMode(page, 'advanced');
  await open(page, 'Warden');
  await page.locator('.animus-npc-tabs').waitFor();
  /** A section of the Advanced Combat tab, opened (closed until it holds a value). */
  const section = async (id) => {
    const part = (await npcTab(page, 'Combat')).locator(`[data-combat-section="${id}"]`).first();
    const details = part.locator('details').first();
    if (!(await details.evaluate((element) => element.open))) await details.locator(':scope > summary').click();
    return part;
  };
  await noJsonBoxes(await npcTab(page, 'Combat'), `${tag} combat`);

  // Rare rules are typed from the NPC schema under More rules: patterns.names is a list.
  const targets = await section('targets');
  const pattern = targets.getByLabel('Mob name patterns 1', {exact: true});
  if (!(await pattern.isVisible().catch(() => false))) await targets.locator('.ui-more > details > summary').first().click();
  assert.equal(await pattern.inputValue(), '^Bandit', 'patterns.names is a typed list');
  await pattern.fill('^Thief');
  await settle(page);

  // The return point: nudged from the keyboard, then placed by a grid click.
  let avoidance = await section('avoidance');
  const mark = avoidance.locator('.animus-position-grid').first().getByRole('button', {name: /^Return point at 0, 64, 0/});
  await mark.focus();
  await page.keyboard.press('Shift+ArrowRight');
  await settle(page);
  let json = await codeOf(page);
  let module = json.modules['animus:combat'];
  assert.equal(module.targets.patterns.names[0], '^Thief');
  assert.deepEqual(module.avoidance.returnPoint, {world: 'world', x: 1, y: 64, z: 0});
  avoidance = await section('avoidance');
  await clickGrid(avoidance.locator('.animus-position-grid').first(), 3, 2);
  await settle(page);
  json = await codeOf(page);
  assert.deepEqual(json.modules['animus:combat'].avoidance.returnPoint, {world: 'world', x: 3, y: 64, z: 2}, `${tag} grid click`);

  // Victims drop loot is on by default; turning it off writes false.
  await (await section('melee')).getByRole('switch', {name: 'Victims drop loot'}).click();
  await settle(page);
  await (await section('ranged')).getByLabel('Strength', {exact: true}).fill('3');
  await settle(page);
  await shot(page, `${tag}-combat`);
  await noSideways(page, `${tag} combat`);
  json = await codeOf(page);
  module = json.modules['animus:combat'];
  assert.equal(module.victimDrops, false, `${tag} victimDrops off`);
  assert.deepEqual(module.ranged.effect, {id: 'minecraft:poison', amplifier: 3});
}

async function pack(page, tag) {
  await open(page, 'Economy');
  const module = page.locator('[data-module="bank"]');
  await module.waitFor();
  await noJsonBoxes(module, `${tag} pack module`);
  const config = module.getByRole('group', {name: 'Configuration'});
  await config.getByLabel('currency', {exact: true}).fill('gems');
  await settle(page);
  await config.getByRole('button', {name: 'Edit as JSON'}).click();
  await config.locator('textarea').waitFor();
  await config.getByRole('button', {name: 'Edit as JSON'}).click();
  await shot(page, `${tag}-pack`);
  const json = await codeOf(page);
  assert.deepEqual(json.modules.bank, {required: false, configuration: {currency: 'gems', startingBalance: 10}});
  await noSideways(page, `${tag} pack`);
}

async function presets(page, tag) {
  await open(page, 'presets');
  const editor = page.getByRole('region', {name: 'Presets'});
  await editor.waitFor();
  await noJsonBoxes(editor, `${tag} presets`);
  await chooseAndWait(editor.getByLabel('For', {exact: true}), 'menus');
  await editor.locator('summary', {hasText: /^\s*wide\b/}).click();
  const values = editor.getByRole('group', {name: 'Values of wide'});
  await values.getByLabel('Max distance', {exact: true}).fill('9');
  await settle(page);
  await chooseAndWait(editor.getByLabel('For', {exact: true}), 'inventories');
  const defaults = editor.getByRole('group', {name: 'Defaults'});
  await defaults.getByLabel('Title', {exact: true}).fill('&8Market');
  await settle(page);
  await shot(page, `${tag}-presets`);
  const json = await codeOf(page);
  assert.equal(json.presets.menus.wide.values.maxDistance, 9);
  assert.deepEqual(json.defaults.inventories, {title: '&8Market', closeOnTeleport: true});
  await noSideways(page, `${tag} presets`);
}

async function chest(page, tag) {
  await open(page, 'town:board');
  await page.locator('[data-slot="29"]').click();
  const form = page.getByRole('region', {name: 'Chest GUI'});
  const args = form.getByRole('group', {name: 'Args'}).first();
  await args.waitFor();
  await noJsonBoxes(form, `${tag} chest GUI actions`);
  await args.getByLabel('Argument page', {exact: true}).fill('3');
  await settle(page);
  const target = form.getByLabel('ID', {exact: true}).last();
  const offered = await suggestions(page, target);
  assert.ok(offered.some((text) => text.includes('town:board')), `chest GUI ids offered: ${offered}`);
  assert.ok(offered.some((text) => text.includes('New chest GUI')), 'the + New row is offered');
  await shot(page, `${tag}-chest-actions`);
  const json = await codeOf(page);
  assert.deepEqual(json.keys.P.actions[1].args, {page: '3'});
  await noSideways(page, `${tag} chest GUI`);
}

async function menu(page, tag) {
  await open(page, 'town:hub');
  const form = page.getByRole('region', {name: 'Menu'});
  await form.locator('summary', {hasText: /market, Button/}).click();
  await noJsonBoxes(form, `${tag} menu actions`);
  const call = form.getByLabel('Action', {exact: true}).first();
  const named = await suggestions(page, call);
  assert.ok(named.some((text) => text.includes('bye')), `named lists offered: ${named}`);
  await suggest(page, call, 'bye', 'bye');
  const lists = await suggestions(page, form.getByLabel('List', {exact: true}).first());
  assert.ok(lists.some((text) => text.includes('goods')), `list components offered: ${lists}`);
  await shot(page, `${tag}-menu-actions`);
  const json = await codeOf(page);
  assert.equal(json.components[1].data.actions[0].action, 'bye');
  await noSideways(page, `${tag} menu`);
}

async function shop(page, tag) {
  await open(page, 'town:market');
  const form = page.getByRole('region', {name: 'Shop'});
  await showPreview(page);
  await page.getByRole('region', {name: 'Preview'}).locator('[data-slot="10"]').click();
  await suggest(page, form.getByLabel('Gives item', {exact: true}), 'golden apple', 'Golden apple');
  await form.locator('.animus-item-picker img').first().waitFor();
  await shot(page, `${tag}-shop-item`);
  const json = await codeOf(page);
  assert.equal(json.pages[0].buy['10'].item.id, 'minecraft:golden_apple');
  assert.equal(json.pages[0].buy['10'].item.count, 4, 'the count survives');
  await noSideways(page, `${tag} shop`);
}

async function run(browser, viewport, theme) {
  const tag = `${viewport.width}-${theme}`;
  const context = await browser.newContext({viewport});
  await context.addInitScript((mode) => window.localStorage.setItem('animus.theme', mode), theme);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  await page.goto(process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to your own served site'); })());
  await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(fixture);
  await page.getByRole('heading', {name: 'typed_forms'}).waitFor();
  assert.ok(await page.evaluate((mode) => document.documentElement.classList.contains(mode), theme), `${theme} theme is on`);
  for (const step of [combat, pack, presets, chest, menu, shop]) {
    await step(page, tag);
  }
  assert.deepEqual(errors, [], `${tag} console errors`);
  await context.close();
  console.log(`ok ${tag}`);
}

(async () => {
  const browser = await chromium.launch({headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
  try {
    for (const viewport of [{width: 1440, height: 1000}, {width: 390, height: 844}]) {
      for (const theme of ['dark', 'light']) await run(browser, viewport, theme);
    }
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
