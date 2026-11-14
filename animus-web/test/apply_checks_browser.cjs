// The save bar's Apply in Simple checks first: a change with a problem
// opens Review and sends nothing; a clean change applies without it. At 1440
// px dark and 390 px light, against the fake server with its default data.
//
//   python3 tool/serve_site.py --port <site port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<site port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<site port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> \
//     node test/apply_checks_browser.cjs
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {saveAction, saveState, setMode, showView, waitOnline} = require('./support/shell.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const npc = 'examples/greeter';

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? undefined : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`${route}: HTTP ${response.status}`);
  return response.json();
}

const serverCopy = () => control(`document?kind=npc&id=${encodeURIComponent(npc)}`);
const reviewBody = (page) => page.locator('#ui-review [data-testid="review"]');

/** Replaces the open NPC's draft through the Code view, then goes back to Simple. */
async function edit(page, change) {
  await showView(page, 'code');
  const area = page.locator('.animus-code textarea').first();
  const json = JSON.parse(await area.inputValue());
  change(json);
  await area.fill(JSON.stringify(json, null, 2));
  await page.getByRole('button', {name: 'Use this code'}).click();
  await saveState(page).filter({hasText: /^1 change not applied/}).waitFor();
  await setMode(page, 'simple');
  await page.evaluate(() => { location.hash = location.hash.split('?')[0]; });
}

async function run(browser, viewport, label, theme) {
  await control('reset', {});
  await control('restore', {});
  const {code} = await control('pair?paths=both', {});
  const context = await browser.newContext({viewport});
  await context.addInitScript((mode) => window.localStorage.setItem('animus.theme', mode), theme);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()); });
  page.on('pageerror', (error) => errors.push(String(error)));

  await page.goto(`${site}/#/pair/${code}`);
  await page.getByTestId('pair-submit').click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await waitOnline(page);
  await page.locator('[data-testid="home-count"]').first().waitFor();
  await page.evaluate((id) => { location.hash = location.hash.replace(/^#\/server\/([^/]+).*$/, `#/workspace/server-$1/npc/${encodeURIComponent(id)}`); }, npc);
  await page.locator('.ui-page-header').waitFor();

  // A change with a problem: Apply opens Review and the server keeps its copy.
  const before = await serverCopy();
  await edit(page, (json) => { json.id = 'wrong'; });
  await saveAction(page, 'apply').click();
  await reviewBody(page).waitFor();
  assert.equal((await serverCopy()).revision, before.revision, `${label}: nothing was sent`);
  assert.match(await saveState(page).innerText(), /^1 change not applied/, `${label}: the change waits`);
  await page.locator('#ui-review').getByRole('button', {name: 'Close'}).first().click();
  await reviewBody(page).waitFor({state: 'hidden'});

  // Back to the server copy, then a clean change: Apply sends it, no Review.
  await edit(page, (json) => { json.id = before.source.id; json.name = 'Greeter Prime'; });
  await saveAction(page, 'apply').click();
  await saveState(page).filter({hasText: /Applied 1 change|Up to date/}).waitFor({timeout: 20000});
  assert.equal(await reviewBody(page).isVisible(), false, `${label}: Review stays shut`);
  const after = await serverCopy();
  assert.equal(after.source.name, 'Greeter Prime', `${label}: the change reached the server`);
  assert.equal(after.revision, before.revision + 1);

  assert.deepEqual(errors, [], label);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    for (const [viewport, label, theme] of [[{width: 1440, height: 900}, 'apply-1440-dark', 'dark'], [{width: 390, height: 844}, 'apply-390-light', 'light']]) {
      await run(browser, viewport, label, theme);
      console.log(`${label}: ok`);
    }
    console.log('apply_checks_browser: ok');
  } finally {
    await browser.close();
  }
})().catch((error) => { console.error(error); process.exit(1); });
