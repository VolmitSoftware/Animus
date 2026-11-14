// Pairing lands on Home from the catalog alone. With every document read
// held back, Home shows each Simple job's count from the catalog, leaves the
// Advanced jobs out and has no Needs attention yet; once the reads go
// through, the NPC with a problem fills Needs attention in. Prints the time
// from Pair to Home as the page saw it.
//
//   python3 tool/serve_site.py --port <web port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<web port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<web port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/pair_home_browser.cjs
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

/** Each Home card's job and count. */
function homeCounts(page) {
  return page.evaluate(() => Object.fromEntries([...document.querySelectorAll('.ui-home-card[data-job]')].map(card => [
    card.getAttribute('data-job'),
    (card.querySelector('[data-testid="home-count"]')?.textContent || '').trim(),
  ])));
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    await control('reset', {});
    await control('restore', {});
    const state = await control('state');
    const count = kind => String(state.documents.filter(document => document.kind === kind).length);
    const expected = {npcs: count('npc'), behaviors: count('behavior'), shops: count('shop'), movement: '0', holograms: count('hologram')};
    const {code} = await control('pair?paths=both', {});
    const context = await browser.newContext({viewport: {width: 1440, height: 900}});
    await context.addInitScript(() => window.localStorage.setItem('animus.mode', 'simple'));
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));

    // Every document read waits until it is let through.
    const held = [];
    let release;
    const released = new Promise(resolve => { release = resolve; });
    await page.route('**/v1/documents/read', async route => {
      held.push(route);
      await released;
      await route.continue();
    });

    await page.goto(`${site}/#/pair/${code}`);
    await page.getByTestId('pair-submit').waitFor();
    await page.evaluate(() => {
      const button = document.querySelector('[data-testid="pair-submit"]');
      button.addEventListener('click', () => {
        const started = performance.now();
        new MutationObserver((_, observer) => {
          if (!document.querySelector('.ui-home-card[data-job] [data-testid="home-count"]')) return;
          window.homeShownMs = Math.round(performance.now() - started);
          observer.disconnect();
        }).observe(document.body, {subtree: true, childList: true});
      }, {capture: true});
    });
    await page.getByTestId('pair-submit').click();
    await page.waitForFunction(want => {
      const counts = Object.fromEntries([...document.querySelectorAll('.ui-home-card[data-job]')].map(card => [card.getAttribute('data-job'), (card.querySelector('[data-testid="home-count"]')?.textContent || '').trim()]));
      return Object.entries(want).every(([job, value]) => counts[job] === value);
    }, expected);
    const shownMs = await page.evaluate(() => window.homeShownMs);
    console.log(`Home with every count ${shownMs} ms after Pair; ${held.length} document reads held`);
    assert.ok(held.length > 0, 'the background read started');
    assert.deepEqual(await homeCounts(page), expected, 'Simple counts its own jobs from the catalog and leaves the Advanced ones out');
    assert.equal(await page.locator('[aria-labelledby="home-attention"]').count(), 0, 'nothing needs attention before any document is read');

    release();
    const attention = page.locator('[aria-labelledby="home-attention"]');
    await attention.locator('a[href$="/npc/examples%2Fbroken"]').waitFor();
    assert.deepEqual(await homeCounts(page), expected, 'the counts stay once the documents are read');
    assert.deepEqual(errors, []);
    await context.close();
    console.log('pair_home_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
