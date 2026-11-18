// The NPC form against the paired fake server (default --data): ids the
// server's lists lack are errors at their field and in the header; World
// is a dropdown of the loaded worlds; New NPC lists every body and starts
// where the pairing player stands, else on the terrain's ground; the feet
// inside a block are flagged with a fix; Duplicate asks for a name and
// saves the copy despawned; a new hologram line takes focus; modules read
// by name; Rides on writes nothing until its NPC is picked; anchors and
// named facings have their own fields; Seat on then off leaves no key;
// unknown equipment text stays with its error; Simple names villager
// professions; the New page's NPC is the dialog; Rigs counts rigs; a
// refused Apply keeps Apply and the toast copies the pair command; the
// Talk tab says who answers a click; Facing, Head tilt, Fights and Max
// health carry tips and no inline explanation. At 1600, 768 and 390 the
// Basics, Movement and Talk tabs do not scroll sideways, and no console
// errors.
//
//   python3 tool/serve_site.py --port <site port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<site port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<site port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/npc_forms_browser.cjs
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openJob, setMode, showView, saveAction, sidebar, closeDrawer} = require('./support/shell.cjs');
const {choose, openDropdown, optionLabels, optionValues} = require('./support/select.cjs');
const {npcTab} = require('./support/npc_tabs.cjs');

const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

async function shot(page, name) {
  if (shots) await page.screenshot({path: `${shots}/${name}.png`});
}

async function codeOf(page) {
  await showView(page, 'code');
  const text = await page.locator('.animus-code textarea').inputValue();
  await showView(page, 'visual');
  return JSON.parse(text);
}

async function useCode(page, change) {
  const json = await codeOf(page);
  change(json);
  await showView(page, 'code');
  await page.locator('.animus-code textarea').fill(`${JSON.stringify(json, null, 2)}\n`);
  await page.getByTestId('code-use').click();
  await showView(page, 'visual');
}

async function noSidewaysScroll(page, where) {
  const overflow = await page.evaluate(() => Math.max(
    document.documentElement.scrollWidth - window.innerWidth,
    (document.querySelector('#animus-main')?.scrollWidth || 0) - (document.querySelector('#animus-main')?.clientWidth || 0),
  ));
  assert.ok(overflow <= 1, `${where}: the page scrolls sideways by ${overflow}px`);
}

/** Pairs a fresh page with the fake server and returns the workspace id. */
async function pair(page, scopes) {
  const {code} = await control(`pair?paths=both${scopes ? `&scopes=${scopes}` : ''}`, {});
  await page.goto(`${url}/#/pair/${code}`);
  await page.getByTestId('pair-submit').waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  const serverId = decodeURIComponent((await page.evaluate(() => location.hash)).split('/')[2]);
  return `server-${serverId}`;
}

async function openNpc(page, workspace, id) {
  await page.goto(`${url}/#/workspace/${encodeURIComponent(workspace)}/npc/${encodeURIComponent(id)}`);
  await page.locator('.animus-npc-tabs').waitFor();
}

/** Whether the field labelled [label] in [scope] has an (i) tip beside its label. */
async function hasTip(scope, label) {
  const text = scope.locator('[data-slot="field-label"] > label, [data-slot="field-label"] > span').filter({hasText: new RegExp(`^\\s*${label}\\s*$`)}).first();
  await text.waitFor();
  return (await text.locator('xpath=..').locator('[data-animus-info]').count()) > 0;
}

function header(page) {
  return page.locator('.ui-page-header').first();
}

async function newNpc(page, workspace, name) {
  await page.goto(`${url}/#/workspace/${encodeURIComponent(workspace)}/npcs`);
  await page.getByRole('button', {name: 'New NPC', exact: true}).first().click();
  const dialog = page.getByRole('dialog', {name: 'New NPC'});
  await dialog.waitFor();
  await dialog.locator('#npc-new-name').fill(name);
  return dialog;
}

