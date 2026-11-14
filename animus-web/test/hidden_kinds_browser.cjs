// Documents only Advanced lists never vanish in Simple. Opening
// test/fixtures/advanced_only (a chest GUI and a trait group) as a folder
// says how many arrived in Advanced, with one Switch to Advanced that lands
// on them, and Home says the same instead of empty job cards. Pairing the
// fake server loaded with the same folder shows that Home too; a chest GUI
// the server gains while Simple is open is announced with a switch that
// opens it; Search for an Advanced document says it matches in Advanced.
// Nothing is dropped: Advanced lists every document. 1440 px, no console
// errors.
//
//   python3 tool/serve_site.py --port <web port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<web port> --data=test/fixtures/advanced_only
//   PLAYWRIGHT_MODULE=/path/to/node_modules/playwright ANIMUS_WEB_URL=http://127.0.0.1:<web port> \
//     ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/hidden_kinds_browser.cjs
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {setMode, openSearch, searchBox} = require('./support/shell.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const folder = path.resolve(__dirname, 'fixtures/advanced_only');

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

const toast = (page, text) => page.locator('.arcane-toast', {hasText: text});
const mode = page => page.evaluate(() => localStorage.getItem('animus.mode'));

async function simpleHome(page, count) {
  const home = page.locator('#animus-main .ui-home');
  await home.getByText(`${count} documents are in Advanced.`, {exact: true}).waitFor();
  assert.equal(await home.locator('.ui-home-card').count(), 0, 'no empty job cards');
  assert.equal(await home.getByTestId('switch-to-advanced').count(), 1);
}

(async () => {
  const browser = await chromium.launch({headless: true});
  const context = await browser.newContext({viewport: {width: 1440, height: 900}});
  await context.addInitScript(() => { if (!sessionStorage.getItem('seeded')) { sessionStorage.setItem('seeded', '1'); localStorage.setItem('animus.mode', 'simple'); } });
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', error => errors.push(String(error)));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  try {
    // A folder of Advanced kinds opened in Simple.
    await page.goto(site);
    await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
    const chooser = page.waitForEvent('filechooser');
    await page.getByRole('button', {name: 'Open folder', exact: true}).first().click();
    await (await chooser).setFiles(folder);
    await page.waitForFunction(() => /#\/workspace\/[^/]+$/.test(location.hash));
    const opened = toast(page, '2 imported documents are in Advanced.');
    await opened.waitFor();
    await simpleHome(page, 2);
    await opened.getByRole('button', {name: 'Switch to Advanced'}).click();
    await page.waitForFunction(() => /\/list\/group$/.test(location.hash));
    assert.equal(await mode(page), 'advanced');
    await page.locator('#animus-main .ui-library-row', {hasText: 'Harbor'}).waitFor();
    console.log('ok open folder: toast and Home say 2 in Advanced; Switch lands on Trait groups');

    // Home's own switch lands on them too.
    await setMode(page, 'simple');
    await page.evaluate(() => { location.hash = location.hash.replace(/\/list\/group$/, ''); });
    await simpleHome(page, 2);
    await page.locator('#animus-main').getByTestId('switch-to-advanced').click();
    await page.waitForFunction(() => /\/list\/group$/.test(location.hash));
    assert.equal(await mode(page), 'advanced');
    console.log('ok Home: Switch to Advanced lands on the first hidden kind');

    // A server whose documents are all Advanced kinds.
    await setMode(page, 'simple');
    await control('reset', {});
    const {code} = await control('pair?paths=both', {});
    await page.goto(`${site}/#/pair/${code}`);
    await page.getByRole('button', {name: 'Pair', exact: true}).click();
    await page.waitForFunction(() => location.hash.startsWith('#/server/'));
    await simpleHome(page, 2);
    const workspace = await page.evaluate(() => `server-${location.hash.split('/')[2]}`);
    console.log('ok paired: Home says 2 in Advanced');

    // A chest GUI the server gains while Simple is open.
    const board = await control(`document?kind=inventory&id=${encodeURIComponent('town:board')}`);
    await control('edit', {kind: 'inventory', id: 'town:counter', source: {...board.source, title: '&6Counter'}});
    const gained = toast(page, '1 new document is in Advanced.');
    await gained.waitFor();
    await gained.getByRole('button', {name: 'Switch to Advanced'}).click();
    await page.waitForFunction(w => location.hash === `#/workspace/${w}/inventory/town%3Acounter`, workspace);
    assert.equal(await mode(page), 'advanced');
    await page.getByRole('heading', {level: 1, name: 'Counter'}).waitFor();
    console.log('ok server event: 1 new document in Advanced, Switch opens it');

    // Search in Simple for a document only Advanced lists.
    await setMode(page, 'simple');
    await openSearch(page);
    await searchBox(page).fill('Town board');
    await page.getByText('1 match is in Advanced.', {exact: true}).waitFor();
    await page.keyboard.press('Escape');
    console.log('ok search: 1 match is in Advanced');

    // Nothing was dropped.
    await setMode(page, 'advanced');
    await page.evaluate(w => { location.hash = `#/workspace/${w}/list/inventory`; }, workspace);
    await page.locator('#animus-main .ui-library-row', {hasText: 'Counter'}).waitFor();
    await page.locator('#animus-main .ui-library-row', {hasText: 'Town board'}).waitFor();
    assert.deepEqual(errors, [], 'no console errors');
    console.log('hidden kinds: ok');
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exit(1);
});
