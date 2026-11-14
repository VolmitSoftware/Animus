// Checks the Advanced Attributes tab at 1440x900 and 390x844, dark and
// light. In test/fixtures/groups_recordings a trait group adds gravity from
// the keyboard; an NPC of that group sees the "Add attribute" list grouped,
// ordered for its body and named in words (Max health and armor are not in
// it: the Combat tab sets them), adds Size (then disabled in the list), sees
// its range and vanilla default as the hint and its id in the tip, edits it
// (the preview reports the size, an out-of-range value is flagged), and the
// reset removes it again. In test/fixtures/health_part the Gate Sentry's
// attribute rows say when a Combat setting replaces them, also after Max
// health is set in the Combat tab. No console errors, no sideways scrolling.
//
//   python3 tool/serve_site.py --port <WEB_PORT> &
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<WEB_PORT> ANIMUS_SCREENSHOTS=<scratch folder> \
//     node test/attributes_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {setMode, showView, startAction} = require('./support/shell.cjs');
const {npcTab} = require('./support/npc_tabs.cjs');

const fixtures = path.resolve(__dirname, 'fixtures');
const site = process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })();
const shots = process.env.ANIMUS_SCREENSHOTS;

async function shot(page, name) {
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`)});
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

/** The document's tab [label], or before the tabs land its part [id] opened. */
async function part(page, label, id) {
  const panel = await npcTab(page, label);
  if (panel) return panel;
  const details = page.locator(`details[data-section="${id}"]`);
  if (!(await details.evaluate((element) => element.open))) await details.locator('summary').first().click();
  return details;
}

const attributesPart = (page) => part(page, 'Attributes', 'attributes');

async function openFolder(page, name) {
  await page.goto(`${site}/`);
  await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await startAction(page, 'Open folder');
  await (await chooser).setFiles(path.join(fixtures, name));
  await page.waitForFunction(() => /#\/workspace\//.test(location.hash));
  await page.locator('.ui-save-bar').waitFor();
  return /#\/workspace\/([^/?]+)/.exec(page.url())[1];
}

async function openDocument(page, workspace, kind, id) {
  await page.evaluate((hash) => { location.hash = hash; }, `#/workspace/${workspace}/${kind}/${encodeURIComponent(id)}`);
  await page.locator('#animus-main h1').waitFor();
}

async function settle(page) {
  await page.waitForTimeout(250);
}

/** The add dropdown's trigger, named by its field label. */
function addTrigger(scope) {
  return scope.getByRole('combobox', {name: 'Add attribute'});
}

/** Adds [id] from the keyboard: focus the dropdown, open it, search, Enter on the match. */
async function addAttribute(page, scope, id) {
  const trigger = addTrigger(scope);
  await trigger.focus();
  await page.keyboard.press('Enter');
  const listbox = page.locator(`[id="${await trigger.getAttribute('aria-controls')}"]`);
  await listbox.waitFor();
  // The list's search box matches ids as well as the words.
  await listbox.getByRole('textbox', {name: 'Search'}).fill(id);
  await listbox.locator(`[role="option"][data-arcane-value="${id}"]`).waitFor();
  await page.keyboard.press('Enter');
  await listbox.waitFor({state: 'hidden'});
  await scope.locator(`[data-attribute="${id}"]`).waitFor();
  await settle(page);
}

/** The dropdown's rows as {value, label, group, disabled, hint}, opening and closing it. */
async function addRows(page, scope) {
  const trigger = addTrigger(scope);
  await trigger.click();
  const listbox = page.locator(`[id="${await trigger.getAttribute('aria-controls')}"]`);
  await listbox.waitFor();
  // Group headings are presentation rows before their options.
  const rows = await listbox.evaluate((list) => {
    const out = [];
    let group = null;
    for (const row of list.querySelectorAll('[role="presentation"], [role="option"]')) {
      if (row.getAttribute('role') === 'presentation') {
        group = row.textContent.trim();
        continue;
      }
      out.push({
        value: row.getAttribute('data-arcane-value'),
        label: row.getAttribute('data-label'),
        group,
        disabled: row.getAttribute('aria-disabled') === 'true' || row.hasAttribute('disabled'),
        hint: row.textContent,
      });
    }
    return out;
  });
  await page.keyboard.press('Escape');
  await listbox.waitFor({state: 'hidden'});
  return rows;
}

/** The hint under attribute row [id]. */
async function hintOf(scope, id) {
  return (await scope.locator(`[data-attribute="${id}"] [data-slot="field-description"]`).innerText()).trim();
}

