// The home page shows a paired server's real state after a reload: the
// connection starts on its own and the pill reads Online, not Offline.
//
//   python3 tool/serve_site.py --port <web port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<web port> --data=test/fixtures/server_behaviors_live
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<web port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/home_servers_browser.cjs
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');

async function control(route) {
  const response = await fetch(`${fake}/__fake/${route}`, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{}'});
  assert.ok(response.ok, `${route}: ${response.status}`);
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
    await page.getByRole('heading', {name: 'Servers'}).waitFor();
    await page.locator('.animus-server-list .animus-pill[data-phase="online"]').waitFor({timeout: 20000});
    assert.deepEqual(errors, []);
    await context.close();
    console.log('home_servers_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
