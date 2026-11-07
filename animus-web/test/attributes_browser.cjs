// Opens test/fixtures/groups_recordings in the built site at 1440x900 and
// 390x844, dark and light, and checks the Attributes part: a group sets
// gravity through the add dropdown, picked by keyboard, an NPC of that group
// sees its rows grouped with ranges and the attributes its body lacks
// marked, adds scale (then disabled in the list), sees its range and vanilla
// default and help, edits it (the preview reports the size,
// an out-of-range value is flagged), the Effective part names the source of
// each attribute, and the reset removes scale again. No console errors and no
// sideways scrolling.
//
//   python3 tool/serve_site.py --port <WEB_PORT> &
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<WEB_PORT> ANIMUS_SCREENSHOTS=<scratch folder> \
//     node test/attributes_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

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

/** The add dropdown's trigger. */
function addTrigger(attributes) {
  return attributes.locator('button[role="combobox"][aria-label="Attribute to add"]');
}

/** Adds [id] from the keyboard: focus the dropdown, open it, search, pick the first match. */
async function addAttribute(page, attributes, id) {
  const trigger = addTrigger(attributes);
  await trigger.focus();
  await page.keyboard.press('Enter');
  const listbox = page.locator(`[id="${await trigger.getAttribute('aria-controls')}"]`);
  await listbox.waitFor();
  await page.keyboard.type(id);
  await listbox.locator(`[role="option"][data-value="${id}"]`).waitFor();
  await page.keyboard.press('Enter');
  await listbox.waitFor({state: 'detached'});
  await attributes.locator(`[data-attribute="${id}"]`).waitFor();
  await settle(page);
}

/** The dropdown's rows as {value, group, disabled, hint}, opening and closing it. */
async function addRows(page, attributes) {
  const trigger = addTrigger(attributes);
  await trigger.click();
  const listbox = page.locator(`[id="${await trigger.getAttribute('aria-controls')}"]`);
  await listbox.waitFor();
  const rows = await listbox.locator('[role="option"]').evaluateAll((options) => options.map((option) => ({
    value: option.getAttribute('data-value'),
    group: option.closest('[role="group"]')?.querySelector('.animus-select-group')?.textContent ?? null,
    disabled: option.getAttribute('aria-disabled') === 'true',
    hint: option.textContent,
  })));
  await page.keyboard.press('Escape');
  await listbox.waitFor({state: 'detached'});
  return rows;
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

  // The group sets gravity from the keyboard, written bare.
  await openDocument(page, workspace, 'group', 'town-guards');
  await page.locator('[aria-label="Group inspector"]').waitFor();
  let attributes = await part(page, 'attributes');
  await addAttribute(page, attributes, 'gravity');
  assert.deepEqual(Object.keys((await codeOf(page)).values.attributes), ['gravity']);

  // An NPC of the group adds scale and edits it.
  await openDocument(page, workspace, 'npc', 'town/gate-guard');
  attributes = await part(page, 'attributes');
  await attributes.getByText('No attributes set', {exact: false}).waitFor();
  const rows = await addRows(page, attributes);
  assert.deepEqual([...new Set(rows.map((row) => row.group))], ['Size and movement', 'Combat', 'Senses', 'Player only', 'Other'], `${tag}: groups`);
  assert.equal(rows[0].value, 'scale', `${tag}: scale leads`);
  assert.ok(rows.find((row) => row.value === 'scale').hint.includes('0.0625..16, vanilla 1'), `${tag}: range and default`);
  assert.ok(rows.find((row) => row.value === 'flying_speed').hint.includes('no effect on this body'), `${tag}: marked for the body`);
  assert.equal(rows.length, new Set(rows.map((row) => row.value)).size, `${tag}: one row each`);
  await addAttribute(page, attributes, 'scale');
  assert.ok((await addRows(page, attributes)).find((row) => row.value === 'scale').disabled, `${tag}: a set attribute is disabled`);
  const scale = attributes.locator('[data-attribute="scale"]');
  assert.equal((await scale.locator('[data-attribute-hint]').textContent()).trim(), '0.0625..16, vanilla 1');
  await scale.getByText('Body size', {exact: false}).waitFor({state: 'attached'});
  assert.deepEqual((await codeOf(page)).attributes, {scale: 1});
  attributes = await part(page, 'attributes');
  await scale.getByLabel('scale value').fill('2');
  await scale.getByLabel('scale value').blur();
  await settle(page);
  assert.deepEqual((await codeOf(page)).attributes, {scale: 2});
  await page.locator('.animus-npc-facts').getByText('2 times the vanilla size').waitFor();
  attributes = await part(page, 'attributes');
  await scale.getByLabel('scale value').fill('20');
  await scale.getByLabel('scale value').blur();
  await scale.locator('.animus-gui-problem').first().waitFor();
  await scale.getByLabel('scale value').fill('1.5');
  await scale.getByLabel('scale value').blur();
  await settle(page);
  assert.equal(await scale.locator('.animus-gui-problem').count(), 0, 'an in-range value clears the problem');
  await shot(page, `${tag}-01-attributes`);
  await noSidewaysScroll(page, `${tag} attributes`);

  // Effective: one row per attribute with its source.
  const effective = await part(page, 'effective');
  const source = (field) => effective.locator(`tr[data-path="${field}"]`).getAttribute('data-source');
  assert.equal(await source('attributes.scale'), 'npc');
  assert.equal(await source('attributes.gravity'), 'town-guards');
  assert.equal(await effective.locator('tr[data-path="attributes"]').count(), 0);
  await shot(page, `${tag}-02-effective`);
  await noSidewaysScroll(page, `${tag} effective`);

  // Reset removes the attribute and the empty field.
  attributes = await part(page, 'attributes');
  await attributes.getByRole('button', {name: 'Reset scale to vanilla 1'}).click();
  await settle(page);
  assert.equal(await attributes.locator('[data-attribute="scale"]').count(), 0);
  assert.equal((await codeOf(page)).attributes, undefined);

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
  console.log('attributes_browser: ok');
})().catch((error) => { console.error(error.stack || error.message || error); process.exit(1); });
