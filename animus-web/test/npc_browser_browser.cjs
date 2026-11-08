// Opens test/fixtures/npc_browser in the built site at desktop and phone
// widths, in the dark and the light theme, and checks the NPC browser
// (search, the nested folder tree by mouse and keyboard with expand,
// collapse and counts, folder create, rename, move and delete each undone
// in one step, NPCs moved into a nested folder, the export zip's nested
// paths, tag filters, empty states, bulk tag and folder edits undone in one
// step each, a bulk module switch, the delete confirm and Escape), the NPC
// inspector (its parts and
// their summaries, pose, owner, hologram lines with a preview, armor stand
// parts and angles, the rig picker), the route editor (a point added on the
// grid, one dragged, one nudged and one added and removed from the
// keyboard, and a recording step added, typed, made a position and
// removed), the combat editor (an out-of-range value flagged, then fixed),
// the equipment and skin editors, the skin document's summary and the
// conversation view. Then, against the fake server loaded with the same
// fixture, it renames a folder, applies it as moves, sees the moved paths
// on the server and undoes the apply, spawns and despawns a selection on
// the server (the despawn asks first) and shows that a token without the
// spawn scope cannot. No console errors and no sideways scrolling anywhere.
//
//   python3 tool/serve_site.py --port <WEB_PORT> &
//   dart run tool/fake_server.dart --port=<FAKE_PORT> --origin=http://127.0.0.1:<WEB_PORT> --data=test/fixtures/npc_browser &
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<WEB_PORT> ANIMUS_FAKE_URL=http://127.0.0.1:<FAKE_PORT> \
//     ANIMUS_SCREENSHOTS=<scratch folder> node test/npc_browser_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {sidebar, topbarAction} = require('./support/shell.cjs');
const {choose, chooseAndWait, valueOf} = require('./support/select.cjs');

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
    if (message.type() === 'error') errors.push(message.text());
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
  }, expected).catch(async (error) => {
    throw new Error(`rows are ${JSON.stringify(await rows(page))}, not ${JSON.stringify(expected)}: ${error.message}`);
  });
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

/** The tree item of a folder ('' is npcs/ itself). */
function folderItem(page, folder) {
  return page.locator(`.animus-npc-tree [role="treeitem"][data-folder="${folder}"]`);
}

async function folderCount(page, folder) {
  return Number(await folderItem(page, folder).locator('.animus-count').textContent());
}

/** Runs a folder action on the selected folder and returns its form. */
async function folderAction(page, name) {
  const actions = page.getByRole('group', {name: 'Folder actions'});
  await actions.getByRole('button', {name, exact: true}).click();
  return actions;
}

async function focusedFolder(page) {
  return page.evaluate(() => document.activeElement?.getAttribute('data-folder'));
}

async function focusedLabel(page) {
  return page.evaluate(() => document.activeElement?.getAttribute('aria-label'));
}

