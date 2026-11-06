// Opens test/fixtures/npc_browser in the built site at desktop and phone
// widths, in the dark and the light theme, and checks the NPC browser
// (search, the folder tree by mouse and keyboard, tag filters, empty
// states, bulk tag and folder edits undone in one step each, a bulk module
// switch, the delete confirm and Escape), the NPC inspector (its parts and
// their summaries, pose, owner, hologram lines with a preview, armor stand
// parts and angles, the rig picker), the route editor (a point added on the
// grid, one dragged, one nudged and one added and removed from the
// keyboard), the combat editor (an out-of-range value flagged, then fixed),
// the equipment and skin editors, the skin document's summary and the
// conversation view. Then, against the fake server loaded with the same
// fixture, it spawns and despawns a selection on the server (the despawn
// asks first) and shows that a token without the spawn scope cannot. No
// console errors and no sideways scrolling anywhere.
//
//   python3 -m http.server <WEB_PORT> --bind 127.0.0.1 --directory build/jaspr &
//   dart run tool/fake_server.dart --port=<FAKE_PORT> --origin=http://127.0.0.1:<WEB_PORT> --data=test/fixtures/npc_browser &
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<WEB_PORT> ANIMUS_FAKE_URL=http://127.0.0.1:<FAKE_PORT> \
//     ANIMUS_SCREENSHOTS=<scratch folder> node test/npc_browser_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const folder = path.resolve(__dirname, 'fixtures/npc_browser');
const site = process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })();
const fake = process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })();
const shots = process.env.ANIMUS_SCREENSHOTS;
const skinHash = 'dc33741fb113d5f1f3876ba48fb235adefc63628862c1e659f4061d8c7c18717';

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
    // The brand lane ships the pairing page's mark asset; until then its 404 is expected.
    if (message.type() === 'error' && !message.location().url.includes('animus-mark')) errors.push(message.text());
  });
  return errors;
}

