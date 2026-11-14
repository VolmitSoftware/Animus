// The NPC list against the fake server loaded with test/fixtures/npc_browser,
// at 1600, 768 and 390 px (dark, dark, light): every NPC is listed with its
// name right after pairing (no click), the NPC named only `&f` opens by its
// row, the Folder, Body and World filters and search narrow the list, Simple
// shows no checkboxes or file paths, and New NPC makes an NPC and opens it.
// In Advanced: tag chips, then bulk tags add and remove, a move to a new
// folder, a module turned off, on and removed, a trait group added and
// removed (each one undo step, sent at Apply and checked on the server),
// delete with its confirm and Undo, Select all down to the empty state,
// spawn and despawn on the server (Despawn asks first), folder rename (sent
// as moves that keep the revision chain) and folder move, and New NPC with
// a folder and an id. A token without the `spawn` scope spawns and
// despawns in the definitions instead. No console errors, no sideways
// scrolling.
//
// With ANIMUS_SCALE_URL (a second fake server started with --scale=1200,
// about 500 NPCs) it also lists them all, scrolls to the end, opens the
// last row and prints the time to the first rows and the time per key of
// a search.
//
//   python3 tool/serve_site.py --port <WEB_PORT> &
//   dart run tool/fake_server.dart --port=<FAKE_PORT> --origin=http://127.0.0.1:<WEB_PORT> --data=test/fixtures/npc_browser &
//   dart run tool/fake_server.dart --port=<SCALE_PORT> --origin=http://127.0.0.1:<WEB_PORT> --scale=1200 &
//   ANIMUS_WEB_URL=http://127.0.0.1:<WEB_PORT> ANIMUS_FAKE_URL=http://127.0.0.1:<FAKE_PORT> \
//     ANIMUS_SCALE_URL=http://127.0.0.1:<SCALE_PORT> ANIMUS_SCREENSHOTS=<folder> node test/npc_browser_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {setMode, topbarAction, saveState, saveAction} = require('./support/shell.cjs');
const {choose} = require('./support/select.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to your own served site'); })()).replace(/\/$/, '');
const fake = process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })();
const scaleFake = process.env.ANIMUS_SCALE_URL;
const shots = process.env.ANIMUS_SCREENSHOTS;
// ANIMUS_VIEWPORTS= (empty) runs the 500-NPC part alone.
const viewports = (process.env.ANIMUS_VIEWPORTS ?? '1600x1000:dark,768x1024:light,390x844:dark').split(',').filter(Boolean).map((entry) => {
  const [size, theme] = entry.split(':');
  const [width, height] = size.split('x').map(Number);
  return {width, height, theme: theme || 'dark'};
});

const all = ['crier', 'harbor/dockhand', 'harbor/ferryman', 'harbor/lamplighter', 'town/baker', 'town/guard-north', 'town/guard-south', 'town/market/fishmonger', 'town/market/stalls/cartwright', 'wild/golem-keeper'];
// Pairing grants spawn by default; this token is narrowed to leave it out.
const noSpawn = 'read,validate,preview,apply:npcs,apply:behaviors,apply:gui,apply:rigs,terrain';

async function shot(page, name) {
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  // Dialogs and menus fade in.
  await page.waitForTimeout(350);
  await page.screenshot({path: path.join(shots, `${name}.png`)});
}

