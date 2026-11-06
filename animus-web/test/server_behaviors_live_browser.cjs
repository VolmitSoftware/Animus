// server-behaviors.json against tool/fake_server.dart at desktop and phone
// widths in both themes: pair, open the list and a server behavior from the
// server catalog, add the behavior to the list in the Visual view, apply,
// and read the stored file back from the fake server, with no console
// errors and no sideways scrolling.
//
// The fake server needs a data folder that holds server-behaviors.json and
// a behavior with "server": true, for example ../schema/fixtures/export plus
// behaviors/town/welcome.json (a copy of
// ../schema/fixtures/documents/behavior/server-welcome.json) and a
// server-behaviors.json with an empty list.
//
//   python3 tool/serve_site.py --port <web port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<web port> --data=<that folder>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<web port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/server_behaviors_live_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openFromSidebar} = require('./support/shell.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined && route === 'state' ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

async function shot(page, name) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 1, `${name}: ${overflow}px of sideways scrolling`);
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: true});
}

async function notice(page, text) {
  await page.locator('.animus-live-log li', {hasText: text}).first().waitFor({state: 'attached', timeout: 15000});
}

async function run(browser, viewport, label, theme) {
  await control('reset', {});
  await control('restore', {});
  const {code} = await control('pair?paths=both', {});
  const context = await browser.newContext({viewport});
  await context.addInitScript(mode => window.localStorage.setItem('animus.theme', mode), theme);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  page.on('pageerror', error => errors.push(String(error)));

  await page.goto(`${site}/#/pair/${code}`);
  // The pairing page leads with the server's name and its fingerprint.
  await page.locator('[data-testid="pair-fingerprint"]', {hasText: 'sha256:'}).waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await page.locator('.animus-live .animus-pill[data-phase="online"]').waitFor({timeout: 20000});

  await page.getByText(/documents on the server/).waitFor();
  await page.getByRole('checkbox', {name: 'server-behaviors', exact: true}).check();
  await page.getByRole('checkbox', {name: 'town:welcome', exact: true}).check();
  await page.getByRole('button', {name: 'Open 2'}).click();
  await notice(page, 'Opened 2 documents');
  await openFromSidebar(page, 'server-behaviors');
  await page.getByRole('tab', {name: 'Visual'}).click();
  const list = page.locator('.animus-server-behaviors');
  await list.getByText('The list is empty, so no server behavior runs. Add one below.').waitFor();
  await list.locator('select', {has: page.locator('option', {hasText: 'Add a server behavior'})}).first().selectOption('town:welcome');
  await list.locator('[data-behavior="town:welcome"]').getByText('Server behavior in this workspace.').waitFor();
  await shot(page, `${label}-list`);
  const listed = async () => (await control('state')).documents.find(document => document.kind === 'server-behaviors');
  const before = await listed();
  assert.ok(before, 'the fake server lists the server-behaviors kind');
  await page.getByRole('button', {name: 'Apply 1'}).click();
  await notice(page, 'Applied 1 document');
  const after = await listed();
  assert.notEqual(after.hash, before.hash, 'the apply wrote the list');
  assert.equal(after.revision, 0, 'the list carries no revision');
  await page.getByRole('tab', {name: 'Code'}).click();
  assert.deepEqual(JSON.parse(await page.locator('.animus-code textarea').inputValue()), {schemaVersion: 1, behaviors: ['town:welcome']});
  await shot(page, `${label}-applied`);
  assert.deepEqual(errors, [], label);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    for (const theme of ['dark', 'light']) {
      await run(browser, {width: 1440, height: 900}, `live-desktop-${theme}`, theme);
      await run(browser, {width: 390, height: 844}, `live-phone-${theme}`, theme);
    }
    console.log('server_behaviors_live_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