async function open(browser, viewport, theme) {
  const context = await browser.newContext({viewport});
  await context.addInitScript((mode) => window.localStorage.setItem('animus.theme', mode), theme);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  return {context, page, errors: watch(page)};
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

async function settle(page) {
  await page.waitForTimeout(250);
}

async function tagsOf(page, key) {
  return page.locator(`.animus-npc-row[data-npc="${key}"] .animus-npc-tag`).evaluateAll((elements) => elements.map((element) => element.getAttribute('data-tag')));
}

async function select(page, key) {
  await page.getByRole('checkbox', {name: `Select ${key}`, exact: true}).check();
}

/** Opens an inspector part (closed by default on phones) and returns it. */
async function part(inspector, id) {
  const details = inspector.locator(`details[data-section="${id}"]`);
  if (!(await details.evaluate((element) => element.open))) await details.locator('summary').first().click();
  return details;
}

async function focusedLabel(page) {
  return page.evaluate(() => document.activeElement?.getAttribute('aria-label'));
}

async function local(browser, viewport, tag, theme) {
  const {context, page, errors} = await open(browser, viewport, theme);
  const phone = viewport.width < 600;
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

  // A search that matches nothing names it and offers to clear it.
  await search(page, 'nobody-here');
  await browserPanel.getByText('No NPC matches "nobody-here".').waitFor();
  await shot(page, `${tag}-02-no-match`);
  await browserPanel.locator('.animus-npc-empty').getByRole('button', {name: 'Clear search'}).click();
  await waitRows(page, all);

  // Folder tree by mouse.
  const tree = page.getByRole('tree', {name: 'Folders'});
  await tree.getByRole('treeitem', {name: /^Folder harbor/}).click();
  await waitRows(page, ['harbor/dockhand', 'harbor/ferryman']);
  assert.equal(await page.getByLabel('Search NPCs', {exact: true}).inputValue(), 'folder:harbor');
  assert.equal(await tree.getByRole('treeitem', {name: /^Folder harbor/}).getAttribute('aria-selected'), 'true');
  await tree.getByRole('treeitem', {name: /^Folder harbor/}).click();
  await waitRows(page, all);

  // Folder tree by keyboard: one tab stop, arrows, Home, End and Enter.
  assert.equal(await tree.locator('[role="treeitem"][tabindex="0"]').count(), 1, 'the tree has one tab stop');
  await tree.getByRole('treeitem', {name: /^All folders/}).focus();
  await page.keyboard.press('ArrowDown');
  assert.match(await focusedLabel(page), /^Folder harbor/);
  await page.keyboard.press('End');
  assert.match(await focusedLabel(page), /^Folder wild/);
  await page.keyboard.press('Enter');
  await waitRows(page, ['wild/golem-keeper']);
  await page.keyboard.press('Home');
  assert.match(await focusedLabel(page), /^All folders/);
  await page.keyboard.press('Enter');
  await waitRows(page, all);
  assert.equal(await tree.locator('[role="treeitem"][tabindex="0"]').count(), 1);

  await page.locator('.animus-npc-folders').getByRole('button', {name: /^quest 1$/}).click();
  await waitRows(page, ['harbor/ferryman']);
  await shot(page, `${tag}-03-search`);
  await search(page, '');
  await waitRows(page, all);

  // Bulk: tag three NPCs, move two to a new folder, undo each in one step.
  await select(page, 'town/guard-north');
  await select(page, 'town/guard-south');
  await select(page, 'town/baker');
  const bulk = page.getByRole('group', {name: 'Bulk edit'});
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
  await shot(page, `${tag}-04-bulk`);
  await page.getByRole('button', {name: 'Undo'}).click();
  await waitRows(page, all);
  assert.deepEqual(await tagsOf(page, 'town/guard-south'), ['guard', 'night-watch'], 'undoing the move keeps the tags');
  await page.getByRole('button', {name: 'Undo'}).click();
  await page.waitForFunction(() => !document.querySelector('.animus-npc-tag[data-tag="night-watch"]'));
  assert.deepEqual(await tagsOf(page, 'town/guard-north'), ['guard', 'patrol']);

  // Bulk module switch: combat off on both guards, then undo.
  await page.getByRole('checkbox', {name: 'Select every NPC shown'}).check();
  await bulk.getByText('6 NPCs selected').waitFor();
  await page.getByRole('checkbox', {name: 'Select every NPC shown'}).uncheck();
  await select(page, 'town/guard-north');
  await select(page, 'town/guard-south');
  await bulk.getByLabel('Module to change').selectOption('animus:combat');
  await bulk.getByRole('button', {name: 'Disable', exact: true}).click();
  await page.waitForFunction(() => !document.querySelector('.animus-npc-row [data-feature="combat"]'));
  await shot(page, `${tag}-05-bulk-module`);
  await page.getByRole('button', {name: 'Undo'}).click();
  await page.locator('.animus-npc-row[data-npc="town/guard-north"] [data-feature="combat"]').waitFor();

  // Bulk delete asks first; Escape keeps the NPCs.
  await bulk.getByRole('button', {name: 'Delete', exact: true}).click();
  await bulk.getByRole('button', {name: 'Delete 2 NPCs'}).waitFor();
  await shot(page, `${tag}-06-delete-confirm`);
  await page.keyboard.press('Escape');
  await bulk.getByRole('button', {name: 'Delete', exact: true}).waitFor();
  assert.equal(await bulk.getByRole('button', {name: 'Delete 2 NPCs'}).count(), 0);
  await waitRows(page, all);
  await noSidewaysScroll(page, `${tag} bulk`);
  await page.getByRole('button', {name: 'Clear selection'}).click();

  // Inspector parts: on phones every part but identity starts closed.
  await page.locator('.animus-npc-row[data-npc="town/guard-north"] .animus-npc-name a').click();
  const inspector = page.getByRole('region', {name: 'NPC inspector'});
  await inspector.waitFor();
  const openParts = await inspector.locator('details.animus-npc-section').evaluateAll((list) => list.map((element) => [element.dataset.section, element.open]));
  if (phone) assert.deepEqual(openParts.filter(([, isOpen]) => isOpen).map(([id]) => id), ['identity'], 'phones open only identity');
  else assert.ok(openParts.every(([, isOpen]) => isOpen), 'desktops open every part');
  if (phone) await inspector.locator('details[data-section="behaviour"] .animus-npc-section-summary').getByText(/1 behavior|0 behaviors|waypoints/).waitFor();

  // Route: add a point on the grid, drag point 0, then from the keyboard.
  const behaviour = await part(inspector, 'behaviour');
  const route = behaviour.getByRole('group', {name: 'Route editor'});
  const grid = route.locator('svg.animus-route-grid');
  await grid.scrollIntoViewIfNeeded();
  assert.equal(await grid.locator('.animus-route-node').count(), 4);
  // Where a block sits on screen now; the grid may rescale or scroll after an edit.
  const screenOf = async () => {
    await grid.scrollIntoViewIfNeeded();
    const box = await grid.boundingBox();
    const bounds = await grid.evaluate((svg) => ({x: Number(svg.dataset.minX), z: Number(svg.dataset.minZ), size: Number(svg.dataset.size)}));
    return (x, z) => ({x: box.x + (x - bounds.x) / bounds.size * box.width, y: box.y + (z - bounds.z) / bounds.size * box.height});
  };
  const added = (await screenOf())(8, 0);
  await page.mouse.click(added.x, added.y);
  await grid.locator('.animus-route-node').nth(4).waitFor();
  const toScreen = await screenOf();
  const dot = await grid.locator('[data-node="points:0"]').boundingBox();
  const from = {x: dot.x + dot.width / 2, y: dot.y + dot.height / 2};
  const to = toScreen(2, -8);
  await page.mouse.move(from.x, from.y);
  await page.mouse.down();
  await page.mouse.move((from.x + to.x) / 2, (from.y + to.y) / 2, {steps: 4});
  await page.mouse.move(to.x, to.y, {steps: 4});
  await page.mouse.up();
  await settle(page);
  await shot(page, `${tag}-07-route`);
  let json = await codeOf(page);
  let points = json.modules['animus:navigation'].points;
  assert.equal(points.length, 5);
  assert.deepEqual(points[4], {x: 8, y: 64, z: 0});
  assert.deepEqual([points[0].x, points[0].z], [2, -8]);
  assert.equal(points[1].waitTicks, 60, 'other point fields survive');

  // Keyboard: nudge point 2 a quarter block east and a block north, then add
  // a point by its coordinates and delete it again.
  const routeAgain = (await part(inspector, 'behaviour')).getByRole('group', {name: 'Route editor'});
  await routeAgain.locator('[data-node="points:2"]').focus();
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('Shift+ArrowUp');
  await settle(page);
  const adder = routeAgain.getByRole('group', {name: 'Add point at'});
  await adder.getByLabel('New point x').fill('20');
  await adder.getByLabel('New point y').fill('65');
  await adder.getByLabel('New point z').fill('-3');
  await adder.getByRole('button', {name: 'Add point', exact: true}).click();
  await routeAgain.locator('[data-node="points:5"]').waitFor();
  json = await codeOf(page);
  points = json.modules['animus:navigation'].points;
  assert.deepEqual(points[2], {x: 12.25, y: 64, z: 3}, 'arrows nudge the focused point');
  assert.deepEqual(points[5], {x: 20, y: 65, z: -3}, 'the form adds a point');
  const routeThird = (await part(inspector, 'behaviour')).getByRole('group', {name: 'Route editor'});
  await routeThird.locator('[data-node="points:5"]').focus();
  await page.keyboard.press('Delete');
  await page.waitForFunction(() => !document.querySelector('[data-node="points:5"]'));
  json = await codeOf(page);
  assert.equal(json.modules['animus:navigation'].points.length, 5, 'Delete removes the focused point');

  // Body: pose and owner.
  const body = await part(inspector, 'body');
  await body.getByLabel('Pose', {exact: true}).selectOption('sitting');
  await settle(page);
  const identity = await part(inspector, 'identity');
  await identity.getByLabel('Owner UUID').fill('Steve');
  await identity.locator('.animus-gui-problem', {hasText: '$.owner'}).first().waitFor();
  await identity.getByLabel('Owner UUID').fill('0f6a3b8e-6a55-4b1e-9d8a-1f2e3d4c5b6a');
  await settle(page);

  // Appearance: hologram lines with a coloured preview and a style field.
  const appearance = await part(inspector, 'appearance');
  await appearance.getByRole('button', {name: 'Add hologram text'}).click();
  const hologram = (await part(inspector, 'appearance')).getByRole('group', {name: 'Hologram'});
  await hologram.getByLabel('Hologram line 1', {exact: true}).fill('&aHello there');
  await hologram.getByLabel('Hologram line 1', {exact: true}).blur();
  await hologram.locator('.animus-hologram-preview').getByText('Hello there').waitFor();
  await hologram.getByLabel('Billboard', {exact: true}).selectOption('vertical');
  await settle(page);
  await shot(page, `${tag}-08-appearance`);
  json = await codeOf(page);
  assert.equal(json.pose, 'sitting');
  assert.equal(json.owner, '0f6a3b8e-6a55-4b1e-9d8a-1f2e3d4c5b6a');
  assert.deepEqual(json.hologram, {lines: ['&aHello there'], billboard: 'vertical'});

  // Combat: an out-of-range reach is flagged at its field, then fixed.
  const modules = await part(inspector, 'modules');
  const combat = modules.locator('.animus-combat-editor').first();
  await combat.scrollIntoViewIfNeeded();
  const reach = combat.getByLabel('melee.reach', {exact: true});
  await reach.fill('9');
  await reach.blur();
  await combat.getByText(/melee\.reach/).first().waitFor();
  await page.locator('.animus-diagnostics li', {hasText: 'melee.reach'}).first().waitFor();
  await shot(page, `${tag}-09-combat-invalid`);
  await reach.fill('4.5');
  await reach.blur();
  await page.waitForFunction(() => ![...document.querySelectorAll('.animus-diagnostics li')].some((item) => item.textContent.includes('melee.reach')));
  json = await codeOf(page);
  assert.equal(json.modules['animus:combat'].melee.reach, 4.5);
  assert.equal(json.modules['animus:combat'].melee.damage, 5, 'other combat fields survive');

  // Equipment and skin.
  const look = await part(inspector, 'appearance');
  const offHand = look.getByLabel('equipment.off_hand', {exact: true});
  await offHand.scrollIntoViewIfNeeded();
  await offHand.fill('minecraft:shield');
  await offHand.blur();
  await look.getByRole('radio', {name: /^Skin 1 of 1, .*dc33741f$/}).click();
  await settle(page);
  json = await codeOf(page);
  assert.deepEqual(json.equipment.off_hand, {id: 'minecraft:shield'});
  assert.equal(json.equipment.main_hand.id, 'minecraft:iron_sword');
  assert.equal(json.skin, skinHash);
  await page.locator('.animus-npc-visual canvas').first().waitFor();
  await shot(page, `${tag}-10-inspector`);
  await noSidewaysScroll(page, `${tag} inspector`);

  // Armor stand parts and angles, and the rig picker, on the golem keeper.
  const base = await page.evaluate(() => location.hash.replace(/\/npc\/.*$/, ''));
  await page.goto(`${site}/${base}/npc/wild%2Fgolem-keeper`);
  const keeper = page.getByRole('region', {name: 'NPC inspector'});
  await keeper.waitFor();
  const keeperBody = await part(keeper, 'body');
  const stand = keeperBody.getByRole('group', {name: 'Armor stand'});
  await stand.getByRole('checkbox', {name: 'Small'}).click();
  await stand.locator('[role="checkbox"][aria-checked="true"]').first().waitFor();
  await stand.getByLabel('Head x degrees', {exact: true}).fill('15');
  await stand.getByLabel('Head x degrees', {exact: true}).blur();
  assert.equal(await keeperBody.getByLabel('Rig', {exact: true}).inputValue(), 'town:golem');
  await keeperBody.getByRole('button', {name: 'Open rig'}).waitFor();
  await keeperBody.getByLabel('Rig', {exact: true}).selectOption('');
  await settle(page);
  json = await codeOf(page);
  assert.deepEqual(json.armorStand, {small: true, head: [15, 0, 0]});
  assert.equal(json.rig, undefined, 'picking no rig removes it');
  await (await part(keeper, 'body')).getByLabel('Rig', {exact: true}).selectOption('town:golem');
  await settle(page);
  json = await codeOf(page);
  assert.deepEqual(json.rig, {id: 'town:golem'});
  await shot(page, `${tag}-11-armor-stand`);
  await noSidewaysScroll(page, `${tag} armor stand`);

  // The skin document: hash, model and the NPC that now wears it.
  await page.goto(`${site}/${base}/skin/${skinHash}`);
  const skin = page.getByRole('region', {name: 'Skin'});
  await skin.waitFor();
  await skin.getByText(skinHash).waitFor();
  await skin.getByText('Slim arms (Alex)').waitFor();
  await skin.getByRole('button', {name: /North Guard \(town\/guard-north\)/}).waitFor();
  await skin.getByRole('button', {name: 'Load skin PNG'}).waitFor();
  await shot(page, `${tag}-12-skin`);
  await noSidewaysScroll(page, `${tag} skin`);

  // Conversation of the ferryman's behavior.
  await page.goto(`${site}/${base}/behavior/harbor%3Aferryman`);
  const conversation = page.getByRole('group', {name: 'Conversation'});
  await conversation.waitFor();
  assert.equal(await conversation.getByLabel('Prompt ferry', {exact: true}).inputValue(), 'Need a crossing?');
  assert.equal(await conversation.getByLabel('Prompt fare', {exact: true}).inputValue(), 'Pay now or later?');
  await conversation.getByText('trigger pay runs paid').waitFor();
  await conversation.getByLabel('Answer no of ferry', {exact: true}).fill('Not today');
  await settle(page);
  await shot(page, `${tag}-13-dialogue`);
  json = await codeOf(page);
  assert.equal(json.routines.main.steps[2].arguments.options[1].text, 'Not today');
  await noSidewaysScroll(page, `${tag} dialogue`);

  assert.deepEqual(errors, [], `${tag}: console errors`);
  await context.close();
}

async function empty(browser, viewport, tag, theme) {
  const {context, page, errors} = await open(browser, viewport, theme);
  await page.goto(`${site}/`);
  await page.getByLabel('Workspace name').fill('Empty town');
  await page.getByRole('button', {name: 'Create blank workspace'}).click();
  await page.getByRole('heading', {name: 'New document'}).waitFor();
  const workspace = await page.evaluate(() => location.hash.replace(/\/new.*$/, ''));
  await page.goto(`${site}/${workspace}/npcs`);
  const browserPanel = page.getByRole('region', {name: 'NPC browser'});
  await browserPanel.getByText('This workspace has no NPCs yet.').waitFor();
  await shot(page, `${tag}-14-empty`);
  await noSidewaysScroll(page, `${tag} empty`);
  await browserPanel.getByRole('link', {name: 'Create an NPC'}).click();
  await page.getByRole('heading', {name: 'New document'}).waitFor();
  assert.ok((await page.evaluate(() => location.hash)).endsWith('/new/npc'));
  assert.deepEqual(errors, [], `${tag}: console errors on the empty workspace`);
  await context.close();
}

async function pairWith(page, scopes) {
  await control('reset', {});
  const {code} = await control(`pair?paths=direct${scopes ? `&scopes=${scopes}` : ''}`, {});
  await page.goto(`${site}/#/pair/${code}`);
  await page.locator('[data-testid="pair-fingerprint"]').waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await page.getByText(/documents on the server/).waitFor();
  for (const id of ['harbor/dockhand', 'harbor/ferryman']) await page.getByRole('checkbox', {name: id, exact: true}).check();
  await page.getByRole('button', {name: 'Open 2'}).click();
  await page.waitForFunction(() => decodeURIComponent(location.hash).includes('/npc/'));
  await page.locator('.animus-tree-browse').click();
  await waitRows(page, ['harbor/dockhand', 'harbor/ferryman']);
  await select(page, 'harbor/dockhand');
  await select(page, 'harbor/ferryman');
  return page.getByRole('group', {name: 'Bulk edit'});
}

async function server(browser, viewport, tag, theme) {
  {
    const {context, page, errors} = await open(browser, viewport, theme);
    const bulk = await pairWith(page, 'spawn');
    await bulk.getByRole('button', {name: 'Spawn on server', exact: true}).click();
    await page.getByTestId('live-last').filter({hasText: 'Spawned 2 NPCs on the server.'}).waitFor();
    await bulk.getByRole('button', {name: 'Despawn on server', exact: true}).click();
    await bulk.getByRole('button', {name: 'Despawn 2 NPCs now'}).waitFor();
    await shot(page, `${tag}-15-server-despawn-confirm`);
    await bulk.getByRole('button', {name: 'Despawn 2 NPCs now'}).click();
    await page.getByTestId('live-last').filter({hasText: 'Despawned 2 NPCs on the server.'}).waitFor();
    await noSidewaysScroll(page, `${tag} server`);
    assert.deepEqual(errors, [], `${tag}: console errors on the server workspace`);
    await context.close();
  }
  {
    const {context, page, errors} = await open(browser, viewport, theme);
    const bulk = await pairWith(page, '');
    await bulk.getByText(/needs the spawn scope/).waitFor();
    assert.equal(await bulk.getByRole('button', {name: 'Spawn on server'}).count(), 0, 'no spawn without the scope');
    await shot(page, `${tag}-16-server-no-scope`);
    assert.deepEqual(errors, [], `${tag}: console errors without the spawn scope`);
    await context.close();
  }
}

(async () => {
  const browser = await chromium.launch();
  try {
    for (const [viewport, width] of [[{width: 1440, height: 900}, 'desktop'], [{width: 390, height: 844}, 'phone']]) {
      for (const theme of ['dark', 'light']) {
        const tag = `${width}-${theme}`;
        await local(browser, viewport, tag, theme);
        await empty(browser, viewport, tag, theme);
        await server(browser, viewport, tag, theme);
        console.log(`${tag}: ok`);
      }
    }
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
