// Opens test/fixtures/groups_recordings in the built site at 1440x900 and
// 390x844, dark and light, and checks the trait group screens: the NPC
// list's Trait group action adding a folder's NPCs (filter, Ctrl+A) and
// undoing it in one step, then taking a picked NPC out; an NPC's trait group
// chips and what it takes (each value with its source, a link to the group
// that set it), reordering by keyboard, "+ New group" from a typed name in
// one undo step; the group's Members tab (description, the member list, Add
// members); no revision input; and an NPC playing a recording with its
// playback mode. Routes and recordings are checked by
// test/movement_browser.cjs. No console errors and no sideways scrolling
// anywhere.
//
//   python3 tool/serve_site.py --port <WEB_PORT> &
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<WEB_PORT> ANIMUS_SCREENSHOTS=<scratch folder> \
//     node test/groups_recordings_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {topbarAction, showView, openJob, setMode, workspaceOpen} = require('./support/shell.cjs');
const {npcTab} = require('./support/npc_tabs.cjs');
const {choose, chooseAndWait, openDropdown} = require('./support/select.cjs');

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
  await showView(page, 'code');
  const text = await page.locator('.animus-code textarea').inputValue();
  await showView(page, 'visual');
  return JSON.parse(text);
}

/**
 * The NPC or group tab that holds an old inspector part, with its section
 * opened: identity and effective on Basics (Advanced), the group's members
 * on Members, appearance on Look, routes on Movement.
 */
async function part(page, id) {
  const tab = {identity: 'Basics', effective: 'Basics', group: 'Members', appearance: 'Look', behaviour: 'Movement'}[id];
  const panel = await npcTab(page, tab);
  const section = {identity: '[data-npc="trait-groups"]', effective: '[data-npc="effective"]'}[id];
  if (!section) return panel;
  const details = panel.locator(`${section} > details`);
  await details.waitFor();
  if (!(await details.evaluate((element) => element.open))) await details.locator(':scope > summary').first().click();
  return panel.locator(section);
}

