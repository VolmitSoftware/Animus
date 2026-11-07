// Opens test/fixtures/document_forms as a data folder: an animation whose rig
// is not in the workspace. Its Visual view is a form generated from the
// animation schema with a short note on the missing rig: the duration and
// loop edit and land in Code, a track's fields edit as a nested form, a key
// the schema does not know shows under Other fields as a typed key/value row
// (rename, change kind, Advanced JSON). Runs at 1440 and 390 px in both
// themes, with no sideways scroll and no console errors. Needs no server.
//
//   python3 tool/serve_site.py --port <port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<port> node test/document_forms_browser.cjs
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {chooseText, waitForValue} = require('./support/select.cjs');

const folder = path.resolve(__dirname, 'fixtures/document_forms');
const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');

async function noSideways(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 0, `${where} scrolls sideways by ${overflow}px`);
}

/** The document's JSON as the Code view shows it. */
async function code(page, workspace, id) {
  await page.goto(`${site}/#/workspace/${workspace}/animation/${encodeURIComponent(id)}?view=code`);
  const text = await page.locator('.animus-code textarea').first().inputValue();
  return JSON.parse(text);
}

async function visual(page, workspace, id) {
  await page.goto(`${site}/#/workspace/${workspace}/animation/${encodeURIComponent(id)}?view=visual`);
  await page.locator('[data-form="animation"]').waitFor();
}

async function run(browser, viewport, theme) {
  const label = `${viewport.width}px ${theme}`;
  const context = await browser.newContext({viewport, colorScheme: theme});
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });

  await page.goto(site);
  await page.getByRole('heading', {name: 'From a data folder'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(folder);
  await page.waitForFunction(() => /#\/workspace\/[^/]+/.test(location.hash));
  const workspace = /#\/workspace\/([^/?]+)/.exec(page.url())[1];
  const id = 'town:stray/wave';

  // The form and the note, not a summary table.
  await visual(page, workspace, id);
  const form = page.locator('[data-form="animation"]');
  assert.equal(await page.locator('.animus-fields table').count(), 0, `${label}: no summary table`);
  await form.locator('[data-form="rig-missing"]').getByText('Rig town:stray is not in this workspace.').waitFor();
  await noSideways(page, `${label} form`);

  // Top-level fields.
  const duration = form.getByLabel('durationTicks', {exact: true});
  await duration.fill('12');
  await duration.blur();
  const loop = form.locator('#animus-field--loop');
  await chooseText(loop, 'loop');
  await waitForValue(loop, 'loop');

  // A track edits as a nested form: its bone is a text field.
  const bones = form.getByLabel('bone', {exact: true});
  assert.ok(await bones.count() >= 1, `${label}: track fields render as a form`);
  await bones.first().fill('jaw');
  await bones.first().blur();

  let json = await code(page, workspace, id);
  assert.equal(json.durationTicks, 12);
  assert.equal(json.loop, 'loop');
  assert.equal(json.tracks[0].bone, 'jaw');

  // A key the schema lacks, typed in Code, shows under Other fields.
  json.note = 'draft';
  const area = page.locator('.animus-code textarea').first();
  await area.fill(JSON.stringify(json, null, 2));
  await page.getByRole('button', {name: 'Apply to draft'}).click();
  await visual(page, workspace, id);
  const other = form.getByRole('group', {name: 'Other fields'});
  const key = other.getByLabel('Other fields key 1', {exact: true});
  assert.equal(await key.inputValue(), 'note');
  await key.fill('notes');
  await key.press('Enter');
  await chooseText(other.getByLabel('Kind of notes'), 'Number');
  const number = other.locator('input[type="number"][aria-label="notes"]');
  await number.waitFor();
  await number.fill('3');
  await number.blur();
  await other.getByRole('button', {name: 'Advanced'}).click();
  assert.equal(await other.getByRole('button', {name: 'Advanced'}).getAttribute('aria-pressed'), 'true');
  assert.match(await other.locator('textarea').inputValue(), /"notes": 3/);
  await noSideways(page, `${label} other fields`);

  json = await code(page, workspace, id);
  assert.equal(json.notes, 3);
  assert.equal(json.note, undefined);
  assert.deepEqual(errors, [], `${label}: console errors`);
  console.log(`${label}: animation form, track form, other fields`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    await run(browser, {width: 1440, height: 900}, 'dark');
    await run(browser, {width: 1440, height: 900}, 'light');
    await run(browser, {width: 390, height: 844}, 'dark');
    await run(browser, {width: 390, height: 844}, 'light');
    console.log('document forms browser check passed');
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exit(1);
});