async function control(route, body, base = fake) {
  const response = await fetch(`${base}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

async function source(id) {
  return (await control(`document?kind=npc&id=${encodeURIComponent(id)}`)).source;
}

async function serverNpcs(base = fake) {
  return (await control('state', undefined, base)).documents.filter((document) => document.kind === 'npc');
}

function watch(page) {
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  page.on('console', (message) => {
    // Generated skins name textures that do not exist on Mojang's host.
    if (message.type() === 'error' && !/textures\.minecraft\.net|status of 404/.test(message.text())) errors.push(message.text());
  });
  return errors;
}

async function noSidewaysScroll(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  assert.ok(overflow <= 1, `${where}: the page scrolls sideways by ${overflow}px`);
}

/** Pairs a new context with [base] and opens its NPC list; returns the time to the first rows. */
async function pair(browser, viewport, {scopes, base = fake} = {}) {
  await control('reset', {}, base);
  const {code} = await control(`pair?paths=direct${scopes ? `&scopes=${scopes}` : ''}`, {}, base);
  const context = await browser.newContext({viewport: {width: viewport.width, height: viewport.height}});
  await context.addInitScript((theme) => window.localStorage.setItem('animus.theme', theme), viewport.theme);
  const page = await context.newPage();
  page.setDefaultTimeout(20000);
  const errors = watch(page);
  await page.goto(`${site}/#/pair/${code}`);
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  const paired = Date.now();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  const id = decodeURIComponent((await page.evaluate(() => location.hash)).split('/')[2]);
  const workspace = encodeURIComponent(`server-${id}`);
  await page.evaluate((hash) => { location.hash = hash; }, `#/workspace/${workspace}/npcs`);
  await page.locator('.ui-library-row .ui-library-title').first().waitFor();
  return {context, page, errors, workspace, firstRows: Date.now() - paired};
}

const list = (page) => page.locator('#npc-list');
const row = (page, id) => page.locator(`.ui-library-row[data-key="npc:${id}"]`);

/** Every row key of the list, scrolling through it (rows are windowed). */
async function keys(page) {
  const viewport = list(page).locator('.ui-library-rows');
  if (!(await viewport.count())) return [];
  await viewport.evaluate((element) => { element.scrollTop = 0; });
  const seen = new Set();
  for (let round = 0; round < 400; round++) {
    await page.waitForTimeout(30);
    for (const key of await page.locator('.ui-library-row').evaluateAll((found) => found.map((element) => element.getAttribute('data-key')))) seen.add(key.slice('npc:'.length));
    const more = await viewport.evaluate((element) => {
      if (element.scrollTop + element.clientHeight >= element.scrollHeight - 1) return false;
      element.scrollTop += element.clientHeight;
      return true;
    });
    if (!more) break;
  }
  await viewport.evaluate((element) => { element.scrollTop = 0; });
  return [...seen].sort();
}

async function waitKeys(page, expected, where) {
  const want = [...expected].sort();
  const deadline = Date.now() + 15000;
  let got = await keys(page);
  while (JSON.stringify(got) !== JSON.stringify(want) && Date.now() < deadline) {
    await page.waitForTimeout(150);
    got = await keys(page);
  }
  assert.deepEqual(got, want, `${where}: rows`);
}

async function count(page) {
  return list(page).locator('.ui-library-bar .ui-library-count').textContent();
}

async function search(page, text) {
  await page.getByLabel('Search npcs', {exact: true}).fill(text);
}

/** Picks the filter select [id] value ('' for its "All"/"Any" entry). */
async function filter(page, id, value) {
  await choose(page.locator(`#${id}`), value);
}

async function pick(page, ids) {
  for (const id of ids) {
    await row(page, id).scrollIntoViewIfNeeded();
    await row(page, id).locator('.ui-library-pick [role="checkbox"]').click();
    await row(page, id).locator('.ui-library-pick [role="checkbox"][aria-checked="true"]').waitFor();
  }
  await page.locator('.ui-library-bulk .ui-library-count', {hasText: `${ids.length} picked`}).waitFor();
}

async function clearPicks(page) {
  const bulk = page.locator('.ui-library-bulk');
  if (await bulk.count()) await bulk.getByRole('button', {name: 'Clear', exact: true}).click();
  await bulk.waitFor({state: 'detached'});
}

/** Runs a bulk bar action, from the bar or its "..." menu when it did not fit. */
async function bulk(page, name) {
  const bar = page.locator('.ui-library-bulk');
  // The bar folds what does not fit into its "..." menu once it has measured.
  await page.waitForTimeout(250);
  const button = bar.getByRole('button', {name, exact: true});
  if (await button.isVisible().catch(() => false)) {
    await button.click();
    return;
  }
  await bar.getByRole('button', {name: 'More actions'}).click();
  await page.getByRole('menuitem', {name, exact: true}).click();
}

/** The open dialog with [id]; returns it once visible. */
async function dialog(page, id) {
  const found = page.locator(`#${id}`);
  await found.getByRole('dialog').or(found.locator('[role="dialog"]')).first().waitFor();
  return found;
}

async function closed(page, id) {
  await page.locator(`#${id} [role="dialog"]`).waitFor({state: 'detached'}).catch(async () => {
    await page.locator(`#${id}`).waitFor({state: 'detached'});
  });
}

async function confirm(page, title, button) {
  const alert = page.getByRole('alertdialog');
  await alert.getByText(title).waitFor();
  await alert.getByRole('button', {name: button, exact: true}).click();
  await alert.waitFor({state: 'detached'});
}

async function changes(page, text) {
  await saveState(page).filter({hasText: text}).waitFor();
}

async function apply(page) {
  await saveAction(page, 'apply').click();
  await saveState(page).filter({hasText: /^Applied|^Up to date/}).waitFor();
}

async function headerMenu(page, item) {
  await page.locator('#animus-main').getByRole('button', {name: 'More actions'}).first().click();
  await page.getByRole('menuitem', {name: item, exact: true}).click();
}

async function advancedLine(page, id) {
  await row(page, id).scrollIntoViewIfNeeded();
  return row(page, id).locator('.ui-library-line').textContent();
}

async function backToList(page, workspace) {
  await page.evaluate((hash) => { location.hash = hash; }, `#/workspace/${workspace}/npcs`);
  await list(page).waitFor();
}

async function simple(page, workspace, tag) {
  // Every NPC with its name, no click after pairing; the `&f` NPC is
  // "Unnamed NPC" with its folder path as its line.
  await waitKeys(page, all, `${tag} after pairing`);
  assert.equal(await count(page), '10 NPCs');
  await row(page, 'town/baker').locator('.ui-library-title', {hasText: 'Baker'}).waitFor();
  assert.equal(await row(page, 'harbor/lamplighter').locator('.ui-library-title').textContent(), 'Unnamed NPC');
  assert.equal(await row(page, 'harbor/lamplighter').locator('.ui-library-line').textContent(), 'Villager, harbor at 30, 64, 6', 'an unnamed NPC is told apart by where it stands');
  // Simple: no checkboxes, no file paths, no folder menu.
  assert.equal(await list(page).locator('.ui-library-pick').count(), 0, `${tag}: no checkboxes in Simple`);
  const lines = await list(page).locator('.ui-library-line').allTextContents();
  assert.ok(lines.every((line) => !line.includes('npcs/')), `${tag}: no file paths in Simple: ${lines}`);
  assert.equal(await page.locator('#animus-main').getByRole('button', {name: 'More actions'}).count(), 0, `${tag}: nothing but New NPC in the Simple header`);
  assert.equal(await row(page, 'harbor/dockhand').locator('.ui-library-line').textContent(), 'Armor stand, harbor');
  await shot(page, `list-${tag}-simple`);
  await noSidewaysScroll(page, `${tag} list`);

  // Folder, Body and World: one row of selects inside the list's own bar,
  // with its search and chips; nothing filters the list from outside it.
  const boxes = await page.locator('#npc-list .ui-library-tools .animus-select').evaluateAll((found) => found.map((element) => element.getBoundingClientRect().top));
  assert.equal(boxes.length, 3);
  assert.ok(boxes.every((top) => Math.abs(top - boxes[0]) < 2), `${tag}: the filter selects share one row: ${boxes}`);
  assert.equal(await page.locator('#animus-main .animus-select').evaluateAll((found) => found.filter((element) => !element.closest('#npc-list')).length), 0, `${tag}: a filter outside the list card`);
  await filter(page, 'npc-filter-folder', 'town');
  await waitKeys(page, ['town/baker', 'town/guard-north', 'town/guard-south', 'town/market/fishmonger', 'town/market/stalls/cartwright'], `${tag} folder town`);
  await filter(page, 'npc-filter-folder', 'town/market');
  await waitKeys(page, ['town/market/fishmonger', 'town/market/stalls/cartwright'], `${tag} folder town/market`);
  await filter(page, 'npc-filter-folder', '');
  await filter(page, 'npc-filter-body', 'minecraft:armor_stand');
  await waitKeys(page, ['harbor/dockhand', 'wild/golem-keeper'], `${tag} body`);
  await filter(page, 'npc-filter-world', 'harbor');
  await waitKeys(page, ['harbor/dockhand'], `${tag} body and world`);
  await filter(page, 'npc-filter-body', '');
  await waitKeys(page, ['harbor/dockhand', 'harbor/ferryman', 'harbor/lamplighter'], `${tag} world`);
  await filter(page, 'npc-filter-world', '');
  await waitKeys(page, all, `${tag} filters cleared`);

  // Search: words of the name, the body and world line, and the id.
  await search(page, 'guard');
  await waitKeys(page, ['town/guard-north', 'town/guard-south'], `${tag} search guard`);
  await search(page, 'armor harbor');
  await waitKeys(page, ['harbor/dockhand'], `${tag} search armor harbor`);
  await search(page, 'nobody-here');
  await list(page).getByText('No matches.').waitFor();
  await list(page).getByRole('button', {name: 'Clear search'}).click();
  await waitKeys(page, all, `${tag} search cleared`);
  await list(page).getByRole('group', {name: 'Filters'}).getByRole('button', {name: 'Despawned', exact: true}).click();
  await waitKeys(page, ['harbor/dockhand', 'town/market/stalls/cartwright'], `${tag} despawned chip`);
  await list(page).getByRole('group', {name: 'Filters'}).getByRole('button', {name: 'Despawned', exact: true}).click();
  await waitKeys(page, all, `${tag} chip off`);

  // The NPC named only `&f` opens by its row.
  await row(page, 'harbor/lamplighter').click();
  await page.waitForFunction(() => decodeURIComponent(location.hash).endsWith('/npc/harbor/lamplighter'));
  await page.locator('#animus-main-heading').waitFor();
  await backToList(page, workspace);

  // New NPC in Simple: name and body only; Create opens it.
  await page.getByTestId('npc-new').click();
  let box = await dialog(page, 'npc-new-dialog');
  assert.equal(await box.locator('#npc-new-folder, #npc-new-id').count(), 0, `${tag}: no folder or id in Simple`);
  await shot(page, `list-${tag}-new-simple`);
  await box.getByRole('button', {name: 'Create', exact: true}).click();
  await box.getByText('Give the NPC a name.').waitFor();
  await box.locator('#npc-new-name').fill('Lamp Seller');
  await choose(box.locator('#npc-new-body'), 'minecraft:villager');
  await box.getByRole('button', {name: 'Create', exact: true}).click();
  await page.waitForFunction(() => decodeURIComponent(location.hash).endsWith('/npc/local/lamp-seller'));
  await changes(page, '1 change not applied');
  await topbarAction(page, 'Undo');
  await changes(page, 'Up to date');
  await backToList(page, workspace);
  await waitKeys(page, all, `${tag} new NPC undone`);
}

async function advanced(page, workspace, tag) {
  await setMode(page, 'advanced');
  await list(page).locator('.ui-library-pick').first().waitFor();
  assert.equal(await advancedLine(page, 'town/guard-north'), 'Player, world · npcs/town/guard-north · guard, patrol');
  await shot(page, `list-${tag}-advanced`);
  await noSidewaysScroll(page, `${tag} advanced`);

  // Tag chips narrow the list (any picked tag).
  const tags = page.getByRole('group', {name: 'Tags', exact: true});
  await tags.getByRole('button', {name: 'guard', exact: true}).click();
  await waitKeys(page, ['town/guard-north', 'town/guard-south'], `${tag} tag chip`);
  await tags.getByRole('button', {name: 'quest', exact: true}).click();
  await waitKeys(page, ['harbor/ferryman', 'town/guard-north', 'town/guard-south'], `${tag} two tag chips`);
  await tags.getByRole('button', {name: 'guard', exact: true}).click();
  await tags.getByRole('button', {name: 'quest', exact: true}).click();
  await waitKeys(page, all, `${tag} tag chips off`);

  // Tags: add one to three NPCs, then remove it; each is one undo step.
  await pick(page, ['town/baker', 'town/guard-north', 'town/guard-south']);
  await shot(page, `list-${tag}-bulk`);
  await bulk(page, 'Tags');
  let box = await dialog(page, 'npc-tags-dialog');
  await box.getByText('Tags for 3 NPCs').waitFor();
  await box.locator('#npc-tag').fill('Bad Tag');
  await box.getByRole('button', {name: 'Add tag', exact: true}).click();
  await box.getByText('Use 1-32 lowercase letters, digits, _ or -.').waitFor();
  await box.locator('#npc-tag').fill('night-watch');
  await shot(page, `list-${tag}-tags-dialog`);
  await box.getByRole('button', {name: 'Add tag', exact: true}).click();
  await closed(page, 'npc-tags-dialog');
  await page.getByText('Tagged 3 NPCs.').first().waitFor();
  assert.match(await advancedLine(page, 'town/baker'), /merchant, night-watch$/);
  assert.equal(await advancedLine(page, 'harbor/ferryman'), 'Player, harbor · npcs/harbor/ferryman · quest');
  await changes(page, '3 changes not applied');
  await bulk(page, 'Tags');
  box = await dialog(page, 'npc-tags-dialog');
  await box.getByRole('button', {name: 'Remove', exact: true}).click();
  await choose(box.locator('#npc-untag'), 'night-watch');
  await box.getByRole('button', {name: 'Remove tag', exact: true}).click();
  await closed(page, 'npc-tags-dialog');
  assert.match(await advancedLine(page, 'town/baker'), /merchant$/);
  await topbarAction(page, 'Undo');
  await page.waitForFunction(() => document.querySelector('.ui-library-row[data-key="npc:town/baker"] .ui-library-line')?.textContent.endsWith('night-watch'));
  await topbarAction(page, 'Undo');
  await changes(page, 'Up to date');

  // Folder: move two NPCs to a new folder typed as a path, then undo.
  await clearPicks(page);
  await pick(page, ['town/guard-north', 'town/guard-south']);
  await bulk(page, 'Folder');
  box = await dialog(page, 'npc-move-dialog');
  await box.locator('#npc-move-path').fill('Watch/Night/');
  await box.getByRole('button', {name: 'Move', exact: true}).click();
  await closed(page, 'npc-move-dialog');
  await waitKeys(page, all.map((id) => id.startsWith('town/guard') ? `watch/night/${id.slice(5)}` : id), `${tag} moved`);
  await topbarAction(page, 'Undo');
  await waitKeys(page, all, `${tag} move undone`);
  await clearPicks(page);

  // Module: combat off, on, then removed; each applied and read back.
  await pick(page, ['town/guard-north', 'town/guard-south']);
  await bulk(page, 'Module');
  box = await dialog(page, 'npc-module-dialog');
  await box.getByRole('button', {name: 'Off', exact: true}).click();
  await box.getByRole('button', {name: 'Turn off', exact: true}).click();
  await closed(page, 'npc-module-dialog');
  await page.getByText('Turned off animus:combat for 2 NPCs.').first().waitFor();
  await apply(page);
  assert.equal((await source('town/guard-north')).modules['animus:combat'].enabled, false);
  await bulk(page, 'Module');
  box = await dialog(page, 'npc-module-dialog');
  await box.getByRole('button', {name: 'On', exact: true}).click();
  await box.getByRole('button', {name: 'Turn on', exact: true}).click();
  await closed(page, 'npc-module-dialog');
  await apply(page);
  assert.equal((await source('town/guard-south')).modules['animus:combat'].enabled, true);
  await bulk(page, 'Module');
  box = await dialog(page, 'npc-module-dialog');
  await box.getByRole('button', {name: 'Remove', exact: true}).click();
  await shot(page, `list-${tag}-module-dialog`);
  await box.getByRole('button', {name: 'Remove module', exact: true}).click();
  await closed(page, 'npc-module-dialog');
  await apply(page);
  assert.equal((await source('town/guard-north')).modules['animus:combat'], undefined);
  await clearPicks(page);

  // Trait group: add two NPCs to "watch", then take them out.
  await pick(page, ['harbor/dockhand', 'harbor/ferryman']);
  await bulk(page, 'Trait group');
  box = await dialog(page, 'npc-group-dialog');
  await choose(box.locator('#npc-group'), 'watch');
  await box.getByRole('button', {name: 'Add to group', exact: true}).click();
  await closed(page, 'npc-group-dialog');
  await page.getByText('Assigned watch: 2 changed, 0 unchanged.').first().waitFor();
  await apply(page);
  assert.deepEqual((await source('harbor/dockhand')).groups, ['watch']);
  await bulk(page, 'Trait group');
  box = await dialog(page, 'npc-group-dialog');
  await box.getByRole('button', {name: 'Remove', exact: true}).click();
  await choose(box.locator('#npc-group'), 'watch');
  await box.getByRole('button', {name: 'Remove from group', exact: true}).click();
  await closed(page, 'npc-group-dialog');
  await apply(page);
  assert.equal((await source('harbor/ferryman')).groups, undefined);
  await clearPicks(page);

  // Delete asks first; Escape keeps the NPC, Delete removes it, Undo brings it back.
  await pick(page, ['crier']);
  await bulk(page, 'Delete');
  await page.getByRole('alertdialog').getByText('Delete 1 NPC?').waitFor();
  await shot(page, `list-${tag}-delete-confirm`);
  await page.keyboard.press('Escape');
  await page.getByRole('alertdialog').waitFor({state: 'detached'});
  await waitKeys(page, all, `${tag} delete kept`);
  await bulk(page, 'Delete');
  await confirm(page, 'Delete 1 NPC?', 'Delete');
  // On a server the row stays, marked, until Apply deletes it there.
  // Phones hide the row badges (the kit's list at 520 px and below).
  await row(page, 'crier').getByText('Deleted', {exact: true}).waitFor({state: 'attached'});
  await changes(page, '1 change not applied');
  await topbarAction(page, 'Undo');
  await row(page, 'crier').getByText('Deleted', {exact: true}).waitFor({state: 'detached'});
  await changes(page, 'Up to date');
  await clearPicks(page);

  // Spawn and Despawn act on the server now with the spawn scope; Despawn asks.
  await pick(page, ['harbor/dockhand', 'town/market/stalls/cartwright']);
  await bulk(page, 'Spawn');
  await page.getByText('Spawned 2 NPCs on the server.').first().waitFor();
  await bulk(page, 'Despawn');
  await page.getByRole('alertdialog').getByText('Despawn 2 NPCs now?').waitFor();
  await shot(page, `list-${tag}-despawn-confirm`);
  await confirm(page, 'Despawn 2 NPCs now?', 'Despawn');
  await page.getByText('Despawned 2 NPCs on the server.').first().waitFor();
  await changes(page, 'Up to date');
  await clearPicks(page);

  // Folder rename from the "..." menu: sent as moves that keep the revision chain.
  await headerMenu(page, 'Rename folder');
  box = await dialog(page, 'npc-folder-dialog');
  await choose(box.locator('#npc-folder-from'), 'town/market');
  await box.locator('#npc-folder-name').fill('bazaar');
  await shot(page, `list-${tag}-folder-dialog`);
  await box.getByRole('button', {name: 'Rename', exact: true}).click();
  await closed(page, 'npc-folder-dialog');
  await waitKeys(page, all.map((id) => id.replace('town/market/', 'town/bazaar/')), `${tag} folder renamed`);
  await apply(page);
  const moved = (await serverNpcs()).map((document) => document.id);
  assert.ok(moved.includes('town/bazaar/stalls/cartwright') && !moved.includes('town/market/fishmonger'), `the server has the moved paths: ${moved}`);
  assert.equal((await serverNpcs()).find((document) => document.id === 'town/bazaar/fishmonger').revision, 2, 'a move keeps the revision chain');

  // Folder move into another folder, then undo.
  await headerMenu(page, 'Move folder');
  box = await dialog(page, 'npc-folder-dialog');
  await choose(box.locator('#npc-folder-from'), 'wild');
  await choose(box.locator('#npc-folder-into'), 'harbor');
  await box.getByRole('button', {name: 'Move', exact: true}).click();
  await closed(page, 'npc-folder-dialog');
  await waitKeys(page, all.map((id) => id.replace('town/market/', 'town/bazaar/').replace('wild/', 'harbor/wild/')), `${tag} folder moved`);
  await topbarAction(page, 'Undo');
  await changes(page, 'Up to date');

  // New NPC in Advanced: folder and an id taken from the name.
  await page.getByTestId('npc-new').click();
  box = await dialog(page, 'npc-new-dialog');
  await box.locator('#npc-new-name').fill('Night Owl');
  assert.equal(await box.locator('#npc-new-id').inputValue(), 'night-owl');
  await choose(box.locator('#npc-new-folder'), 'harbor');
  await shot(page, `list-${tag}-new-advanced`);
  await box.getByRole('button', {name: 'Create', exact: true}).click();
  await page.waitForFunction(() => decodeURIComponent(location.hash).endsWith('/npc/harbor/night-owl'));
  await topbarAction(page, 'Undo');
  await changes(page, 'Up to date');
  await backToList(page, workspace);

  // Select all (Ctrl+A on the list), delete and apply: the empty state offers New NPC.
  await list(page).locator('.ui-library-rows').focus();
  await page.keyboard.press('Control+a');
  await page.locator('.ui-library-bulk .ui-library-count', {hasText: '10 picked'}).waitFor();
  await bulk(page, 'Delete');
  await confirm(page, 'Delete 10 NPCs?', 'Delete');
  await apply(page);
  await list(page).getByText('No NPCs yet.').waitFor();
  assert.equal((await serverNpcs()).length, 0);
  await shot(page, `list-${tag}-empty`);
  await list(page).getByRole('button', {name: 'New NPC', exact: true}).click();
  box = await dialog(page, 'npc-new-dialog');
  await box.getByRole('button', {name: 'Cancel', exact: true}).click();
  await closed(page, 'npc-new-dialog');
  await setMode(page, 'simple');
}

/** Without the spawn scope, Spawn and Despawn change the definitions, sent at Apply. */
async function withoutSpawn(browser, viewport, tag) {
  const {context, page, errors} = await pair(browser, viewport, {scopes: noSpawn});
  await setMode(page, 'advanced');
  await waitKeys(page, all, `${tag} no spawn scope`);
  await pick(page, ['harbor/dockhand']);
  await bulk(page, 'Spawn');
  await page.getByText('Spawned 1 NPC.').first().waitFor();
  await changes(page, '1 change not applied');
  await row(page, 'harbor/dockhand').getByText('Despawned', {exact: true}).waitFor({state: 'detached'});
  await bulk(page, 'Despawn');
  await row(page, 'harbor/dockhand').getByText('Despawned', {exact: true}).waitFor({state: 'attached'});
  assert.equal(await page.getByRole('alertdialog').count(), 0, 'a definition despawn does not ask');
  await changes(page, 'Up to date');
  await bulk(page, 'Spawn');
  await apply(page);
  assert.equal((await source('harbor/dockhand')).spawned, true);
  await setMode(page, 'simple');
  assert.deepEqual(errors, [], `${tag}: console errors without the spawn scope`);
  await context.close();
}

/** About 500 NPCs: listed at once, scrolled to the end, the last one opened, search timed per key. */
async function scale(browser) {
  const npcs = await serverNpcs(scaleFake);
  const viewport = {width: 1600, height: 1000, theme: 'dark'};
  const {context, page, errors, firstRows} = await pair(browser, viewport, {base: scaleFake});
  await list(page).locator('.ui-library-count', {hasText: `${npcs.length} NPCs`}).waitFor({timeout: 90000});
  const rows = list(page).locator('.ui-library-rows');
  await rows.focus();
  await page.keyboard.press('End');
  await page.waitForTimeout(200);
  const last = await page.locator('.ui-library-row.is-active').getAttribute('data-key');
  assert.ok(last && last.startsWith('npc:'), `the last row is active: ${last}`);
  assert.ok(await page.locator('.ui-library-row').count() < 60, 'rows are windowed');
  await page.keyboard.press('Enter');
  await page.waitForFunction((id) => decodeURIComponent(location.hash).endsWith(`/npc/${id}`), last.slice(4));
  await page.goBack();
  await list(page).waitFor();
  // Per key: from keydown to the frame after the list answered.
  await page.evaluate(() => {
    window.__keys = [];
    const input = document.querySelector('#npc-list .ui-library-search input');
    let start = 0;
    input.addEventListener('keydown', () => { start = performance.now(); }, true);
    document.addEventListener('input', () => {
      requestAnimationFrame(() => setTimeout(() => window.__keys.push(performance.now() - start), 0));
    });
  });
  const input = page.getByLabel('Search npcs', {exact: true});
  await input.click();
  for (const key of 'fishmonger') {
    await page.keyboard.type(key);
    await page.waitForTimeout(120);
  }
  const times = (await page.evaluate(() => window.__keys)).sort((a, b) => a - b);
  const median = times[Math.floor(times.length / 2)];
  console.log(`scale: ${npcs.length} NPCs, first rows ${firstRows} ms after pairing; search per key median ${median.toFixed(1)} ms, max ${times[times.length - 1].toFixed(1)} ms (${times.length} keys)`);
  assert.ok(median < 50, `a key in search costs ${median} ms`);
  assert.match(await count(page), /^\d+ of \d+ NPCs$/);
  assert.deepEqual(errors, [], 'scale: console errors');
  await context.close();
}

(async () => {
  const browser = await chromium.launch();
  try {
    for (const viewport of viewports) {
      const tag = `${viewport.width}-${viewport.theme}`;
      const {context, page, errors, workspace, firstRows} = await pair(browser, viewport);
      await simple(page, workspace, tag);
      await advanced(page, workspace, tag);
      assert.deepEqual(errors, [], `${tag}: console errors`);
      await context.close();
      if (viewport.width !== 768) await withoutSpawn(browser, viewport, tag);
      console.log(`${tag}: ok (first rows ${firstRows} ms after pairing)`);
    }
    if (scaleFake) await scale(browser);
    else console.log('scale: skipped (set ANIMUS_SCALE_URL to a fake server started with --scale=1200)');
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