async function checks(page, workspace) {
  await setMode(page, 'advanced');

  // Ids the server's lists lack: an error at the field and in the header.
  await openNpc(page, workspace, 'examples/greeter');
  const basics = await npcTab(page, 'Basics');
  assert.deepEqual(await optionValues(basics.locator('#animus-npc-world')), ['world', 'world_nether'], 'World lists the loaded worlds');
  await choose(basics.locator('#animus-npc-world'), 'world_nether');
  await page.waitForTimeout(300);
  assert.equal((await codeOf(page)).position.world, 'world_nether');
  await useCode(page, (json) => { json.type = 'minecraft:lama'; json.position.world = 'no_such_world'; });
  const body = await npcTab(page, 'Basics');
  await body.getByText('Lama is not a body on this server.').waitFor();
  await body.getByText('World no_such_world is not loaded on the server.').waitFor();
  await header(page).getByText('2 problems').waitFor();
  await shot(page, 'w3-unknown-body-world');
  await useCode(page, (json) => {
    json.type = 'minecraft:player';
    json.position.world = 'world';
    json.modules = {...(json.modules || {}), 'animus:combat': {targets: {entityTypes: ['minecraft:zombie', 'zombe_king']}}};
  });
  const combat = await npcTab(page, 'Combat');
  await combat.getByText('zombe_king is not a mob type on this server.').first().waitFor();
  await header(page).getByText('1 problem').waitFor();
  await shot(page, 'w3-unknown-mob-type');
  await useCode(page, (json) => { delete json.modules['animus:combat']; });
  await header(page).getByText('Valid').waitFor();

  // Facing and Head tilt explain in their tips; nothing inline.
  const where = await npcTab(page, 'Basics');
  assert.equal(await where.getByText(/0 south, 90 west/).filter({visible: true}).count(), 0, 'Facing has no inline explanation');
  assert.equal(await where.getByText(/-90 looks up/).filter({visible: true}).count(), 0, 'Head tilt has no inline explanation');
  for (const label of ['Facing', 'Head tilt', 'Position']) assert.ok(await hasTip(where, label), `${label} has a tip`);

  // The feet inside a block are flagged, and Stand on ground fixes them.
  await useCode(page, (json) => { json.position.x = 0.5; json.position.y = 64; json.position.z = -1.5; });
  const stand = (await npcTab(page, 'Basics')).locator('[data-npc="stand-check"]');
  await stand.getByText(/Its feet are inside a block\. The ground here is at Y 65\./).waitFor();
  await shot(page, 'w11-inside-block');
  await stand.getByRole('button', {name: 'Stand on ground'}).click();
  await stand.waitFor({state: 'detached'});
  assert.equal((await codeOf(page)).position.y, 65);

  // Anchors and named facings get their own fields.
  const extras = await npcTab(page, 'Basics');
  const places = extras.locator('details', {has: page.locator(':scope > summary', {hasText: 'Facings and anchors'})}).first();
  if (!(await places.evaluate((details) => details.open))) await places.locator(':scope > summary').click();
  await extras.getByRole('button', {name: 'Add anchor'}).click();
  await extras.getByRole('button', {name: 'Add facing'}).click();
  await page.waitForTimeout(300);
  let json = await codeOf(page);
  assert.deepEqual(json.anchors, {anchor: {x: 0.5, y: 65, z: -1.5}});
  assert.equal(Object.keys(json.poses)[0], 'facing');
  const placed = await npcTab(page, 'Basics');
  assert.equal(await placed.getByText(/expected object/).count(), 0, 'no Key: expected object');
  await placed.locator('#animus-npc-anchor-0-name').fill('gate');
  await page.waitForTimeout(300);
  assert.deepEqual(Object.keys((await codeOf(page)).anchors), ['gate']);
  await placed.locator('[data-npc="anchors"]').scrollIntoViewIfNeeded();
  await shot(page, 'w20-anchors-facings');

  // Modules read by name, with a line each.
  const modules = await npcTab(page, 'Modules');
  const labels = await optionLabels(modules.locator('#animus-npc-add-module'));
  for (const name of ['Looks at players', 'Returns home', 'Players can ride it', 'Schedule', 'Mirrors the viewer']) assert.ok(labels.includes(name), `module picker offers ${name}: ${labels}`);
  assert.ok(!labels.some((label) => label && label.startsWith('animus:')), `no raw ids: ${labels}`);
  await openDropdown(modules.locator('#animus-npc-add-module'));
  await page.getByText('Turns toward the nearest player in range.').first().waitFor();
  await shot(page, 'w20-module-picker');
  await page.keyboard.press('Escape');

  // Rides on writes nothing until its NPC is picked.
  const movement = await npcTab(page, 'Movement');
  const ridesOn = movement.locator('details', {hasText: 'Rides on'}).first();
  await ridesOn.getByText('Rides on').first().waitFor();
  if (!(await ridesOn.getAttribute('open'))) await ridesOn.locator('summary').click();
  await ridesOn.getByRole('radio', {name: 'Another NPC'}).or(ridesOn.getByRole('button', {name: 'Another NPC'})).first().click();
  await page.waitForTimeout(300);
  json = await codeOf(page);
  assert.equal((json.modules || {})['animus:mount'], undefined, 'Another NPC alone writes nothing');
  const moved = await npcTab(page, 'Movement');
  const rides = moved.locator('details', {hasText: 'Rides on'}).first();
  if (!(await rides.getAttribute('open'))) await rides.locator('summary').click();
  await rides.getByRole('radio', {name: 'Another NPC'}).or(rides.getByRole('button', {name: 'Another NPC'})).first().click();
  assert.equal(await rides.getByText('Rides now').count(), 0, 'Rides now stays off screen until an NPC is picked');
  await rides.scrollIntoViewIfNeeded();
  await shot(page, 'w20-rides-on-pending');
  await choose(rides.locator('#animus-npc-vehicle'), 'shopkeeper');
  await page.waitForTimeout(300);
  json = await codeOf(page);
  assert.deepEqual(json.modules['animus:mount'], {vehicle: 'shopkeeper'});

  // Seat on, then off, leaves no key.
  const seatTab = await npcTab(page, 'Movement');
  const seat = seatTab.locator('details', {hasText: 'Seat'}).last();
  if (!(await seat.getAttribute('open'))) await seat.locator('summary').click();
  await seat.getByRole('switch', {name: 'Players sit'}).click();
  await page.waitForTimeout(200);
  await seat.getByRole('switch', {name: 'Players sit'}).click();
  await page.waitForTimeout(300);
  assert.equal(((await codeOf(page)).modules || {})['animus:seat'], undefined, 'no enabled: false left behind');

  // Fights and Max health explain themselves in Simple too.
  await setMode(page, 'simple');
  await useCode(page, (it) => { it.modules = {...(it.modules || {}), 'animus:health': {killable: true}}; });
  await setMode(page, 'simple');
  const fights = await npcTab(page, 'Combat');
  for (const label of ['Fights', 'Can be hurt', 'Max health']) assert.ok(await hasTip(fights, label), `${label} has a tip in Simple`);
  await setMode(page, 'advanced');
  await useCode(page, (it) => { delete it.modules['animus:health']; });

  // Simple: names, not ids.
  await setMode(page, 'simple');
  await useCode(page, (it) => { it.type = 'minecraft:villager'; it.villager = {profession: 'librarian'}; });
  await setMode(page, 'simple');
  const look = await npcTab(page, 'Look');
  const profession = look.locator('[data-settings="villager"]').getByRole('combobox').nth(1);
  await profession.waitFor();
  assert.match(await profession.innerText(), /Librarian/, 'Simple names the profession');
  await shot(page, 'w36-villager-names');
  await setMode(page, 'advanced');
  await useCode(page, (it) => { it.type = 'minecraft:player'; delete it.villager; });

  // A new hologram line takes focus, so typing goes into it.
  await setMode(page, 'simple');
  const holo = await npcTab(page, 'Look');
  // The form settles after the page opens; a click while it redraws can land on the old button.
  await page.waitForTimeout(600);
  const addText = holo.getByRole('button', {name: 'Add text'});
  if (await addText.count()) await addText.click();
  else await holo.getByRole('button', {name: 'Add line'}).click();
  await page.waitForFunction(() => document.activeElement && /animus-hologram-line-/.test(document.activeElement.id));
  const before = await page.evaluate(() => location.hash);
  await page.keyboard.type('nb');
  await page.waitForTimeout(300);
  assert.equal(await page.evaluate(() => location.hash), before, 'N did not open New');
  assert.equal(await page.locator('[role="dialog"]').filter({visible: true}).count(), 0, 'typing opened nothing');
  assert.equal(await page.evaluate(() => document.activeElement.value), 'nb');
  await shot(page, 'w10-hologram-focus');

  // Unknown equipment text stays with an error.
  await setMode(page, 'simple');
  const equipment = await npcTab(page, 'Look');
  await equipment.locator('[data-slot="main_hand"]').click();
  const picker = equipment.locator('[data-editing="main_hand"] input[role="combobox"]');
  await picker.fill('notanitem_xyz');
  await page.keyboard.press('Tab');
  await equipment.getByTestId('item-unmatched').waitFor();
  assert.equal(await picker.inputValue(), 'notanitem_xyz', 'the typed text stays');
  assert.match(await equipment.getByTestId('item-unmatched').innerText(), /No item is called notanitem_xyz/);
  await shot(page, 'w36-equipment-unknown');

  await setMode(page, 'advanced');

  // Who answers a click: the greeter's behavior, then the shop; a GUI on the same click never opens.
  await openNpc(page, workspace, 'examples/greeter');
  await useCode(page, (it) => {
    it.modules = {...(it.modules || {}), 'animus:shop': {shop: 'shop:bakery'}, 'animus:gui': {open: {kind: 'inventory', id: 'shop:counter'}}};
  });
  const talk = await npcTab(page, 'Talk and trade');
  const answers = talk.locator('[data-npc="click-answers"]');
  await answers.waitFor();
  assert.match(await answers.innerText(), /^Right-click starts Greeter, then opens .+\. The GUI never gets it\.$/m);
  assert.equal(await answers.locator('[data-problem="warning"]').count(), 1, 'the shop and GUI on one click read as a warning');
  await answers.scrollIntoViewIfNeeded();
  await shot(page, 'w23-click-answers');
  await useCode(page, (it) => { delete it.modules['animus:shop']; delete it.modules['animus:gui']; });

  // Duplicate asks for a name and saves the copy despawned.
  await openNpc(page, workspace, 'examples/greeter');
  await header(page).locator('button[aria-haspopup="menu"], .ui-page-header-more button').last().click();
  await page.getByRole('menuitem', {name: 'Duplicate'}).click();
  const copy = page.getByRole('dialog');
  await copy.locator('#ui-doc-copy-name').fill('Greeter Two');
  assert.equal(await copy.locator('#ui-doc-id-input').inputValue(), 'examples/greeter-two');
  await shot(page, 'w12-duplicate-dialog');
  await copy.getByRole('button', {name: 'Duplicate', exact: true}).click();
  await page.waitForFunction(() => location.hash.includes('greeter-two'));
  json = await codeOf(page);
  assert.equal(json.name, 'Greeter Two');
  assert.equal(json.id, 'greeter-two');
  assert.equal(json.spawned, false);

  // New NPC: every body, searchable; where the pairing player stands.
  await control('admin-position', {world: 'world_nether', x: 12.345, y: 70, z: -3.5, yaw: 91.27});
  let dialog = await newNpc(page, workspace, 'Lamb Herder');
  const bodies = await openDropdown(dialog.locator('#npc-new-body'));
  const count = await bodies.locator('[role="option"]').count();
  assert.ok(count > 100, `every body: ${count}`);
  await page.getByPlaceholder('Search').filter({visible: true}).first().fill('llama');
  await bodies.locator('[role="option"][data-arcane-value="minecraft:llama"]').filter({visible: true}).waitFor();
  await shot(page, 'w3-new-npc-body');
  await bodies.locator('[role="option"][data-arcane-value="minecraft:llama"]').click();
  await dialog.getByRole('button', {name: 'Create', exact: true}).click();
  await page.waitForFunction(() => location.hash.includes('lamb-herder'));
  json = await codeOf(page);
  assert.equal(json.type, 'minecraft:llama');
  assert.deepEqual(json.position, {world: 'world_nether', x: 12.35, y: 70, z: -3.5, yaw: 91.3, pitch: 0});

  // Without the player, the NPC stands on the terrain's ground.
  await control('admin-position', {});
  dialog = await newNpc(page, workspace, 'Ground Check');
  await dialog.getByRole('button', {name: 'Create', exact: true}).click();
  await page.waitForFunction(() => location.hash.includes('ground-check'));
  json = await codeOf(page);
  assert.equal(json.position.y, 65, 'feet on the grass, not in it');

  // The New page's NPC is the same dialog.
  await page.goto(`${url}/#/workspace/${encodeURIComponent(workspace)}/new/npc`);
  await page.getByRole('dialog', {name: 'New NPC'}).waitFor();
  assert.equal(await page.getByLabel('Yaw', {exact: true}).count(), 0);
  await page.keyboard.press('Escape');

  // Rigs counts rigs only.
  const nav = await sidebar(page);
  const rigs = nav.getByRole('link', {name: /Rigs/}).first();
  assert.match(await rigs.innerText(), /Rigs\s*0/, 'no rig in this workspace');
  await closeDrawer(page);
}

