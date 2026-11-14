// The (i) tip (lib/ui/info_tip.dart) on shadcn's hover card: a resting
// pointer opens it after a short delay and leaving closes it; keyboard focus
// opens it; a click pins it until Escape or a second click; it reads its
// sentences and a Docs link to the wiki; the button is named and described
// by the tip; the label beside it stays its own text. 1440 and 390 px, both
// themes, no console errors.
//
//   python3 tool/serve_site.py --port <port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/info_tip_browser.cjs
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site'); })()).replace(/\/$/, '');

async function run(browser, width, theme) {
  const label = `${width} ${theme}`;
  const context = await browser.newContext({viewport: {width, height: width <= 390 ? 844 : 900}, colorScheme: theme});
  // The editor starts dark whatever the system says; the light runs pick light.
  await context.addInitScript(mode => { try { window.localStorage.setItem('animus.theme', mode); } catch (error) { /* storage off */ } }, theme);
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(url);
  await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();

  const wrap = page.locator('.animus-info').first();
  const button = wrap.locator('[data-animus-info]');
  const tip = wrap.locator('.animus-info-tip');
  assert.equal(await button.getAttribute('aria-label'), 'More info', `${label}: the button is named`);
  const described = await button.getAttribute('aria-describedby');
  assert.ok(described && (await page.locator(`#${described}`).textContent()).trim().length > 10, `${label}: the tip describes the button`);
  const heading = page.getByRole('heading', {name: 'Pair a server'}).first();
  assert.match(await heading.textContent(), /^Pair a server/, `${label}: the heading keeps its text`);

  if (width > 390) {
    await button.hover();
    await tip.waitFor({state: 'visible', timeout: 2000});
    await page.mouse.move(2, 2);
    await tip.waitFor({state: 'hidden', timeout: 2000});
  }
  await button.focus();
  await tip.waitFor({state: 'visible', timeout: 2000});
  const docs = tip.getByRole('link', {name: 'Docs'});
  assert.match(await docs.getAttribute('href'), /^https:\/\/docs\.volmitsoftware\.com\/animus\//, `${label}: Docs links the wiki`);
  await page.keyboard.press('Escape');
  await tip.waitFor({state: 'hidden', timeout: 2000});

  // A click pins it; Escape closes it.
  await button.click();
  await tip.waitFor({state: 'visible', timeout: 2000});
  await page.mouse.move(2, 2);
  await page.waitForTimeout(400);
  assert.equal(await tip.isVisible(), true, `${label}: a clicked tip stays open`);
  await button.focus();
  await page.keyboard.press('Escape');
  await tip.waitFor({state: 'hidden', timeout: 2000});

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 0, `${label}: no sideways scroll`);
  assert.deepEqual(errors, [], `${label}: console errors`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    for (const width of [1440, 390]) for (const theme of ['dark', 'light']) await run(browser, width, theme);
    console.log('info_tip_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
