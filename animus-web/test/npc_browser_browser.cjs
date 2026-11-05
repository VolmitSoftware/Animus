// Opens test/fixtures/npc_browser in the built site at desktop and phone
// widths and checks the NPC browser (search, folder and tag filters, a bulk
// tag and folder move undone in one step each, a bulk module switch), the
// route editor (a point added on the grid and one dragged), the combat
// editor (an out-of-range value flagged, then fixed), the equipment and skin
// editors and the conversation view. Then, against the fake server loaded
// with the same fixture, it spawns and despawns a selection on the server.
// No console errors and no sideways scrolling at either width.
//
//   python3 -m http.server 8098 --bind 127.0.0.1 --directory build/jaspr
//   dart run tool/fake_server.dart --data=test/fixtures/npc_browser &
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/npc_browser_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const folder = path.resolve(__dirname, 'fixtures/npc_browser');
const site = process.env.ANIMUS_WEB_URL || 'http://127.0.0.1:8098';
const fake = process.env.ANIMUS_FAKE_URL || 'http://127.0.0.1:9797';
const shots = process.env.ANIMUS_SCREENSHOTS;

async function shot(page, name) {
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: true});
}

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

function watch(page) {
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  return errors;
}

async function noSidewaysScroll(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  assert.ok(overflow <= 1, `${where}: the page scrolls sideways by ${overflow}px`);
}

async function rows(page) {
  return page.locator('.animus-npc-row[data-npc]').evaluateAll((elements) => elements.map((element) => element.getAttribute('data-npc')));
}

async function waitRows(page, expected) {
  await page.waitForFunction((want) => {
    const got = [...document.querySelectorAll('.animus-npc-row[data-npc]')].map((element) => element.getAttribute('data-npc'));
    return JSON.stringify(got) === JSON.stringify(want);
  }, expected);
}

async function search(page, text) {
  const input = page.getByLabel('Search NPCs', {exact: true});
  await input.fill(text);
}

async function codeOf(page) {
  await page.getByRole('tab', {name: 'Code'}).click();
  const text = await page.locator('.animus-code textarea').inputValue();
  await page.getByRole('tab', {name: 'Visual'}).click();
  return JSON.parse(text);
}

async function tagsOf(page, key) {
  return page.locator(`.animus-npc-row[data-npc="${key}"] .animus-npc-tag`).evaluateAll((elements) => elements.map((element) => element.getAttribute('data-tag')));
}

async function select(page, key) {
  await page.getByRole('checkbox', {name: `Select ${key}`, exact: true}).check();
}