/** Runs a bulk bar action of the NPC list, from the bar or its "..." menu when it did not fit. */
async function bulkAction(page, name) {
  const bar = page.locator('.ui-library-bulk');
  await page.waitForTimeout(250);
  const button = bar.getByRole('button', {name, exact: true});
  if (await button.isVisible().catch(() => false)) {
    await button.click();
    return;
  }
  await bar.getByRole('button', {name: 'More actions'}).click();
  await page.getByRole('menuitem', {name, exact: true}).click();
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
  await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(folder);
  await workspaceOpen(page);
  const workspace = /#\/workspace\/([^/?]+)/.exec(page.url())[1];

  // NPC list (Advanced): filter to one folder, pick them all, add a trait group, undo it in one step.
  await setMode(page, 'advanced');
  await openJob(page, 'NPCs');
  const npcRows = page.locator('#npc-list .ui-library-rows');
  await npcRows.locator('.ui-library-row').first().waitFor();
  await chooseAndWait(page.locator('#npc-filter-folder'), 'town');
  await npcRows.focus();
  await page.keyboard.press('Control+a');
  await page.locator('.ui-library-bulk .ui-library-count', {hasText: '3 picked'}).waitFor();
  await bulkAction(page, 'Trait group');
  const groupDialog = page.locator('#npc-group-dialog');
  await chooseAndWait(groupDialog.locator('#npc-group'), 'town-guards');
  await shot(page, `${tag}-01-group-assign`);
  await noSidewaysScroll(page, `${tag} group assign`);
  await groupDialog.getByRole('button', {name: 'Add to group', exact: true}).click();
  await page.getByText('Assigned town-guards: 2 changed, 1 unchanged.', {exact: false}).first().waitFor();
  await openDocument(page, workspace, 'npc', 'town/watchman');
  assert.deepEqual((await codeOf(page)).groups, ['town-guards']);
  await topbarAction(page, 'Undo');
  await settle(page);
  assert.equal((await codeOf(page)).groups, undefined, 'one undo reverts the assign');
  await openDocument(page, workspace, 'npc', 'town/stall-keeper');
  assert.equal((await codeOf(page)).groups, undefined, 'the same undo step covers every NPC');

  // Taking a picked NPC out of a group.
  await openJob(page, 'NPCs');
  await page.locator('#npc-list .ui-library-row[data-key="npc:harbor/porter"] .ui-library-pick [role="checkbox"]').click();
  await page.locator('.ui-library-bulk .ui-library-count', {hasText: '1 picked'}).waitFor();
  await bulkAction(page, 'Trait group');
  await groupDialog.getByRole('button', {name: 'Remove', exact: true}).click();
  await chooseAndWait(groupDialog.locator('#npc-group'), 'harbor');
  await groupDialog.getByRole('button', {name: 'Remove from group', exact: true}).click();
  await page.getByText('Unassigned harbor: 1 changed, 0 unchanged.', {exact: false}).first().waitFor();
  await topbarAction(page, 'Undo');
  await settle(page);

  // NPC with groups (Advanced Basics): the trait groups and what it takes.
  await openDocument(page, workspace, 'npc', 'town/gate-guard');
  const identity = await part(page, 'identity');
  assert.deepEqual(await identity.locator('[data-npc="groups"] li[data-group]').evaluateAll((items) => items.map((item) => item.dataset.group)), ['town-guards', 'harbor']);
  const effective = await part(page, 'effective');
  const source = (field) => effective.locator(`[data-path="${field}"]`).getAttribute('data-source');
  assert.equal(await source('teamColor'), 'npc');
  assert.equal(await source('equipment'), 'town-guards');
  assert.equal(await source('hologram'), 'harbor');
  assert.equal(await source('modules.animus:look-close'), 'harbor');
  await shot(page, `${tag}-02-effective`);
  await noSidewaysScroll(page, `${tag} effective`);
  // The revision is never an input.
  assert.equal(await page.getByRole('spinbutton', {name: /revision/i}).count(), 0, 'no revision input');
  // Moving harbor first (Alt+Left on its chip) makes town-guards win look-close.
  await identity.getByRole('button', {name: 'Open group harbor', exact: true}).focus();
  await page.keyboard.press('Alt+ArrowLeft');
  await settle(page);
  assert.equal(await source('modules.animus:look-close'), 'town-guards');
  await topbarAction(page, 'Undo');
  await settle(page);
  await effective.getByRole('button', {name: 'Open group harbor, which sets hologram'}).click();
  await page.locator('[data-npc="group-form"]').waitFor();

  // Group editor: Members.
  const group = await part(page, 'group');
  const member = (id) => group.locator(`[data-group="members"] .ui-library-row[data-key="${id}"]`);
  await member('town/gate-guard').waitFor();
  await page.locator('#animus-group-description').fill('Harbor colors only');
  await settle(page);
  assert.equal((await codeOf(page)).description, 'Harbor colors only');
  // Adding a member from the group screen writes the NPC's groups; Undo takes it back.
  await group.locator('[data-group="add-members"]').click();
  await page.locator('#animus-group-add-members .ui-library-row[data-key="town/watchman"]').click();
  await member('town/watchman').waitFor();
  await topbarAction(page, 'Undo');
  await settle(page);
  assert.equal(await member('town/watchman').count(), 0, 'Undo removes the added member');
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
  // "+ New group" asks for a name, then makes the group and lists it, one undo step.
  await part(page, 'identity');
  const adder = await openDropdown(page.locator('#animus-npc-add-group'));
  await adder.getByRole('button', {name: /^\+ New/}).click();
  const naming = page.getByRole('dialog').filter({hasText: 'New trait group'});
  await naming.getByLabel('Name', {exact: true}).fill('Dock crew');
  await naming.getByRole('button', {name: 'Create', exact: true}).click();
  const made = (await part(page, 'identity')).getByRole('status').filter({hasText: 'Made group dock-crew.'});
  await made.waitFor();
  await made.getByRole('button', {name: 'Open dock-crew'}).waitFor();
  await shot(page, `${tag}-03b-new-group`);
  assert.deepEqual((await codeOf(page)).groups, ['dock-crew']);
  const groupListed = async () => {
    await page.evaluate((hash) => { location.hash = hash; }, `#/workspace/${workspace}/list/group`);
    await page.locator('.ui-library').first().waitFor();
    await page.waitForTimeout(200);
    const count = await page.locator('.ui-library-row[data-key="group:dock-crew"]').count();
    await openDocument(page, workspace, 'npc', 'town/watchman');
    return count;
  };
  assert.equal(await groupListed(), 1, 'the group document exists');
  await topbarAction(page, 'Undo');
  await settle(page);
  assert.equal((await codeOf(page)).groups, undefined, 'one undo drops the list entry');
  assert.equal(await groupListed(), 0, 'and the group');

  // An NPC playing a recording.
  await openDocument(page, workspace, 'npc', 'town/stall-keeper');
  assert.equal((await codeOf(page)).modules['animus:navigation'].recording, 'market-loop', 'it plays a recording');
  await part(page, 'behaviour');
  assert.equal((await codeOf(page)).modules['animus:navigation'].playback, 'pingpong');
  // Playback is the recording page's toggle group, plus the recording's own.
  const playback = page.getByRole('group', {name: 'Playback'});
  assert.deepEqual((await playback.getByRole('button').allInnerTexts()).map(text => text.trim()), ["Recording's", 'Once', 'Loop', 'Back and forth']);
  await playback.getByRole('button', {name: 'Once', exact: true}).click();
  await settle(page);
  assert.deepEqual((await codeOf(page)).modules['animus:navigation'], {enabled: true, recording: 'market-loop', playback: 'once'});
  await shot(page, `${tag}-04-playback`);
  await noSidewaysScroll(page, `${tag} playback`);

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
