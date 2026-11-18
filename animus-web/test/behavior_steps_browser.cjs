// The behavior step editor, offline on ../schema/fixtures/export, in
// Advanced at 1600, 768 and 390 px: New behavior offers the templates with
// a visible folder and an editable id; Look and Navigate pick their target
// (the player, coordinates, a route or an anchor) and their headers say it;
// a new If checks a flag; a single-step branch grows with Add step, and a
// type change that would drop content asks first; steps move with the menu,
// Alt+Up and Alt+Down and by dragging, copy and paste within and between
// behaviors, and each move is its own undo step whose notice names it; a
// wait takes "2 seconds" and says when it held a value; flag scopes read as
// variable scopes; a refused routine name says why; the id renames in place;
// a new hologram shows its folder too. No console errors, no sideways
// scrolling.
//
//   python3 tool/serve_site.py --port <port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_SCREENSHOTS=<folder> node test/behavior_steps_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openJob, showView, closeDrawer, setMode} = require('./support/shell.cjs');
const {choose, openDropdown, optionLabels} = require('./support/select.cjs');

const exportFolder = path.resolve(__dirname, '../../schema/fixtures/export');
const shots = process.env.ANIMUS_SCREENSHOTS;
const site = process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })();
const mod = process.platform === 'darwin' ? 'Meta' : 'Control';

async function settle(page) {
  await page.waitForTimeout(150);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

/// Checks for sideways scrolling and saves a screenshot, with [subject]
/// (a locator) scrolled to the middle of the window.
async function shot(page, name, subject) {
  if (subject) await subject.first().evaluate(element => element.scrollIntoView({block: 'center'}));
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 1, `${name}: ${overflow}px of sideways scrolling`);
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: false});
}

async function codeOf(page) {
  await showView(page, 'code');
  const text = await page.locator('.animus-code textarea').inputValue();
  await showView(page, 'visual');
  await page.locator('.animus-behavior-editor').waitFor();
  return JSON.parse(text);
}

async function waitForCode(page, check, what) {
  const deadline = Date.now() + 8000;
  for (;;) {
    await settle(page);
    const json = await codeOf(page);
    if (check(json)) return json;
    if (Date.now() > deadline) throw new Error(`${what}: ${JSON.stringify(json.routines)}`);
  }
}

function node(page, nodePath) {
  return page.locator(`.animus-node[data-node-path="${nodePath}"]`);
}

function summaryOf(page, nodePath) {
  return node(page, nodePath).locator(':scope > details > summary');
}

async function headerOf(page, nodePath) {
  return (await summaryOf(page, nodePath).innerText()).replace(/\s+/g, ' ').trim();
}

function menuWith(page, item) {
  return page.getByRole('menu').filter({has: page.getByRole('menuitem', {name: item})});
}

async function pickMenu(page, trigger, item) {
  await settle(page);
  await trigger.click();
  const menu = menuWith(page, item);
  await menu.waitFor();
  await menu.getByRole('menuitem', {name: item}).first().click();
  await menu.waitFor({state: 'hidden'});
}

async function stepMenu(page, nodePath, item) {
  const toolbar = node(page, nodePath).locator(':scope > details').getByRole('toolbar').first();
  await pickMenu(page, toolbar.getByRole('button', {name: 'More actions'}), item);
}

