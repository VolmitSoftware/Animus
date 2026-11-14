// The start page's Continue list shows each paired server once, with its
// real state after a reload from one hello per server and no connection or
// event stream: Online while it answers, Not reachable while it restarts,
// Online again once it is back.
//
//   python3 tool/serve_site.py --port <web port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<web port> --data=test/fixtures/server_behaviors_live
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<web port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/home_servers_browser.cjs
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');

async function control(route, body = {}) {
  const response = await fetch(`${fake}/__fake/${route}`, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

async function state() {
  const response = await fetch(`${fake}/__fake/state`);
  return response.json();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    await control('reset');
    await control('restore');
    const {code} = await control('pair?paths=both');
    const context = await browser.newContext({viewport: {width: 1440, height: 900}});
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    await page.goto(`${site}/#/pair/${code}`);
    await page.getByRole('button', {name: 'Pair', exact: true}).click();
    await page.waitForFunction(() => location.hash.startsWith('#/server/'));
    await page.goto(`${site}/#/`);
    await page.reload();
    await page.getByRole('heading', {name: 'Continue'}).waitFor();
    const servers = page.locator('.ui-continue-row[data-kind="server"]');
    const serverState = servers.locator('[data-testid="server-state"]');
    await serverState.filter({hasText: 'Online'}).waitFor({timeout: 20000});
    assert.equal(await servers.count(), 1, 'the server is listed once');
    assert.equal(await page.locator('.ui-continue-row[data-kind="workspace"]').count(), 0, 'its workspace is not listed again');
    const streams = (await state()).streams;
    assert.deepEqual(streams, {direct: 0, relay: 0}, 'the start page opens no event stream');

    // A restart: the start page says Not reachable, then Online once it is back.
    await control('offline', {seconds: 6});
    await page.reload();
    await serverState.filter({hasText: 'Not reachable'}).waitFor({timeout: 20000});
    await page.waitForTimeout(6500);
    await page.reload();
    await serverState.filter({hasText: 'Online'}).waitFor({timeout: 20000});

    // Open goes back to the server's Home.
    await servers.getByRole('button', {name: /^Open /}).click();
    await page.waitForFunction(() => location.hash.startsWith('#/server/'));
    await page.locator('[data-testid="home-count"]').first().waitFor();
    assert.deepEqual(errors.filter((text) => !/Failed to load resource|WebSocket connection|net::ERR_/.test(text)), []);
    await context.close();
    console.log('home_servers_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
