// The Model Engine module is offered only while the paired server reports
// Model Engine running. Opens ../schema/fixtures/export as a data folder and
// checks that the NPC's Modules tab offers known modules but not Model
// Engine, and that Integrations offers no plugin module; then pairs with
// tool/fake_server.dart (default --data) reporting Model Engine running
// (`/__fake/plugins?running=modelengine`), opens the greeter by its address,
// adds the module from Integrations' dropdown, fills its model and scale
// from the schema's fields and checks the draft JSON. Desktop and phone
// widths, no console errors and no sideways scrolling.
//
//   python3 tool/serve_site.py --port <site port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<site port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<site port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/modelengine_browser.cjs
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openFromSidebar, setMode, showView} = require('./support/shell.cjs');
const {choose, optionValues} = require('./support/select.cjs');
const {npcTab} = require('./support/npc_tabs.cjs');

const exportFolder = path.resolve(__dirname, '../../schema/fixtures/export');
const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

async function codeOf(page) {
  await showView(page, 'code');
  const text = await page.locator('.animus-code textarea').inputValue();
  await showView(page, 'visual');
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
  await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(exportFolder);
  await page.getByRole('heading', {name: 'Needs attention (1)'}).waitFor();
  await openFromSidebar(page, 'Greeter');
  await page.locator('.ui-page-header').waitFor();
  await showView(page, 'visual');
  // Modules and Integrations are Advanced tabs.
  await setMode(page, 'advanced');
  const modules = await npcTab(page, 'Modules');
  const offline = await optionValues(modules.locator('#animus-npc-add-module'));
  assert(offline.includes('animus:schedule'), `known modules offered offline: ${offline}`);
  assert(!offline.includes('animus:modelengine'), `${width}px: Model Engine offered with no server reporting it`);
  // Plugin modules live on Integrations, which offers none without a server reporting one.
  const quiet = await npcTab(page, 'Integrations');
  await quiet.getByText('No plugin on this server adds settings here.').waitFor();
  assert.equal(await quiet.locator('#animus-npc-add-module').count(), 0, `${width}px: an integration offered offline`);

  // Paired with a server that reports Model Engine running, the module is offered and edits.
  await control('reset', {});
  await control('plugins?running=modelengine', {});
  const {code} = await control('pair?paths=both', {});
  await page.goto(`${url}/#/pair/${code}`);
  await page.getByTestId('pair-submit').waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  const serverId = decodeURIComponent((await page.evaluate(() => location.hash)).split('/')[2]);
  // The workspace lists the whole catalog; a document is read when opened.
  await page.goto(`${url}/#/workspace/${encodeURIComponent(`server-${serverId}`)}/npc/${encodeURIComponent('examples/greeter')}`);
  await page.locator('.ui-page-header').waitFor();
  await showView(page, 'visual');
  const integrations = await npcTab(page, 'Integrations');
  await choose(integrations.locator('#animus-npc-add-module'), 'animus:modelengine');
  const module = integrations.locator('[data-module="animus:modelengine"]');
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