/// Opens the behavior [id] by its address.
async function openBehavior(page, id) {
  await page.evaluate((id) => { location.hash = location.hash.replace(/^(#\/workspace\/[^/]+).*$/, `$1/behavior/${encodeURIComponent(id)}`); }, id);
  await page.waitForFunction((id) => decodeURIComponent(location.hash).includes(`/behavior/${id}`), id);
  await page.locator('.animus-behavior-editor').waitFor();
}

async function toast(page, text) {
  await page.getByText(text, {exact: true}).first().waitFor();
}

async function undo(page) {
  await page.locator('#animus-main-heading').click();
  await page.keyboard.press(`${mod}+z`);
}

async function run(browser, viewport, tag, theme) {
  const context = await browser.newContext({viewport});
  try {
    await flow(context, viewport, tag, theme);
  } catch (error) {
    const page = context.pages()[0];
    if (page && shots) await page.screenshot({path: path.join(shots, `${tag}-failed.png`), fullPage: false}).catch(() => {});
    throw error;
  } finally {
    await context.close();
  }
}

async function flow(context, viewport, tag, theme) {
  const phone = viewport.width < 500;
  await context.addInitScript(mode => {
    window.localStorage.setItem('animus.theme', mode);
    window.localStorage.setItem('animus.mode', 'advanced');
  }, theme);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(`${message.text()} ${message.location().url || ''}`); });
  await page.goto(site);
  await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(exportFolder);
  await page.locator('[data-testid="home-count"]').first().waitFor().catch(() => {});

  // New behavior offers the templates, shows the folder and, in
  // Advanced, the id it will get.
  await openJob(page, 'Behaviors');
  await closeDrawer(page);
  await page.locator('[data-testid="list-new"]').click();
  const dialog = page.getByRole('dialog', {name: 'New behavior'});
  await dialog.waitFor();
  const startFrom = dialog.locator('button[role="combobox"][id="new-behavior-template"]');
  // Read the starts with the list open, then pick Guard from it.
  const list = await openDropdown(startFrom);
  const starts = await list.locator('[role="option"]').evaluateAll(rows => rows.map(row => row.getAttribute('data-label')));
  for (const name of ['Conversation', 'Greeter', 'Question', 'Patrol', 'Shop', 'Guard', 'Quest', 'Blank', 'Server behavior']) assert.ok(starts.includes(name), `${tag}: ${name} in ${starts}`);
  await shot(page, `${tag}-new-behavior-templates`);
  // Escape closes the open list alone; the dialog stays.
  await page.keyboard.press('Escape');
  await list.waitFor({state: 'hidden'});
  await dialog.waitFor();
  await openDropdown(startFrom);
  await list.locator('[role="option"][data-arcane-value="guard"]').click();
  await list.waitFor({state: 'hidden'});
  await dialog.locator('#new-behavior-name').fill('Gate guard');
  const folder = dialog.locator('#new-folder');
  await folder.waitFor();
  await folder.fill('town');
  await settle(page);
  assert.equal(await dialog.locator('#new-id').inputValue(), 'town:gate-guard', `${tag}: the id follows folder and name`);
  await shot(page, `${tag}-new-behavior-dialog`);
  await dialog.getByRole('button', {name: 'Create', exact: true}).click();
  await page.waitForFunction(() => decodeURIComponent(location.hash).includes('/behavior/town:gate-guard'));
  const editor = page.locator('.animus-behavior-editor');
  await editor.waitFor();
  await editor.locator('.ui-section-title', {hasText: /^Routines/}).first().waitFor();
  await editor.locator('.ui-section-title', {hasText: /^Triggers/}).first().waitFor();
  assert.equal(await editor.locator('.ui-section-title', {hasText: /^(Parts|Starts when)/}).count(), 0, `${tag}: one word for routines and triggers`);

  // the guard's Look says it looks at the player; Coordinates and back.
  const look = '$.routines.challenge.steps[0]';
  assert.match(await headerOf(page, look), /Look at the player/, `${tag}: look header`);
  const looksAt = node(page, look).getByRole('group', {name: 'Looks at'});
  await looksAt.getByText('Coordinates', {exact: true}).click();
  await page.waitForFunction(p => document.querySelector(`.animus-node[data-node-path="${p}"] > details > summary`)?.textContent.includes('Look at 0, 64, 0'), look);
  await shot(page, `${tag}-look-coordinates`, node(page, look));
  await looksAt.getByText('The player', {exact: true}).click();
  await page.waitForFunction(p => document.querySelector(`.animus-node[data-node-path="${p}"] > details > summary`)?.textContent.includes('Look at the player'), look);
  let json = await codeOf(page);
  assert.deepEqual(json.routines.challenge.steps[0].arguments, {target: 'player'});
  await shot(page, `${tag}-look-player`, node(page, look));

  // Navigate to an anchor.
  const challengeAdd = () => page.locator('[data-routine="challenge"]').getByRole('button', {name: /Add step$/}).last();
  await pickMenu(page, challengeAdd(), 'Do');
  const walk = '$.routines.challenge.steps[2]';
  await node(page, walk).waitFor();
  await choose(node(page, walk).getByRole('combobox', {name: 'What this step does'}), 'animus:navigate');
  await node(page, walk).getByRole('group', {name: 'Goes to'}).getByText('An anchor', {exact: true}).click();
  await settle(page);
  await node(page, walk).locator('[data-argument="anchor"] input').fill('gate');
  await page.waitForFunction(p => document.querySelector(`.animus-node[data-node-path="${p}"] > details > summary`)?.textContent.includes('Walk to anchor gate'), walk);
  json = await codeOf(page);
  assert.deepEqual(json.routines.challenge.steps[2].arguments, {anchor: 'gate'});
  await shot(page, `${tag}-navigate-anchor`, summaryOf(page, walk));

  // the No branch holds one step; Add step under it keeps it.
  const no = '$.routines.challenge.steps[1].else';
  await node(page, no).waitFor();
  const noAdd = node(page, '$.routines.challenge.steps[1]').locator(':scope > details > div').getByRole('button', {name: /Add step$/}).last();
  await pickMenu(page, noAdd, 'Wait');
  json = await waitForCode(page, j => j.routines.challenge.steps[1].else.type === 'sequence', 'no branch grown');
  assert.equal(json.routines.challenge.steps[1].else.steps.length, 2);
  assert.equal(json.routines.challenge.steps[1].else.steps[0].arguments.text, 'Stranger, state your business.');
  await shot(page, `${tag}-no-branch-grown`, node(page, no));
  // The Yes step becomes a list without a question: nothing is lost.
  const yes = '$.routines.challenge.steps[1].then';
  await choose(node(page, yes).locator(':scope > details').getByRole('combobox', {name: 'Type of yes'}), 'sequence');
  json = await waitForCode(page, j => j.routines.challenge.steps[1].then.type === 'sequence', 'yes wrapped');
  assert.equal(json.routines.challenge.steps[1].then.steps[0].arguments.text, 'Evening, citizen.');
  // Say to Wait would drop the line: it asks, and Cancel keeps it.
  const say = '$.routines.challenge.steps[1].else.steps[0]';
  await choose(node(page, say).locator(':scope > details').getByRole('combobox', {name: 'Type of step 1'}), 'wait');
  const ask = page.getByRole('alertdialog');
  await ask.getByText('"Say: Stranger, state your business." goes. Undo brings it back.').waitFor();
  await page.waitForTimeout(400);
  await shot(page, `${tag}-type-change-asks`);
  await ask.getByRole('button', {name: 'Cancel', exact: true}).click();
  await ask.waitFor({state: 'detached'});
  json = await codeOf(page);
  assert.equal(json.routines.challenge.steps[1].else.steps[0].type, 'action');

  // a new If checks a flag, and its scope reads as variables do.
  const alarmAdd = () => page.locator('[data-routine="alarm"]').getByRole('button', {name: /Add step$/}).last();
  await pickMenu(page, alarmAdd(), 'If');
  const check = '$.routines.alarm.steps[2]';
  await node(page, check).waitFor();
  assert.match(await headerOf(page, check), /If flag met is true/);
  assert.deepEqual(await optionLabels(node(page, check).getByRole('combobox', {name: 'Who shares it'})), ['Everyone', 'This NPC', 'Each player', 'NPC and player']);

  // Alt+Up moves the focused step; undo takes back one move and says which.
  await summaryOf(page, check).focus();
  await page.keyboard.press('Alt+ArrowUp');
  await page.waitForFunction(() => document.querySelector('.animus-node[data-node-path="$.routines.alarm.steps[1]"]')?.getAttribute('data-node-type') === 'condition');
  await stepMenu(page, '$.routines.alarm.steps[1]', 'Move up');
  await page.waitForFunction(() => document.querySelector('.animus-node[data-node-path="$.routines.alarm.steps[0]"]')?.getAttribute('data-node-type') === 'condition');
  await undo(page);
  await toast(page, 'Undid: move If up');
  await page.waitForFunction(() => document.querySelector('.animus-node[data-node-path="$.routines.alarm.steps[1]"]')?.getAttribute('data-node-type') === 'condition');
  await page.waitForTimeout(400);
  await shot(page, `${tag}-undo-notice`, node(page, '$.routines.alarm.steps[1]'));
  await undo(page);
  await page.waitForFunction(() => document.querySelector('.animus-node[data-node-path="$.routines.alarm.steps[2]"]')?.getAttribute('data-node-type') === 'condition');
  await shot(page, `${tag}-undo-names-move`, node(page, '$.routines.alarm.steps[2]'));

  // Drag the If to the top of its routine (the grip hides on phones).
  if (!phone) {
    // Folded, the three steps are in view together.
    for (let index = 0; index < 3; index++) {
      await summaryOf(page, `$.routines.alarm.steps[${index}]`).getByText(`Step ${index + 1}`, {exact: true}).click();
    }
    await settle(page);
    await node(page, '$.routines.alarm.steps[2]').locator('[data-step-grip]').first().dragTo(summaryOf(page, '$.routines.alarm.steps[0]'));
    await page.waitForFunction(() => document.querySelector('.animus-node[data-node-path="$.routines.alarm.steps[0]"]')?.getAttribute('data-node-type') === 'condition');
    await shot(page, `${tag}-dragged`, node(page, '$.routines.alarm.steps[0]'));
    await undo(page);
    await toast(page, 'Undid: move If');
    for (let index = 0; index < 3; index++) {
      await summaryOf(page, `$.routines.alarm.steps[${index}]`).getByText(`Step ${index + 1}`, {exact: true}).click();
    }
  }

  // Copy the Look and paste it after the alarm's first step, then into another behavior.
  await summaryOf(page, look).focus();
  await page.keyboard.press(`${mod}+c`);
  await toast(page, 'Copied Look.');
  await summaryOf(page, '$.routines.alarm.steps[0]').focus();
  await page.keyboard.press(`${mod}+v`);
  json = await waitForCode(page, j => j.routines.alarm.steps[1].capability === 'animus:look', 'pasted in the alarm');
  await openBehavior(page, 'greeter:greeter');
  const waveAdd = page.locator('[data-routine="wave"]').getByRole('button', {name: /Add step$/}).last();
  await pickMenu(page, waveAdd, 'Paste Look');
  json = await waitForCode(page, j => j.routines.wave.steps.at(-1).capability === 'animus:look', 'pasted in the greeter');
  await shot(page, `${tag}-pasted-in-greeter`, page.locator('[data-routine="wave"] .animus-node').last());
  await openBehavior(page, 'town:gate-guard');

  // "2 seconds" in Ticks, a held value says so, text it cannot read says what to type.
  await pickMenu(page, alarmAdd(), 'Wait');
  const wait = '$.routines.alarm.steps[4]';
  const ticks = node(page, wait).getByLabel('Ticks', {exact: true});
  await ticks.fill('2 seconds');
  json = await waitForCode(page, j => j.routines.alarm.steps[4].ticks === 40, 'two seconds');
  await ticks.fill('100000');
  await node(page, wait).getByText('Set to 72000 ticks, the most it takes.').waitFor();
  await shot(page, `${tag}-ticks-held`, node(page, wait));
  await ticks.fill('soon');
  await node(page, wait).getByText('Type ticks or a time, such as 40 or 2 seconds.').waitFor();
  json = await codeOf(page);
  assert.equal(json.routines.alarm.steps[4].ticks, 72000);

  // A routine name with a space is refused, with the reason kept after focus leaves.
  const routineName = page.locator('[data-routine="alarm"]').getByLabel(/^Name of routine/).first();
  await routineName.fill('my part');
  await page.getByText('Use lowercase letters, digits, _ and -, such as my-part.', {exact: true}).waitFor();
  await page.keyboard.press('Tab');
  await page.getByText('Kept alarm. Use lowercase letters, digits, _ and -, such as my-part.', {exact: true}).waitFor();
  await shot(page, `${tag}-routine-name-refused`, routineName);

  // the id renames in place.
  const id = editor.getByLabel('Id', {exact: true});
  await id.fill('town:gate-keeper');
  await id.press('Enter');
  await page.waitForFunction(() => decodeURIComponent(location.hash).includes('/behavior/town:gate-keeper'));
  await page.getByText(/Renamed /).first().waitFor();

  // a new hologram shows its folder and id too.
  await page.evaluate(() => { location.hash = location.hash.replace(/\/behavior\/.*$/, '/list/hologram'); });
  await page.locator('[data-testid="list-new"], .ui-list-page button:has-text("New hologram")').first().click();
  const hologram = page.getByRole('dialog', {name: 'New hologram'});
  await hologram.waitFor();
  await hologram.locator('#new-folder').waitFor();
  await hologram.locator('#new-id').waitFor();
  await page.waitForTimeout(400);
  await shot(page, `${tag}-new-hologram-dialog`);
  await page.keyboard.press('Escape');

  // Simple: the templates and the folder, no id and no server behavior.
  await setMode(page, 'simple');
  await openJob(page, 'Behaviors');
  await closeDrawer(page);
  await page.locator('[data-testid="list-new"]').click();
  const simple = page.getByRole('dialog', {name: 'New behavior'});
  await simple.waitFor();
  await simple.locator('#new-folder').waitFor();
  assert.equal(await simple.locator('#new-id').count(), 0, `${tag}: Simple shows no id`);
  const simpleList = await openDropdown(simple.locator('button[role="combobox"][id="new-behavior-template"]'));
  const simpleStarts = await simpleList.locator('[role="option"]').evaluateAll(rows => rows.map(row => row.getAttribute('data-label')));
  assert.deepEqual(simpleStarts, ['Conversation', 'Greeter', 'Question', 'Patrol', 'Shop', 'Guard', 'Quest', 'Blank'], `${tag}: Simple starts`);
  await page.waitForTimeout(400);
  await shot(page, `${tag}-simple-new-behavior`);
  await page.keyboard.press('Escape');

  assert.deepEqual(errors, [], `${tag}: console errors: ${errors.join(" | ")}`);
}

(async () => {
  const browser = await chromium.launch();
  try {
    await run(browser, {width: 1600, height: 1000}, '1600-dark', 'dark');
    await run(browser, {width: 768, height: 1000}, '768-light', 'light');
    await run(browser, {width: 390, height: 844}, '390-dark', 'dark');
    console.log('behavior steps: ok');
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exit(1);
});