async function refused(page) {
  // A pairing without Edit NPCs: Apply is refused and stays, and the toast copies the long command.
  const workspace = await pair(page, 'read,validate,apply:gui');
  await openNpc(page, workspace, 'examples/greeter');
  await page.locator('#animus-npc-name').fill('Greeter Changed');
  await page.waitForTimeout(400);
  await saveAction(page, 'apply').click();
  await page.getByText(/Apply failed: This pairing lacks the Edit NPCs permission\./).waitFor();
  await saveAction(page, 'apply').waitFor();
  await page.getByRole('button', {name: 'Copy command'}).waitFor();
  await shot(page, 'w36-refused-apply');
}

async function widths(page, workspace) {
  for (const [width, height] of [[1600, 1000], [768, 1024], [390, 844]]) {
    await page.setViewportSize({width, height});
    await openNpc(page, workspace, 'examples/shopkeeper');
    await setMode(page, 'advanced');
    for (const tab of ['Basics', 'Movement', 'Talk and trade', 'Modules']) {
      const panel = await npcTab(page, tab);
      for (const section of await panel.locator('details:not([open]) > summary').all()) await section.click().catch(() => {});
      await page.waitForTimeout(200);
      await noSidewaysScroll(page, `${width}px ${tab}`);
      await shot(page, `widths-${width}-${tab.replace(/\W+/g, '-').toLowerCase()}`);
    }
  }
  await page.setViewportSize({width: 1600, height: 1000});
}

(async () => {
  const browser = await chromium.launch({args: ['--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessPermissionPrompt,BlockInsecurePrivateNetworkRequests']});
  try {
    await control('reset', {});
    const context = await browser.newContext({viewport: {width: 1600, height: 1000}});
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()); });
    const workspace = await pair(page);
    await checks(page, workspace);
    await widths(page, workspace);
    assert.deepEqual(errors.filter((text) => !/Failed to load resource|favicon/.test(text)), [], 'console errors');
    await control('reset', {});
    const other = await (await browser.newContext({viewport: {width: 1600, height: 1000}})).newPage();
    other.setDefaultTimeout(15000);
    await refused(other);
    console.log('npc_forms_browser: ok');
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
