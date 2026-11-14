// Two quick field edits against tool/fake_server.dart: pair, open an NPC
// from the NPC list, change two fields with no pause between them and check
// that both are in the change, each is its own undo step, and both go to
// the server in the one change set the save bar's Apply sends.
//
//   python3 tool/serve_site.py --port <site port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<site port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<site port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/quick_edits_browser.cjs
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openJob, saveAction, saveState, showView, topbarAction} = require('./support/shell.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const npc = 'examples/greeter';

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

/// The draft as the Code view shows it.
async function draft(page) {
  await showView(page, 'code');
  const json = JSON.parse(await page.locator('.animus-code textarea').first().inputValue());
  await showView(page, 'visual');
  return json;
}

async function run(browser, width, height, label) {
  await control('reset', {});
  await control('restore', {});
  const {code} = await control('pair?paths=both', {});
  const page = await browser.newPage({viewport: {width, height}});
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()); });
  const changeSets = [];
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname.endsWith('/v1/changes')) changeSets.push(request.postDataJSON());
  });

  await page.goto(`${site}/#/pair/${code}`);
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await page.locator('[data-testid="home-count"]').first().waitFor();
  await openJob(page, 'NPCs');
  await page.locator(`#npc-list .ui-library-row[data-key="npc:${npc}"]`).click();
  await page.waitForFunction((id) => decodeURIComponent(location.hash).includes(`/npc/${id}`), npc);
  const before = await draft(page);

  // Two fields, one straight after the other.
  const main = page.locator('#animus-main');
  const x = main.locator('#animus-npc-x');
  await x.waitFor({state: 'visible'});
  await main.locator('#animus-npc-name').fill('Quick Name');
  await x.fill('12.5');
  await saveState(page).filter({hasText: '1 change not applied'}).waitFor();
  await page.waitForTimeout(300);
  let json = await draft(page);
  assert.equal(json.name, 'Quick Name', `${label}: the first quick edit is kept`);
  assert.equal(json.position.x, 12.5, `${label}: the second quick edit is kept`);

  // Each edit is its own undo step.
  await topbarAction(page, 'Undo');
  await page.waitForTimeout(300);
  json = await draft(page);
  assert.equal(json.name, 'Quick Name', `${label}: Undo keeps the first edit`);
  assert.equal(json.position.x, before.position.x, `${label}: Undo takes back the second edit`);
  await topbarAction(page, 'Undo');
  await page.waitForTimeout(300);
  json = await draft(page);
  assert.equal(json.name, before.name, `${label}: a second Undo takes back the first edit`);
  await topbarAction(page, 'Redo');
  await topbarAction(page, 'Redo');
  await page.waitForTimeout(300);
  json = await draft(page);
  assert.deepEqual([json.name, json.position.x], ['Quick Name', 12.5], `${label}: Redo brings both back`);

  // Both go to the server in one change set.
  await saveAction(page, 'apply').click();
  await saveState(page).filter({hasText: /Applied 1 change|Up to date/}).waitFor();
  assert.equal(changeSets.length, 1, `${label}: one change set`);
  const sent = changeSets[0].documents.find((document) => document.id === npc);
  assert.deepEqual([sent.source.name, sent.source.position.x], ['Quick Name', 12.5], `${label}: the change set carries both edits`);
  const stored = (await control('state')).documents.find((document) => document.kind === 'npc' && document.id === npc);
  assert.ok(stored.revision > 1, `${label}: the server applied it`);
  assert.deepEqual(errors, [], `${label}: console errors`);
  await page.close();
}

(async () => {
  const browser = await chromium.launch();
  try {
    await run(browser, 1400, 1000, 'desktop');
    await run(browser, 390, 844, 'phone');
  } finally {
    await browser.close();
  }
  console.log('quick_edits_browser: ok');
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
