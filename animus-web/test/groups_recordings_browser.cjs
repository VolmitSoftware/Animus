// Opens test/fixtures/groups_recordings in the built site at 1440x900 and
// 390x844, dark and light, and checks the trait group, recording and random
// route screens: the NPC browser's group form assigning a group to a folder
// and undoing it in one step, unassigning by tag and by selection; an NPC's
// Groups chips and Effective part (each value with its source, a link to the
// group that set it), reordering by keyboard, "+ New group" from the search
// text in one undo step; the group editor (description, member chips and
// picker, values parts); revision read only in the header;
// the recording view (facts, path, scrub, play, events), its details form
// (description, tags, playback, speed), trimming the start and end on the
// scrub, Code Apply refusing a draft that breaks a recording rule and
// applying a valid one, renaming it, and the recordings list; an NPC playing a recording with its playback mode;
// and the route editor's ranged waits, seed, start offset, jitter, weights
// and junctions. No console errors and no sideways scrolling anywhere.
//
//   python3 tool/serve_site.py --port <WEB_PORT> &
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<WEB_PORT> ANIMUS_SCREENSHOTS=<scratch folder> \
//     node test/groups_recordings_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {sidebar, topbarAction} = require('./support/shell.cjs');
const {choose, chooseAndWait, openDropdown, valueOf} = require('./support/select.cjs');

const folder = path.resolve(__dirname, 'fixtures/groups_recordings');
const site = process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })();
const shots = process.env.ANIMUS_SCREENSHOTS;

async function shot(page, name) {
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: true});
}

async function noSidewaysScroll(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  assert.ok(overflow <= 1, `${where}: the page scrolls sideways by ${overflow}px`);
}

async function codeOf(page) {
  await page.getByRole('tab', {name: 'Code'}).click();
  const text = await page.locator('.animus-code textarea').inputValue();
  await page.getByRole('tab', {name: 'Visual'}).click();
  return JSON.parse(text);
}

/** Opens an inspector part (closed by default on phones) and returns it. */
async function part(page, id) {
  const details = page.locator(`details[data-section="${id}"]`);
  if (!(await details.evaluate((element) => element.open))) await details.locator('summary').first().click();
  return details;
}

async function openDocument(page, workspace, kind, id) {
  await page.evaluate((hash) => { location.hash = hash; }, `#/workspace/${workspace}/${kind}/${encodeURIComponent(id)}`);
  await page.locator('#animus-main h1').waitFor();
}

async function settle(page) {
  await page.waitForTimeout(250);
}