async function groupsAndNpc(page, tag) {
  const workspace = await openFolder(page, 'groups_recordings');
  await setMode(page, 'advanced');

  // The group sets gravity from the keyboard, written bare.
  await openDocument(page, workspace, 'group', 'town-guards');
  let attributes = await attributesPart(page);
  await addAttribute(page, attributes, 'gravity');
  assert.equal((await attributes.locator('[data-attribute="gravity"] [data-slot="field-label"] label').innerText()).trim(), 'Gravity', `${tag}: label in words`);
  assert.deepEqual(Object.keys((await codeOf(page)).values.attributes), ['gravity']);

  // An NPC of the group: the add list, then Size added and edited.
  await openDocument(page, workspace, 'npc', 'town/gate-guard');
  attributes = await attributesPart(page);
  await attributes.getByText('None set. Every attribute has its normal value.').waitFor();
  const rows = await addRows(page, attributes);
  assert.deepEqual([...new Set(rows.map((row) => row.group))], ['Size and movement', 'Combat', 'Senses', 'Player only', 'Other'], `${tag}: groups`);
  assert.equal(rows[0].value, 'scale', `${tag}: scale leads`);
  assert.equal(rows[0].label, 'Size', `${tag}: named in words`);
  assert.equal(rows.find((row) => row.value === 'movement_speed').label, 'Speed');
  assert.ok(rows.find((row) => row.value === 'scale').hint.includes('0.0625 to 16, vanilla 1'), `${tag}: range and default`);
  assert.ok(rows.find((row) => row.value === 'flying_speed').hint.includes('no effect on this body'), `${tag}: marked for the body`);
  for (const id of ['max_health', 'armor', 'armor_toughness']) {
    assert.equal(rows.find((row) => row.value === id), undefined, `${tag}: ${id} is set in the Combat tab`);
  }
  assert.equal(rows.length, new Set(rows.map((row) => row.value)).size, `${tag}: one row each`);
  await addAttribute(page, attributes, 'scale');
  assert.ok((await addRows(page, attributes)).find((row) => row.value === 'scale').disabled, `${tag}: a set attribute is disabled`);
  const scale = attributes.locator('[data-attribute="scale"]');
  assert.equal((await scale.locator('[data-slot="field-label"] label').innerText()).trim(), 'Size');
  assert.equal(await hintOf(attributes, 'scale'), '0.0625 to 16, vanilla 1');
  assert.match(await scale.locator('[id$="-text"]').first().textContent(), /Id: minecraft:scale/, `${tag}: the id is in the tip`);
  assert.deepEqual((await codeOf(page)).attributes, {scale: 1});
  attributes = await attributesPart(page);
  const value = attributes.getByLabel('Size', {exact: true});
  await value.fill('2');
  await value.blur();
  await settle(page);
  assert.deepEqual((await codeOf(page)).attributes, {scale: 2});
  // Phones keep the preview behind its button; elsewhere the stage draws the new size.
  if (page.viewportSize().width > 760) await page.waitForFunction(() => document.querySelector('[data-npc-stage]')?.dataset.scale === '2.0' || document.querySelector('[data-npc-stage]')?.dataset.scale === '2');
  attributes = await attributesPart(page);
  await attributes.getByLabel('Size', {exact: true}).fill('20');
  await attributes.getByLabel('Size', {exact: true}).blur();
  await attributes.locator('[data-attribute="scale"] :is([data-problem], [data-slot="field-error"])').first().waitFor();
  await attributes.getByLabel('Size', {exact: true}).fill('1.5');
  await attributes.getByLabel('Size', {exact: true}).blur();
  await settle(page);
  assert.equal(await attributes.locator('[data-attribute="scale"] :is([data-problem], [data-slot="field-error"])').count(), 0, 'an in-range value clears the problem');
  await shot(page, `combat-attributes-${tag}`);
  await noSidewaysScroll(page, `${tag} attributes`);

  // Before the tabs land, the Effective part names each attribute's source.
  const effective = page.locator('details[data-section="effective"]');
  if (await effective.count()) {
    if (!(await effective.evaluate((element) => element.open))) await effective.locator('summary').first().click();
    assert.equal(await effective.locator('tr[data-path="attributes.scale"]').getAttribute('data-source'), 'npc');
    assert.equal(await effective.locator('tr[data-path="attributes.gravity"]').getAttribute('data-source'), 'town-guards');
  }

  // Reset removes the attribute and the empty field.
  attributes = await attributesPart(page);
  await attributes.getByRole('button', {name: 'Reset size to vanilla 1'}).click();
  await settle(page);
  assert.equal(await attributes.locator('[data-attribute="scale"]').count(), 0);
  assert.equal((await codeOf(page)).attributes, undefined);
}

/** Attributes a Combat setting replaces say so, also after an edit there. */
async function replacedByCombat(page, tag) {
  const workspace = await openFolder(page, 'health_part');
  await setMode(page, 'advanced');
  await openDocument(page, workspace, 'npc', 'town/sentry');
  let attributes = await attributesPart(page);
  assert.equal((await attributes.locator('[data-attribute="max_health"] [data-slot="field-label"] label').innerText()).trim(), 'Max health');
  assert.equal(await hintOf(attributes, 'max_health'), '1 to 1024, vanilla 20', `${tag}: max health used until Combat sets it`);
  assert.equal(await hintOf(attributes, 'attack_damage'), '0 to 2048, vanilla 2. Not used: Combat sets melee damage', `${tag}: melee damage replaces it`);

  const combat = await part(page, 'Combat', 'modules');
  const max = combat.getByLabel('Max health', {exact: true});
  await max.fill('40');
  await max.blur();
  await settle(page);
  attributes = await attributesPart(page);
  assert.equal(await hintOf(attributes, 'max_health'), '1 to 1024, vanilla 20. Not used: Combat sets max health', `${tag}: Combat's max health wins`);
  const json = await codeOf(page);
  assert.equal(json.modules['animus:health'].maximumHealth, 40);
  assert.deepEqual(json.attributes, {max_health: 30, attack_damage: 3}, `${tag}: the attribute is kept`);
  await noSidewaysScroll(page, `${tag} replaced`);
}

async function run(browser, viewport, theme, tag) {
  const context = await browser.newContext({viewport});
  await context.addInitScript((mode) => window.localStorage.setItem('animus.theme', mode), theme);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  page.on('console', (message) => { if (message.type() === 'error' && !/404|Failed to load resource/.test(message.text())) errors.push(message.text()); });
  try {
    await groupsAndNpc(page, tag);
    await replacedByCombat(page, tag);
  } catch (error) {
    if (shots) await page.screenshot({path: path.join(shots, `combat-attributes-failure-${tag}.png`)});
    throw error;
  }
  assert.deepEqual(errors, [], `${tag}: console errors`);
  await context.close();
  console.log(`ok ${tag}`);
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
  console.log('attributes_browser: ok');
})().catch((error) => { console.error(error.stack || error.message || error); process.exit(1); });