async function local(browser, viewport, tag) {
  const context = await browser.newContext({viewport});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = watch(page);
  await page.goto(`${site}/`);
  await page.getByRole('heading', {name: 'Open an Animus data folder'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(folder);
  await page.getByRole('heading', {name: 'Every document passes the shared schemas'}).waitFor();

  // Browser: list, search, folder and tag filters.
  await page.locator('.animus-tree-browse').click();
  const browserPanel = page.getByRole('region', {name: 'NPC browser'});
  await browserPanel.waitFor();
  const all = ['harbor/dockhand', 'harbor/ferryman', 'town/baker', 'town/guard-north', 'town/guard-south', 'wild/golem-keeper'];
  await waitRows(page, all);
  await shot(page, `${tag}-01-browser`);
  await noSidewaysScroll(page, `${tag} browser`);
  await search(page, 'guard');
  await waitRows(page, ['town/guard-north', 'town/guard-south']);
  await search(page, 'tag:guard -tag:patrol');
  await waitRows(page, ['town/guard-south']);
  await search(page, 'has:combat');
  await waitRows(page, ['town/guard-north', 'town/guard-south']);
  await search(page, '');
  await page.getByRole('button', {name: 'Folder harbor'}).click();
  await waitRows(page, ['harbor/dockhand', 'harbor/ferryman']);
  assert.equal(await page.getByLabel('Search NPCs', {exact: true}).inputValue(), 'folder:harbor');
  await page.getByRole('button', {name: 'Folder harbor'}).click();
  await waitRows(page, all);
  await page.locator('.animus-npc-folders').getByRole('button', {name: /^quest 1$/}).click();
  await waitRows(page, ['harbor/ferryman']);
  await shot(page, `${tag}-02-search`);
  await search(page, '');
  await waitRows(page, all);

  // Bulk: tag three NPCs, move two to a new folder, undo each in one step.
  await select(page, 'town/guard-north');
  await select(page, 'town/guard-south');
  await select(page, 'town/baker');
  const bulk = page.getByRole('toolbar', {name: 'Bulk edit'});
  await bulk.getByText('3 NPCs selected').waitFor();
  await bulk.getByLabel('Tag', {exact: true}).fill('night-watch');
  await bulk.getByRole('button', {name: 'Add tag'}).click();
  await page.locator('.animus-npc-row[data-npc="town/baker"] .animus-npc-tag[data-tag="night-watch"]').waitFor();
  assert.deepEqual(await tagsOf(page, 'town/guard-north'), ['guard', 'patrol', 'night-watch']);
  assert.deepEqual(await tagsOf(page, 'harbor/ferryman'), ['quest'], 'unselected NPCs keep their tags');
  await page.getByRole('checkbox', {name: 'Select town/baker', exact: true}).uncheck();
  await bulk.getByText('2 NPCs selected').waitFor();
  await bulk.getByLabel('Folder', {exact: true}).fill('watch');
  await bulk.getByRole('button', {name: 'Move to folder'}).click();
  await waitRows(page, ['harbor/dockhand', 'harbor/ferryman', 'town/baker', 'watch/guard-north', 'watch/guard-south', 'wild/golem-keeper']);
  await shot(page, `${tag}-03-bulk`);
  await page.getByRole('button', {name: 'Undo'}).click();
  await waitRows(page, all);
  assert.deepEqual(await tagsOf(page, 'town/guard-south'), ['guard', 'night-watch'], 'undoing the move keeps the tags');
  await page.getByRole('button', {name: 'Undo'}).click();
  await page.waitForFunction(() => !document.querySelector('.animus-npc-tag[data-tag="night-watch"]'));
  assert.deepEqual(await tagsOf(page, 'town/guard-north'), ['guard', 'patrol']);

  // Bulk module switch: combat off on both guards, then undo.
  await page.getByRole('checkbox', {name: 'Select every NPC shown'}).check();
  await page.getByRole('toolbar', {name: 'Bulk edit'}).getByText('6 NPCs selected').waitFor();
  await page.getByRole('checkbox', {name: 'Select every NPC shown'}).uncheck();
  await select(page, 'town/guard-north');
  await select(page, 'town/guard-south');
  await page.getByRole('toolbar', {name: 'Bulk edit'}).locator('select', {has: page.locator('option', {hasText: 'Pick a module'})}).selectOption('animus:combat');
  await page.getByRole('toolbar', {name: 'Bulk edit'}).getByRole('button', {name: 'Disable', exact: true}).click();
  await page.waitForFunction(() => !document.querySelector('.animus-npc-row [data-feature="combat"]'));
  await shot(page, `${tag}-04-bulk-module`);
  await page.getByRole('button', {name: 'Undo'}).click();
  await page.locator('.animus-npc-row[data-npc="town/guard-north"] [data-feature="combat"]').waitFor();
  await noSidewaysScroll(page, `${tag} bulk`);

  // Route: add a point on the grid, drag point 0, check the JSON.
  await page.locator('.animus-npc-row[data-npc="town/guard-north"] .animus-npc-name a').click();
  const inspector = page.getByRole('region', {name: 'NPC inspector'});
  await inspector.waitFor();
  const route = inspector.getByRole('group', {name: 'Route editor'});
  const grid = route.locator('svg.animus-route-grid');
  await grid.scrollIntoViewIfNeeded();
  assert.equal(await grid.locator('.animus-route-node').count(), 4);
  const box = await grid.boundingBox();
  const bounds = await grid.evaluate((svg) => ({x: Number(svg.dataset.minX), z: Number(svg.dataset.minZ), size: Number(svg.dataset.size)}));
  const toScreen = (x, z) => ({x: box.x + (x - bounds.x) / bounds.size * box.width, y: box.y + (z - bounds.z) / bounds.size * box.height});
  const added = toScreen(8, 0);
  await page.mouse.click(added.x, added.y);
  await grid.locator('.animus-route-node').nth(4).waitFor();
  const from = toScreen(4, -6);
  const to = toScreen(2, -8);
  await page.mouse.move(from.x, from.y);
  await page.mouse.down();
  await page.mouse.move((from.x + to.x) / 2, (from.y + to.y) / 2, {steps: 4});
  await page.mouse.move(to.x, to.y, {steps: 4});
  await page.mouse.up();
  await page.waitForTimeout(300);
  await shot(page, `${tag}-05-route`);
  let json = await codeOf(page);
  const points = json.modules['animus:navigation'].points;
  assert.equal(points.length, 5);
  assert.deepEqual(points[4], {x: 8, y: 64, z: 0});
  assert.deepEqual([points[0].x, points[0].z], [2, -8]);
  assert.equal(points[1].waitTicks, 60, 'other point fields survive');

  // Combat: an out-of-range reach is flagged at its field, then fixed.
  const combat = inspector.getByRole('region', {name: 'Combat editor'}).or(inspector.locator('.animus-combat-editor')).first();
  await combat.scrollIntoViewIfNeeded();
  const reach = combat.getByLabel('melee.reach', {exact: true});
  await reach.fill('9');
  await reach.blur();
  await combat.getByText(/melee\.reach/).first().waitFor();
  await page.locator('.animus-diagnostics li', {hasText: 'melee.reach'}).first().waitFor();
  await shot(page, `${tag}-06-combat-invalid`);
  await reach.fill('4.5');
  await reach.blur();
  await page.waitForFunction(() => ![...document.querySelectorAll('.animus-diagnostics li')].some((item) => item.textContent.includes('melee.reach')));
  json = await codeOf(page);
  assert.equal(json.modules['animus:combat'].melee.reach, 4.5);
  assert.equal(json.modules['animus:combat'].melee.damage, 5, 'other combat fields survive');

  // Equipment and skin.
  const offHand = inspector.getByLabel('equipment.off_hand', {exact: true});
  await offHand.scrollIntoViewIfNeeded();
  await offHand.fill('minecraft:shield');
  await offHand.blur();
  await inspector.getByRole('radio', {name: /^Skin dc33741f/}).click();
  await page.waitForTimeout(300);
  json = await codeOf(page);
  assert.deepEqual(json.equipment.off_hand, {id: 'minecraft:shield'});
  assert.equal(json.equipment.main_hand.id, 'minecraft:iron_sword');
  assert.equal(json.skin, 'dc33741fb113d5f1f3876ba48fb235adefc63628862c1e659f4061d8c7c18717');
  await page.locator('.animus-npc-visual canvas').first().waitFor();
  await shot(page, `${tag}-07-inspector`);
  await noSidewaysScroll(page, `${tag} inspector`);

  // Conversation of the ferryman's behavior.
  await page.goto(`${site}/${await page.evaluate(() => location.hash.replace(/\/npc\/.*$/, '/behavior/harbor%3Aferryman'))}`);
  const conversation = page.getByRole('tree', {name: 'Conversation'});
  await conversation.waitFor();
  assert.equal(await conversation.getByLabel('Prompt ferry', {exact: true}).inputValue(), 'Need a crossing?');
  assert.equal(await conversation.getByLabel('Prompt fare', {exact: true}).inputValue(), 'Pay now or later?');
  await conversation.getByText('trigger pay runs paid').waitFor();
  await conversation.getByLabel('Answer no of ferry', {exact: true}).fill('Not today');
  await page.waitForTimeout(300);
  await shot(page, `${tag}-08-dialogue`);
  json = await codeOf(page);
  assert.equal(json.routines.main.steps[2].arguments.options[1].text, 'Not today');
  await noSidewaysScroll(page, `${tag} dialogue`);

  assert.deepEqual(errors, [], `${tag}: console errors`);
  await context.close();
}

async function server(browser, viewport, tag) {
  await control('reset', {});
  const {code} = await control('pair?paths=direct&scopes=spawn', {});
  const context = await browser.newContext({viewport});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = watch(page);
  await page.goto(`${site}/#/pair/${code}`);
  await page.getByRole('heading', {name: /Pair with/}).waitFor();
  await page.getByRole('button', {name: 'Pair'}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await page.getByText(/documents on the server/).waitFor();
  for (const id of ['harbor/dockhand', 'harbor/ferryman']) await page.getByRole('checkbox', {name: `npc ${id}`}).check();
  await page.getByRole('button', {name: 'Open 2'}).click();
  await page.waitForFunction(() => decodeURIComponent(location.hash).includes('/npc/'));
  await page.locator('.animus-tree-browse').click();
  await waitRows(page, ['harbor/dockhand', 'harbor/ferryman']);
  await select(page, 'harbor/dockhand');
  await select(page, 'harbor/ferryman');
  const bulk = page.getByRole('toolbar', {name: 'Bulk edit'});
  await bulk.getByRole('button', {name: 'Spawn on server', exact: true}).click();
  await page.getByTestId('live-last').filter({hasText: 'Spawned 2 NPCs on the server.'}).waitFor();
  await bulk.getByRole('button', {name: 'Despawn on server', exact: true}).click();
  await page.getByTestId('live-last').filter({hasText: 'Despawned 2 NPCs on the server.'}).waitFor();
  await shot(page, `${tag}-09-server-spawn`);
  await noSidewaysScroll(page, `${tag} server`);
  assert.deepEqual(errors, [], `${tag}: console errors on the server workspace`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch();
  try {
    for (const [viewport, tag] of [[{width: 1440, height: 1000}, 'desktop'], [{width: 390, height: 844}, 'phone']]) {
      await local(browser, viewport, tag);
      await server(browser, viewport, tag);
      console.log(`${tag}: ok`);
    }
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