async function run(browser, viewport, theme, tag) {
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
  await page.getByRole('heading', {name: 'Every document passes the shared schemas'}).waitFor();
  const workspace = /#\/workspace\/([^/?]+)/.exec(page.url())[1];

  // NPC browser: assign a group to one folder, undo it in one step.
  await (await sidebar(page)).locator('.animus-tree-browse').first().click();
  const form = page.locator('[data-npc="group-assign"]');
  await form.waitFor();
  await chooseAndWait(page.locator('#animus-group-assign-group'), 'town-guards');
  await chooseAndWait(page.locator('#animus-group-assign-by'), 'folder');
  await chooseAndWait(page.locator('#animus-group-assign-folder'), 'town');
  await form.locator('[data-npc="group-assign-reach"]').getByText('3 NPCs affected', {exact: false}).waitFor();
  await shot(page, `${tag}-01-group-assign`);
  await noSidewaysScroll(page, `${tag} group assign`);
  await page.locator('#animus-group-assign').click();
  await page.getByText('Assigned town-guards: 2 changed, 1 unchanged.', {exact: false}).first().waitFor();
  await openDocument(page, workspace, 'npc', 'town/watchman');
  assert.deepEqual((await codeOf(page)).groups, ['town-guards']);
  await topbarAction(page, 'Undo');
  await settle(page);
  assert.equal((await codeOf(page)).groups, undefined, 'one undo reverts the assign');
  await openDocument(page, workspace, 'npc', 'town/stall-keeper');
  assert.equal((await codeOf(page)).groups, undefined, 'the same undo step covers every NPC');

  // Unassign by effective tag: gate-guard takes "guard" from town-guards.
  await page.evaluate((hash) => { location.hash = hash; }, `#/workspace/${workspace}/npcs`);
  await form.waitFor();
  await chooseAndWait(page.locator('#animus-group-assign-group'), 'harbor');
  await chooseAndWait(page.locator('#animus-group-assign-by'), 'tag');
  await chooseAndWait(page.locator('#animus-group-assign-tag'), 'guard');
  await page.locator('#animus-group-unassign').click();
  await page.getByText('Unassigned harbor: 1 changed, 1 unchanged.', {exact: false}).first().waitFor();
  await topbarAction(page, 'Undo');
  await settle(page);

  // By selection.
  await page.getByRole('checkbox', {name: 'Select harbor/porter', exact: true}).check();
  await chooseAndWait(page.locator('#animus-group-assign-by'), 'selection');
  await page.locator('#animus-group-unassign').click();
  await page.getByText('Unassigned harbor: 1 changed, 0 unchanged.', {exact: false}).first().waitFor();
  await topbarAction(page, 'Undo');
  await settle(page);

  // NPC with groups: Groups list and Effective part.
  await openDocument(page, workspace, 'npc', 'town/gate-guard');
  const identity = await part(page, 'identity');
  assert.deepEqual(await identity.locator('[data-npc="groups"] li[data-group]').evaluateAll((items) => items.map((item) => item.dataset.group)), ['town-guards', 'harbor']);
  const effective = await part(page, 'effective');
  const source = (field) => effective.locator(`tr[data-path="${field}"]`).getAttribute('data-source');
  assert.equal(await source('teamColor'), 'npc');
  assert.equal(await source('equipment'), 'town-guards');
  assert.equal(await source('hologram'), 'harbor');
  assert.equal(await source('modules.animus:look-close'), 'harbor');
  await shot(page, `${tag}-02-effective`);
  await noSidewaysScroll(page, `${tag} effective`);
  // The revision is shown, never edited.
  assert.equal(await page.getByRole('spinbutton', {name: /revision/i}).count(), 0, 'no revision input');
  await page.locator('.animus-document-header .animus-document-revision', {hasText: /^revision \d+$/}).waitFor();
  // Moving harbor first (Alt+Left on its chip) makes town-guards win look-close.
  await identity.getByRole('button', {name: 'Open group harbor'}).focus();
  await page.keyboard.press('Alt+ArrowLeft');
  await settle(page);
  assert.equal(await source('modules.animus:look-close'), 'town-guards');
  await topbarAction(page, 'Undo');
  await settle(page);
  await effective.getByRole('button', {name: 'Open group harbor, which sets hologram'}).click();
  await page.locator('[aria-label="Group inspector"]').waitFor();

  // Group editor.
  const group = await part(page, 'group');
  assert.ok(await group.locator('[data-group="members"] [data-member="town/gate-guard"]').count());
  await page.locator('#animus-group-description').fill('Harbor colors only');
  await settle(page);
  assert.equal((await codeOf(page)).description, 'Harbor colors only');
  // Adding a member from the group screen writes the NPC's groups; Undo takes it back.
  await choose(group.locator('#animus-group-add-member'), 'town/watchman');
  await group.locator('[data-group="members"] [data-member="town/watchman"]').waitFor();
  await topbarAction(page, 'Undo');
  await settle(page);
  assert.equal(await group.locator('[data-group="members"] [data-member="town/watchman"]').count(), 0, 'Undo removes the added member');
  await part(page, 'appearance');
  await shot(page, `${tag}-03-group`);
  await noSidewaysScroll(page, `${tag} group`);

  // Adding a group from the NPC's own list.
  await openDocument(page, workspace, 'npc', 'town/watchman');
  await part(page, 'identity');
  await choose(page.locator('#animus-npc-add-group'), 'harbor');
  await settle(page);
  assert.deepEqual((await codeOf(page)).groups, ['harbor']);
  await part(page, 'effective');
  await topbarAction(page, 'Undo');
  await settle(page);
  // "+ New group" makes the group from the search text and lists it, one undo step.
  const adder = await openDropdown(page.locator('#animus-npc-add-group'));
  await page.locator('#animus-npc-add-group-search').fill('Dock crew');
  await adder.getByText('+ New group "Dock crew"').click();
  const made = (await part(page, 'identity')).getByRole('status').filter({hasText: 'Made group dock-crew.'});
  await made.waitFor();
  await made.getByRole('button', {name: 'Open dock-crew'}).waitFor();
  await shot(page, `${tag}-03b-new-group`);
  assert.deepEqual((await codeOf(page)).groups, ['dock-crew']);
  assert.equal(await page.locator('#animus-sidebar').getByText('dock-crew', {exact: true}).count(), 1, 'the group document exists');
  await topbarAction(page, 'Undo');
  await settle(page);
  assert.equal((await codeOf(page)).groups, undefined, 'one undo drops the list entry');
  assert.equal(await page.locator('#animus-sidebar').getByText('dock-crew', {exact: true}).count(), 0, 'and the group');

  // Random route options on the watchman's inline route.
  const behaviour = await part(page, 'behaviour');
  const gridId = async () => (await part(page, 'behaviour')).locator('svg.animus-route-grid').first().getAttribute('id');
  let grid = await gridId();
  assert.equal(await page.locator(`#${grid}-wait-range`).getAttribute('aria-pressed'), 'true');
  assert.equal(await page.locator(`#${grid}-wait`).inputValue(), '20');
  assert.equal(await page.locator(`#${grid}-wait-max`).inputValue(), '60');
  await page.locator(`#${grid}-wait-max`).fill('10');
  await page.locator(`#${grid}-wait-max`).blur();
  await behaviour.getByText('min above max').first().waitFor();
  await page.locator(`#${grid}-wait-max`).fill('90');
  await page.locator(`#${grid}-wait-max`).blur();
  await settle(page);
  assert.deepEqual((await codeOf(page)).modules['animus:navigation'].waitTicks, [20, 90]);
  grid = await gridId();
  assert.equal(await valueOf(page.locator(`#${grid}-start`)), 'random');
  await chooseAndWait(page.locator(`#${grid}-seed`), 'random');
  await settle(page);
  assert.equal((await codeOf(page)).modules['animus:navigation'].seed, 'random');
  grid = await gridId();
  await page.locator(`#${grid}-points-0`).focus();
  await page.keyboard.press('Enter');
  await page.locator(`#${grid}-next-0-to`).waitFor();
  await page.locator(`#${grid}-next-add`).click();
  await settle(page);
  assert.equal((await codeOf(page)).modules['animus:navigation'].points[0].next.length, 3);
  await shot(page, `${tag}-04-route`);
  await noSidewaysScroll(page, `${tag} route`);

  // An NPC playing a recording.
  await openDocument(page, workspace, 'npc', 'town/stall-keeper');
  await part(page, 'behaviour');
  assert.equal(await valueOf(page.locator('#animus-npc-nav-source')), 'recording');
  assert.equal(await valueOf(page.locator('#animus-npc-playback')), 'pingpong');
  await chooseAndWait(page.locator('#animus-npc-playback'), 'once');
  await settle(page);
  assert.deepEqual((await codeOf(page)).modules['animus:navigation'], {enabled: true, recording: 'market-loop', playback: 'once'});
  await part(page, 'behaviour');
  await page.locator('#animus-npc-open-recording').click();

  // The recording view.
  await page.locator('#animus-recording-path').waitFor();
  assert.match(await page.locator('[data-recording="facts"]').textContent(), /80 ticks/);
  await page.locator('#animus-recording-scrub').fill('40');
  await page.locator('[data-recording="readout"]').getByText('tick 40', {exact: false}).waitFor();
  const at40 = await page.locator('#animus-recording-path').getAttribute('data-marker-x');
  assert.equal(Number(at40), 103.7);
  await page.locator('#animus-recording-play').click();
  assert.equal(await page.locator('#animus-recording-play').getAttribute('aria-pressed'), 'true');
  await page.locator('#animus-recording-play').click();
  assert.equal(await page.locator('#animus-recording-play').getAttribute('aria-pressed'), 'false');
  await shot(page, `${tag}-05-recording`);
  await noSidewaysScroll(page, `${tag} recording`);
  assert.equal(await page.getByRole('button', {name: 'Duplicate'}).count(), 0);

  // Details.
  const details = page.locator('[data-recording="details"]');
  await details.waitFor();
  await page.locator('#animus-recording-description').fill('Stall walk');
  await page.locator('#animus-recording-tag').fill('market');
  await page.locator('#animus-recording-add-tag').click();
  await details.locator('[data-tag="market"]').waitFor();
  assert.equal(await page.locator('#animus-recording-tag').inputValue(), '');
  await chooseAndWait(page.locator('#animus-recording-playback'), 'once');
  await page.locator('#animus-recording-speed').fill('5');
  await details.getByText('Use 0.25 to 4.').waitFor();
  await page.locator('#animus-recording-speed').fill('2');
  await page.locator('#animus-recording-speed').blur();
  await settle(page);
  let json = await codeOf(page);
  assert.equal(json.description, 'Stall walk');
  assert.deepEqual(json.tags, ['market']);
  assert.equal(json.playback, 'once');
  assert.equal(json.speed, 2);
  await noSidewaysScroll(page, `${tag} recording details`);

  // Trim to ticks 20..60 from the scrub, keyboard first: focus the scrub,
  // step it, then press the buttons with Enter.
  await page.locator('#animus-recording-scrub').fill('19');
  await page.locator('#animus-recording-trim-start').focus();
  await page.keyboard.press('Enter');
  await page.locator('#animus-recording-scrub').fill('62');
  await page.locator('#animus-recording-trim-end').click();
  await page.locator('[data-recording="trim-range"]').getByText('Keep tick 20 to 60: 40 ticks, 3 of 5 frames').waitFor();
  assert.equal(await page.locator('.animus-recording-line.is-kept').count(), 1);
  await shot(page, `${tag}-05b-recording-trim`);
  await noSidewaysScroll(page, `${tag} recording trim`);
  await page.locator('#animus-recording-trim-apply').click();
  await page.locator('[data-recording="facts"]').getByText('40 ticks', {exact: false}).waitFor();
  json = await codeOf(page);
  assert.equal(json.durationTicks, 40);
  assert.deepEqual(json.frames.map((frame) => frame[0]), [0, 20, 40]);
  assert.deepEqual(json.origin, {x: 102, y: 64, z: -20.25, yaw: 92.5, pitch: -3});
  assert.equal(json.events[0].slot, 'main_hand', 'the sword worn at the new start is kept');

  // Code: a draft that breaks a rule is refused with the reason; a valid one applies.
  await page.getByRole('tab', {name: 'Code'}).click();
  const code = page.locator('.animus-code textarea');
  await code.fill(JSON.stringify({...json, frames: json.frames.map((frame, index) => index === 1 ? [0, ...frame.slice(1)] : frame)}, null, 2));
  await page.locator('[data-recording="code-refusal"]').getByText("$.frames[1][0]: expected a tick above the previous frame's 0", {exact: false}).waitFor();
  assert.equal(await page.getByRole('button', {name: 'Apply to draft'}).isDisabled(), true);
  await shot(page, `${tag}-05c-recording-code`);
  await noSidewaysScroll(page, `${tag} recording code`);
  await code.fill(JSON.stringify({...json, description: 'Coded'}, null, 2));
  assert.equal(await page.locator('[data-recording="code-refusal"]').count(), 0);
  await page.getByRole('button', {name: 'Apply to draft'}).click();
  await settle(page);
  await page.getByRole('tab', {name: 'Visual'}).click();
  assert.equal(await page.locator('#animus-recording-description').inputValue(), 'Coded');

  // Rename from the id field carries the NPC that plays it.
  await page.locator('#animus-recording-id').fill('market-walk');
  await page.locator('#animus-recording-rename').click();
  await page.waitForFunction(() => location.hash.endsWith('/recording/market-walk'));
  await openDocument(page, workspace, 'npc', 'town/stall-keeper');
  assert.equal((await codeOf(page)).modules['animus:navigation'].recording, 'market-walk');

  // The recordings list.
  await page.evaluate((hash) => { location.hash = hash; }, `#/workspace/${workspace}/recordings`);
  await page.locator('[data-recording="list"] tr[data-recording-id="market-walk"]').waitFor();
  await shot(page, `${tag}-06-recordings`);
  await noSidewaysScroll(page, `${tag} recordings list`);

  assert.deepEqual(errors, [], `${tag}: console errors`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    for (const [name, viewport] of [['desktop', {width: 1440, height: 900}], ['phone', {width: 390, height: 844}]]) {
      for (const theme of ['dark', 'light']) await run(browser, viewport, theme, `${name}-${theme}`);
    }
  } finally {
    await browser.close();
  }
  console.log('groups_recordings_browser: ok');
})().catch((error) => { console.error(error.stack || error.message || error); process.exit(1); });
