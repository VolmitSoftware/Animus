// server-behaviors.json against tool/fake_server.dart at desktop and phone
// widths in both themes: pair, open the list and a server behavior from the
// server catalog (an Advanced document), add the behavior to the list with
// its picker, find its row's actions in the row's "..." menu, apply, and
// read the stored file back from the fake server, with no console errors
// and no sideways scrolling. A long imported id stacks under its name
// inside its row on a phone, and a timer behavior imported from Denizen
// reads in Simple by how often it runs, with Edit in Advanced as an
// outline button.
//
// The fake server loads test/fixtures/server_behaviors_live:
// ../schema/fixtures/export plus behaviors/town/welcome.json (a copy of
// ../schema/fixtures/documents/behavior/server-welcome.json) and a
// server-behaviors.json with an empty list.
//
//   python3 tool/serve_site.py --port <web port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<web port> --data=test/fixtures/server_behaviors_live
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<web port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/server_behaviors_live_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openFromSidebar, setMode, showView} = require('./support/shell.cjs');
const {choose} = require('./support/select.cjs');

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

/// A Denizen world script as the importer writes it: parts named after
/// their events, each run by a timer.
const longId = 'denizen:command/lost_book_command_handler_for_the_harbor';
const timer = {
  schemaVersion: 1, revision: 1, id: 'denizen:world/harbor_bells', server: true, entry: 'on-delta-time-secondly',
  routines: {
    'on-delta-time-secondly': {type: 'sequence', steps: [{type: 'action', capability: 'animus:message', arguments: {text: 'Ding'}}]},
    'on-delta-time-secondly-2': {type: 'sequence', steps: []},
  },
  triggers: [
    {id: 'on-delta-time-secondly', event: 'timer', intervalTicks: 20, routine: 'on-delta-time-secondly'},
    {id: 'on-delta-time-secondly-2', event: 'timer', intervalTicks: 120, routine: 'on-delta-time-secondly-2'},
  ],
};

/// Sideways overflow of the page body, which scrolls on its own.
async function bodyOverflow(page) {
  return page.evaluate(() => { const main = document.querySelector('.ui-page'); return main ? main.scrollWidth - main.clientWidth : 0; });
}

async function run(browser, viewport, label, theme) {
  await control('reset', {});
  await control('restore', {});
  await control('edit', {kind: 'behavior', id: longId, source: {...timer, id: longId, routines: {main: {type: 'sequence', steps: []}}, entry: 'main', triggers: []}});
  await control('edit', {kind: 'behavior', id: timer.id, source: timer});
  const {code} = await control('pair?paths=both', {});
  const context = await browser.newContext({viewport});
  await context.addInitScript(mode => {
    window.localStorage.setItem('animus.theme', mode);
    window.localStorage.setItem('animus.mode', 'advanced');
  }, theme);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  page.on('pageerror', error => errors.push(String(error)));

  await page.goto(`${site}/#/pair/${code}`);
  // The pairing page leads with the server's name and its fingerprint.
  await page.getByTestId('pair-submit').waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await page.locator('[data-testid="context-state"]', {hasText: 'Online'}).waitFor({timeout: 20000});

  // The workspace fills from the catalog by itself.
  await page.locator('[data-testid="home-count"]').first().waitFor();
  await openFromSidebar(page, 'server-behaviors');
  await showView(page, 'visual');
  const list = page.locator('.animus-server-behaviors');
  await list.getByText('Nothing listed yet.').waitFor();
  await choose(list.getByRole('combobox', {name: 'Add a server behavior'}), 'town:welcome');
  const row = list.locator('[data-behavior="town:welcome"]');
  await row.waitFor();
  assert.equal(await list.getByText('Nothing listed yet.').count(), 0, 'the empty line goes with the first entry');
  // The row's actions are words in its menu, not bare arrows and an X.
  await row.getByRole('button', {name: 'More actions'}).click();
  const menu = page.getByRole('menu').filter({has: page.getByRole('menuitem', {name: 'Remove from list'})});
  await menu.waitFor();
  for (const entry of ['Open', 'Move up', 'Move down', 'Remove from list']) await menu.getByRole('menuitem', {name: entry}).waitFor();
  // Focus moves into the open menu, so Escape closes it.
  await page.waitForFunction(() => Boolean(document.activeElement && document.activeElement.closest('[role="menu"]')), null, {timeout: 3000});
  await page.keyboard.press('Escape');
  await menu.waitFor({state: 'hidden'});
  await shot(page, `${label}-list`);
  // A long id wraps under its name, clear of the row's menu; a server
  // behavior's row needs no note.
  await choose(list.getByRole('combobox', {name: 'Add a server behavior'}), longId);
  const long = list.locator(`[data-behavior="${longId}"]`);
  await long.waitFor();
  assert.ok(await bodyOverflow(page) <= 1, `${label}: the page body scrolls sideways`);
  const idBox = await long.getByText(longId, {exact: true}).boundingBox();
  const moreBox = await long.getByRole('button', {name: 'More actions'}).boundingBox();
  assert.ok(idBox.x + idBox.width <= moreBox.x + 1, `${label}: the id runs under the row's menu`);
  assert.equal(await long.getByText('Server behavior', {exact: true}).count(), 0, `${label}: a server behavior's row has no note`);
  await long.getByRole('button', {name: 'More actions'}).click();
  await page.getByRole('menuitem', {name: 'Remove from list'}).click();
  await long.waitFor({state: 'detached'});
  const listed = async () => (await control('state')).documents.find(document => document.kind === 'server-behaviors');
  const before = await listed();
  assert.ok(before, 'the fake server lists the server-behaviors kind');
  await page.locator('.ui-save-bar [data-save-action="apply"]').click();
  // The save bar says when the apply went through.
  await page.waitForFunction(() => /^Applied 1 change/.test(document.querySelector('.ui-save-bar .ui-save-message')?.textContent || ''), null, {timeout: 15000});
  const after = await listed();
  assert.notEqual(after.hash, before.hash, 'the apply wrote the list');
  assert.equal(after.revision, 0, 'the list carries no revision');
  await showView(page, 'code');
  assert.deepEqual(JSON.parse(await page.locator('[data-testid="code-view"] textarea').inputValue()), {schemaVersion: 1, behaviors: ['town:welcome']});
  await shot(page, `${label}-applied`);

  // Simple reads the imported timer behavior by how often each part runs.
  await setMode(page, 'simple');
  await openFromSidebar(page, 'Harbor bells');
  const read = page.locator('[data-testid="behavior-read"]');
  // Both parts read alike: each is a heading saying how often it runs.
  await read.getByRole('heading', {name: 'Every 6 seconds'}).waitFor();
  await read.getByRole('heading', {name: /^Every second\b/}).waitFor();
  assert.equal(await read.getByText(/delta time/i).count(), 0, `${label}: no Denizen event names in Simple`);
  const edit = page.locator('[data-testid="edit-in-advanced"]');
  assert.notEqual(await edit.getAttribute('data-action-slot'), 'primary', `${label}: Edit in Advanced is not the page's primary`);
  assert.ok(await bodyOverflow(page) <= 1, `${label}: the Simple read scrolls sideways`);
  await shot(page, `${label}-simple-timer`);
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
