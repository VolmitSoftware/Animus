// The Model Engine module is offered only while the paired server reports
// Model Engine running. Opens ../schema/fixtures/export as a data folder and
// checks that the inspector's "Add a known module" dropdown leaves the module
// out; then pairs with tool/fake_server.dart (default --data) reporting Model
// Engine running (`/__fake/plugins?running=modelengine`), opens the greeter from
// the server catalog, adds the module from the dropdown, fills its model and
// scale from the schema's fields and checks the draft JSON. Desktop and phone
// widths, no console errors and no sideways scrolling.
//
//   python3 tool/serve_site.py --port <site port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<site port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<site port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/modelengine_browser.cjs
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openFromSidebar} = require('./support/shell.cjs');
const {choose, dropdownShowing, optionValues} = require('./support/select.cjs');

const exportFolder = path.resolve(__dirname, '../../schema/fixtures/export');
const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

async function openModules(inspector) {
  for (const details of await inspector.locator('details').all()) {
    if (await details.locator('summary', {hasText: 'Modules'}).count() && !(await details.evaluate(element => element.open))) await details.locator('summary').first().click();
  }
}

async function codeOf(page) {
  await page.getByRole('tab', {name: 'Code'}).click();
  const text = await page.locator('.animus-code textarea').inputValue();
  await page.getByRole('tab', {name: 'Visual'}).click();
  return JSON.parse(text);
}

async function run(browser, width, height) {
  const page = await (await browser.newContext({viewport: {width, height}})).newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });

  // Offline: no server reports Model Engine, so the module is not offered.
  await page.goto(url);
  await page.getByRole('heading', {name: 'From a data folder'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(exportFolder);
  await page.getByRole('heading', {name: '1 document needs repair'}).waitFor();
  await openFromSidebar(page, 'Greeter');
  await page.locator('.animus-document-header').waitFor();
  await page.getByRole('tab', {name: 'Visual'}).click();
  let inspector = page.locator('.animus-inspector').first();
  await openModules(inspector);
  const offline = await optionValues(dropdownShowing(inspector, 'Add a known module'));
  assert(offline.includes('animus:schedule'), `known modules offered offline: ${offline}`);
  assert(!offline.includes('animus:modelengine'), `${width}px: Model Engine offered with no server reporting it`);

  // Paired with a server that reports Model Engine running, the module is offered and edits.
  await control('reset', {});
  await control('plugins?running=modelengine', {});
  const {code} = await control('pair?paths=both', {});
  await page.goto(`${url}/#/pair/${code}`);
  await page.locator('[data-testid="pair-fingerprint"]', {hasText: 'sha256:'}).waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await page.getByText('revision 1').first().waitFor();
  await page.getByRole('checkbox', {name: 'examples/greeter', exact: true}).check();
  await page.getByRole('button', {name: 'Open 1'}).click();
  await page.waitForFunction(() => decodeURIComponent(location.hash).includes('/npc/examples/greeter'));
  await page.locator('.animus-document-header').waitFor();
  await page.getByRole('tab', {name: 'Visual'}).click();
  inspector = page.locator('.animus-inspector').first();
  await openModules(inspector);
  await choose(dropdownShowing(inspector, 'Add a known module'), 'animus:modelengine');
  const module = inspector.locator('[data-module="animus:modelengine"]');
  await module.waitFor();
  await module.locator('input').and(module.getByLabel(/^Model/)).first().fill('drake');
  await module.locator('input').and(module.getByLabel(/^Scale/)).first().fill('1.5');
  await page.waitForTimeout(400);
  const json = await codeOf(page);
  assert.equal(json.modules['animus:modelengine'].model, 'drake');
  assert.equal(json.modules['animus:modelengine'].scale, 1.5);
  assert.equal(json.modules['animus:modelengine'].enabled, true);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert(overflow <= 1, `sideways scrolling at ${width}px: ${overflow}`);
  assert.deepEqual(errors, []);
  console.log(`${width}px: offline offers no Model Engine; paired with it reported, animus:modelengine added and edited: ${JSON.stringify(json.modules['animus:modelengine'])}`);
}

(async () => {
  const browser = await chromium.launch();
  try {
    await run(browser, 1440, 900);
    await run(browser, 390, 844);
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