async function local(browser, viewport, tag, theme) {
  const {context, page, errors} = await open(browser, viewport, theme);
  const phone = viewport.width < 600;
  await page.goto(`${site}/`);
  await page.getByRole('heading', {name: 'From a data folder'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(folder);
  await page.getByRole('heading', {name: 'Every document passes the shared schemas'}).waitFor();

  // Browser: list, search, folder and tag filters.
  await (await sidebar(page)).locator('.animus-tree-browse').click();
  const browserPanel = page.getByRole('region', {name: 'NPC browser'});
  await browserPanel.waitFor();
  const all = ['crier', 'harbor/dockhand', 'harbor/ferryman', 'town/baker', 'town/guard-north', 'town/guard-south', 'town/market/fishmonger', 'town/market/stalls/cartwright', 'wild/golem-keeper'];
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

  // Folder tree by mouse: nested folders, a folder's own count, and its
  // subtree's count once collapsed.
  const tree = page.getByRole('tree', {name: 'Folders'});
  assert.deepEqual(await tree.locator('[role="treeitem"]').evaluateAll((items) => items.map((item) => `${item.getAttribute('aria-level')}:${item.dataset.folder}`)),
    ['1:', '2:harbor', '2:town', '3:town/market', '4:town/market/stalls', '2:wild']);
  assert.equal(await folderCount(page, ''), 1, 'npcs/ itself holds the crier');
  assert.equal(await folderCount(page, 'town'), 3);
  await folderItem(page, 'town').click();
  await waitRows(page, ['town/baker', 'town/guard-north', 'town/guard-south', 'town/market/fishmonger', 'town/market/stalls/cartwright']);
  assert.equal(await page.getByLabel('Search NPCs', {exact: true}).inputValue(), 'folder:town');
  assert.equal(await folderItem(page, 'town').getAttribute('aria-selected'), 'true');
  await folderItem(page, 'town').locator('[data-testid="tree-toggle"]').click();
  await folderItem(page, 'town/market').waitFor({state: 'detached'});
  assert.equal(await folderItem(page, 'town').getAttribute('aria-expanded'), 'false');
  assert.equal(await folderCount(page, 'town'), 5, 'a collapsed folder counts its subtree');
  assert.match(await folderItem(page, 'town').getAttribute('aria-label'), /5 NPCs with its folders/);
  await shot(page, `${tag}-02b-tree-collapsed`);
  await folderItem(page, 'town').locator('[data-testid="tree-toggle"]').click();
  await folderItem(page, 'town/market').waitFor();
  await search(page, 'folder:=town');
  await waitRows(page, ['town/baker', 'town/guard-north', 'town/guard-south']);
  await search(page, '');
  await waitRows(page, all);

  // Folder tree by keyboard: one tab stop, arrows, Home, End and Enter,
  // Right expands then enters, Left collapses then returns to the parent.
  assert.equal(await tree.locator('[role="treeitem"][tabindex="0"]').count(), 1, 'the tree has one tab stop');
  await folderItem(page, '').focus();
  await page.keyboard.press('ArrowDown');
  assert.equal(await focusedFolder(page), 'harbor');
  await page.keyboard.press('ArrowDown');
  assert.equal(await focusedFolder(page), 'town');
  await page.keyboard.press('ArrowLeft');
  await folderItem(page, 'town/market').waitFor({state: 'detached'});
  await page.keyboard.press('ArrowRight');
  await folderItem(page, 'town/market').waitFor();
  await page.keyboard.press('ArrowRight');
  assert.equal(await focusedFolder(page), 'town/market');
  await page.keyboard.press('ArrowLeft');
  await folderItem(page, 'town/market/stalls').waitFor({state: 'detached'});
  await page.keyboard.press('ArrowLeft');
  assert.equal(await focusedFolder(page), 'town');
  await page.keyboard.press('ArrowRight');
  assert.equal(await focusedFolder(page), 'town/market');
  await page.keyboard.press('ArrowRight');
  await folderItem(page, 'town/market/stalls').waitFor();
  await page.keyboard.press('End');
  assert.equal(await focusedFolder(page), 'wild');
  await page.keyboard.press('Enter');
  await waitRows(page, ['wild/golem-keeper']);
  await page.keyboard.press('Home');
  assert.equal(await focusedFolder(page), '');
  await page.keyboard.press('Enter');
  await waitRows(page, all);
  assert.equal(await tree.locator('[role="treeitem"][tabindex="0"]').count(), 1);
  // The page remembers collapsed folders for this workspace.
  await folderItem(page, 'wild').focus();
  await page.keyboard.press('ArrowUp');
  await page.keyboard.press('ArrowUp');
  assert.equal(await focusedFolder(page), 'town/market');
  await page.keyboard.press('ArrowLeft');
  await folderItem(page, 'town/market/stalls').waitFor({state: 'detached'});
  assert.ok((await page.evaluate(() => Object.keys(localStorage).filter((key) => key.startsWith('animus.npc-folders.collapsed.')).map((key) => localStorage.getItem(key)))).some((value) => value.includes('town/market')));
  await page.keyboard.press('ArrowRight');
  await folderItem(page, 'town/market/stalls').waitFor();

  // New folder: a bad name is refused in the server's words; a good one
  // shows empty until an NPC lands there.
  await folderItem(page, 'harbor').click();
  let actions = await folderAction(page, 'New folder');
  await actions.getByLabel('New folder in npcs/harbor/').fill('Bad Name');
  await actions.getByRole('button', {name: 'Make folder'}).click();
  await actions.getByTestId('folder-problem').filter({hasText: 'expected the folder under npcs/: empty, or up to 8 names of 1\u201364 lowercase letters'}).waitFor();
  await shot(page, `${tag}-02c-folder-refused`);
  await actions.getByLabel('New folder in npcs/harbor/').fill('docks');
  await actions.getByRole('button', {name: 'Make folder'}).click();
  await folderItem(page, 'harbor/docks').waitFor();
  assert.equal(await folderCount(page, 'harbor/docks'), 0);
  await browserPanel.getByText('npcs/harbor/docks/ is empty.', {exact: false}).waitFor();
  await browserPanel.locator('.animus-npc-empty').getByRole('link', {name: 'New NPC in npcs/harbor/docks/'}).waitFor();
  await shot(page, `${tag}-02d-folder-empty`);
  await search(page, '');
  await waitRows(page, all);

  // Move two NPCs into the nested folder with the bulk picker, in one step.
  await select(page, 'crier');
  await select(page, 'harbor/dockhand');
  const picker = page.getByRole('group', {name: 'Bulk edit'});
  await chooseAndWait(picker.getByLabel('Move to', {exact: true}), 'harbor/docks');
  await picker.getByRole('button', {name: 'Move to npcs/harbor/docks/'}).click();
  const docked = ['harbor/ferryman', 'harbor/docks/dockhand', 'harbor/docks/crier', 'town/baker', 'town/guard-north', 'town/guard-south', 'town/market/fishmonger', 'town/market/stalls/cartwright', 'wild/golem-keeper'];
  await waitRows(page, docked);
  assert.equal(await folderCount(page, ''), 0);
  assert.equal(await folderCount(page, 'harbor/docks'), 2);
  await page.getByRole('button', {name: 'Clear selection'}).click();

  // Rename a folder with a subtree, then undo it in one step.
  await folderItem(page, 'town/market').click();
  actions = await folderAction(page, 'Rename');
  await actions.getByLabel('New name for npcs/town/market/').fill('bazaar');
  await actions.getByRole('button', {name: 'Rename folder'}).click();
  await folderItem(page, 'town/bazaar/stalls').waitFor();
  await waitRows(page, ['town/bazaar/fishmonger', 'town/bazaar/stalls/cartwright']);
  await shot(page, `${tag}-02e-folder-renamed`);
  await topbarAction(page, 'Undo');
  await folderItem(page, 'town/market/stalls').waitFor();
  await folderItem(page, 'town/bazaar').waitFor({state: 'detached'});

  // Move a folder under another one, then undo.
  await folderItem(page, 'town/market').click();
  actions = await folderAction(page, 'Move');
  await chooseAndWait(actions.getByLabel('Move npcs/town/market/ into'), 'harbor');
  await actions.getByRole('button', {name: 'Move folder'}).click();
  await folderItem(page, 'harbor/market/stalls').waitFor();
  await waitRows(page, ['harbor/market/fishmonger', 'harbor/market/stalls/cartwright']);
  await topbarAction(page, 'Undo');
  await folderItem(page, 'town/market/stalls').waitFor();

  // Delete: a folder with NPCs says why not; an empty one goes.
  await folderItem(page, 'town').click();
  actions = await folderAction(page, 'Delete');
  await actions.getByText('npcs/town/ holds 5 NPCs; move or delete them first.').waitFor();
  await actions.getByRole('button', {name: 'Back'}).click();
  await folderItem(page, 'wild').click();
  actions = await folderAction(page, 'New folder');
  await actions.getByLabel('New folder in npcs/wild/').fill('caves');
  await actions.getByRole('button', {name: 'Make folder'}).click();
  await folderItem(page, 'wild/caves').waitFor();
  actions = await folderAction(page, 'Delete');
  await actions.getByRole('button', {name: 'Remove folder'}).click();
  await folderItem(page, 'wild/caves').waitFor({state: 'detached'});
  await noSidewaysScroll(page, `${tag} folders`);

  // Export writes the nested paths.
  const download = page.waitForEvent('download');
  await topbarAction(page, 'Export the data folder zip', {menu: 'export'});
  const zip = fs.readFileSync(await (await download).path());
  for (const entry of ['npcs/harbor/docks/crier.json', 'npcs/harbor/docks/dockhand.json', 'npcs/town/market/stalls/cartwright.json']) {
    assert.ok(zip.includes(Buffer.from(entry)), `zip holds ${entry}`);
  }

  // Undo the NPC move: everything is back where the fixture has it.
  await topbarAction(page, 'Undo');
  await search(page, '');
  await waitRows(page, all);

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
  await bulk.getByLabel('Or a new path', {exact: true}).fill('watch/night');
  await bulk.getByRole('button', {name: 'Move to npcs/watch/night/'}).click();
  await waitRows(page, ['crier', 'harbor/dockhand', 'harbor/ferryman', 'town/baker', 'town/market/fishmonger', 'town/market/stalls/cartwright', 'watch/night/guard-north', 'watch/night/guard-south', 'wild/golem-keeper']);
  await shot(page, `${tag}-04-bulk`);
  await topbarAction(page, 'Undo');
  await waitRows(page, all);
  assert.deepEqual(await tagsOf(page, 'town/guard-south'), ['guard', 'night-watch'], 'undoing the move keeps the tags');
  await topbarAction(page, 'Undo');
  await page.waitForFunction(() => !document.querySelector('.animus-npc-tag[data-tag="night-watch"]'));
  assert.deepEqual(await tagsOf(page, 'town/guard-north'), ['guard', 'patrol']);

  // Bulk module switch: combat off on both guards, then undo.
  await page.getByRole('checkbox', {name: 'Select every NPC shown'}).check();
  await bulk.getByText('9 NPCs selected').waitFor();
  await page.getByRole('checkbox', {name: 'Select every NPC shown'}).uncheck();
  await select(page, 'town/guard-north');
  await select(page, 'town/guard-south');
  await chooseAndWait(bulk.getByLabel('Module to change'), 'animus:combat');
  await bulk.getByRole('button', {name: 'Disable', exact: true}).click();
  await page.waitForFunction(() => !document.querySelector('.animus-npc-row [data-feature="combat"]'));
  await shot(page, `${tag}-05-bulk-module`);
  await topbarAction(page, 'Undo');
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

  // A recording step: listed and numbered with the points, typed by id when
  // the workspace has no recordings, then made a position and removed.
  const routeStep = (await part(inspector, 'behaviour')).getByRole('group', {name: 'Route editor'});
  await routeStep.getByRole('button', {name: 'Add recording step'}).click();
  const stepPicked = routeStep.getByRole('group', {name: 'Selected Step 5'});
  await stepPicked.waitFor();
  await stepPicked.getByText('No recordings. Type an id.', {exact: true}).waitFor();
  await stepPicked.getByLabel('Step 5 recording').fill('market-loop');
  await stepPicked.getByLabel('Step 5 toTick').fill('400');
  await settle(page);
  const stepRow = routeStep.getByRole('list', {name: 'Route points'}).locator('li').nth(5);
  await stepRow.getByText('market-loop, ticks 0 to 400').waitFor();
  assert.equal(await stepRow.locator('span').first().textContent(), 'step');
  assert.equal(await routeStep.locator('svg.animus-route-grid .animus-route-node').count(), 5, 'a step is not drawn on the grid');
  await shot(page, `${tag}-07b-route-step`);
  json = await codeOf(page);
  assert.deepEqual(json.modules['animus:navigation'].points[5], {recording: 'market-loop', toTick: 400});
  // Code and back clears the selection, so pick the step from the list again.
  const routeStepAgain = (await part(inspector, 'behaviour')).getByRole('group', {name: 'Route editor'});
  await routeStepAgain.getByRole('list', {name: 'Route points'}).locator('li').nth(5).locator('button').click();
  await routeStepAgain.getByRole('group', {name: 'Selected Step 5'}).getByRole('button', {name: 'Make a position'}).click();
  await routeStepAgain.locator('[data-node="points:5"]').waitFor();
  json = await codeOf(page);
  assert.deepEqual(Object.keys(json.modules['animus:navigation'].points[5]), ['x', 'y', 'z'], 'the recording fields go with the step');
  const routeStepLast = (await part(inspector, 'behaviour')).getByRole('group', {name: 'Route editor'});
  await routeStepLast.locator('[data-node="points:5"]').focus();
  await page.keyboard.press('Delete');
  await page.waitForFunction(() => !document.querySelector('[data-node="points:5"]'));

  // Body: pose and owner.
  const body = await part(inspector, 'body');
  await chooseAndWait(body.getByRole('combobox', {name: 'Pose', exact: true}), 'sitting');
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
  await chooseAndWait(hologram.getByRole('combobox', {name: 'Billboard', exact: true}), 'vertical');
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
  // A slot click opens its item field under the equipment grid.
  const equipmentSlot = (key) => look.locator(`[data-slot-grid="animus-equipment"] [data-slot="${key}"]`);
  await equipmentSlot('off_hand').scrollIntoViewIfNeeded();
  await equipmentSlot('off_hand').click();
  const offHand = look.getByLabel('equipment.off_hand', {exact: true});
  await offHand.fill('minecraft:shield');
  await offHand.blur();
  // A bare id is a minecraft item: kept as written, not flagged.
  await equipmentSlot('head').click();
  const head = look.getByLabel('equipment.head', {exact: true});
  await head.fill('netherite_helmet');
  await head.blur();
  await settle(page);
  assert.equal(await page.locator('.animus-diagnostics li', {hasText: 'equipment.head'}).count(), 0, `${tag}: a bare item id validates`);
  await choose(look.locator('#animus-skin-pick'), skinHash);
  await settle(page);
  json = await codeOf(page);
  assert.deepEqual(json.equipment.off_hand, {id: 'minecraft:shield'});
  assert.deepEqual(json.equipment.head, {id: 'netherite_helmet'});
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
  assert.equal(await valueOf(keeperBody.getByRole('combobox', {name: 'Rig', exact: true})), 'town:golem');
  await keeperBody.getByRole('button', {name: 'Open rig'}).waitFor();
  await chooseAndWait(keeperBody.getByRole('combobox', {name: 'Rig', exact: true}), '');
  await settle(page);
  json = await codeOf(page);
  assert.deepEqual(json.armorStand, {small: true, head: [15, 0, 0]});
  assert.equal(json.rig, undefined, 'picking no rig removes it');
  await chooseAndWait((await part(keeper, 'body')).getByRole('combobox', {name: 'Rig', exact: true}), 'town:golem');
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
  await skin.locator('.animus-skin-facts').getByText('Slim arms (Alex)').waitFor();
  await skin.getByRole('button', {name: /North Guard \(town\/guard-north\)/}).waitFor();
  await skin.getByRole('button', {name: 'Load skin PNG'}).waitFor();
  await shot(page, `${tag}-12-skin`);
  await noSidewaysScroll(page, `${tag} skin`);

  // The ferryman's behavior opens on its Conversation tab.
  await page.goto(`${site}/${base}/behavior/harbor%3Aferryman`);
  await page.waitForFunction(() => location.hash.includes('view=conversation'));
  const conversation = page.getByRole('region', {name: 'Conversation'});
  await conversation.locator('[data-block="main"]').waitFor();
  assert.equal(await page.locator('[id="conv-prompt:main:2"]').inputValue(), 'Need a crossing?');
  assert.equal(await page.locator('[id="conv-prompt:cross:1"]').inputValue(), 'Pay now or later?');
  await conversation.locator('[data-block="cross"] [data-option="now"] [data-block="paid"]').waitFor();
  await page.locator('[id="conv-option:main:2:1"]').fill('Not today');
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
  await browserPanel.getByText('No NPCs yet.').waitFor();
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
  await (await sidebar(page)).locator('.animus-tree-browse').click();
  await waitRows(page, ['harbor/dockhand', 'harbor/ferryman']);
  await select(page, 'harbor/dockhand');
  await select(page, 'harbor/ferryman');
  return page.getByRole('group', {name: 'Bulk edit'});
}

/** The list's empty state names the gone folder and how to get back. */
async function browserEmpty(page, text) {
  const empty = page.getByRole('region', {name: 'NPC browser'}).locator('.animus-npc-empty');
  await empty.getByText(text, {exact: false}).waitFor();
  await empty.getByRole('button', {name: 'Show every NPC'}).waitFor();
}

async function serverDocuments() {
  const state = await control('state');
  return state.documents.filter((document) => document.kind === 'npc').map((document) => document.id).sort();
}

/** A server workspace with every NPC open: rename town/market, apply, undo. */
async function serverFolders(browser, viewport, tag, theme) {
  const {context, page, errors} = await open(browser, viewport, theme);
  await control('reset', {});
  const {code} = await control('pair?paths=direct', {});
  await page.goto(`${site}/#/pair/${code}`);
  await page.locator('[data-testid="pair-fingerprint"]').waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await page.getByText(/documents on the server/).waitFor();
  for (const id of ['town/baker', 'town/market/fishmonger', 'town/market/stalls/cartwright']) await page.getByRole('checkbox', {name: id, exact: true}).check();
  await page.getByRole('button', {name: 'Open 3'}).click();
  await page.waitForFunction(() => decodeURIComponent(location.hash).includes('/npc/'));
  await (await sidebar(page)).locator('.animus-tree-browse').click();
  await waitRows(page, ['town/baker', 'town/market/fishmonger', 'town/market/stalls/cartwright']);
  const before = await serverDocuments();
  await folderItem(page, 'town/market').click();
  const actions = await folderAction(page, 'Rename');
  await actions.getByLabel('New name for npcs/town/market/').fill('bazaar');
  await actions.getByRole('button', {name: 'Rename folder'}).click();
  await folderItem(page, 'town/bazaar/stalls').waitFor();
  await page.getByTestId('draft-count').filter({hasText: '2 changed drafts, 2 moved'}).waitFor();
  await page.locator('.animus-live-drafts').getByText('npc town/market/fishmonger to town/bazaar/fishmonger').waitFor();
  await shot(page, `${tag}-15a-server-moves`);
  await page.getByRole('button', {name: 'Apply 2'}).click();
  await page.getByTestId('live-last').filter({hasText: 'Applied 2 documents, 2 moved'}).waitFor();
  await page.getByTestId('draft-count').filter({hasText: 'No changed drafts'}).waitFor();
  const moved = await serverDocuments();
  assert.ok(moved.includes('town/bazaar/fishmonger') && moved.includes('town/bazaar/stalls/cartwright'), `the server has the moved paths: ${moved}`);
  assert.ok(!moved.includes('town/market/fishmonger') && !moved.includes('town/market/stalls/cartwright'), 'the old paths are gone');
  assert.equal(moved.length, before.length);
  const state = await control('state');
  const fish = state.documents.find((document) => document.id === 'town/bazaar/fishmonger');
  assert.equal(fish.revision, 2, 'a move keeps the revision chain');
  await page.getByRole('button', {name: 'Undo last apply'}).click();
  await page.getByRole('alertdialog').getByText('Undo the last apply on the server?').waitFor();
  await page.getByRole('button', {name: 'Undo on the server'}).click();
  await page.getByTestId('live-last').filter({hasText: 'Undid 2 documents, 2 moved'}).waitFor();
  assert.deepEqual(await serverDocuments(), before, 'undo moves them back');
  await browserEmpty(page, 'npcs/town/bazaar/ is empty.');
  await search(page, '');
  await waitRows(page, ['town/baker', 'town/market/fishmonger', 'town/market/stalls/cartwright']);
  await page.getByTestId('draft-count').filter({hasText: 'No changed drafts'}).waitFor();
  await shot(page, `${tag}-15b-server-undone`);
  await noSidewaysScroll(page, `${tag} server folders`);
  assert.deepEqual(errors, [], `${tag}: console errors on the server folder rename`);
  await context.close();
}

async function server(browser, viewport, tag, theme) {
  await serverFolders(browser, viewport, tag, theme);
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
