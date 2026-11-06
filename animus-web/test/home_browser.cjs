// Opens ../schema/fixtures/export as a data folder in the built site and
// checks the listing, diagnostics and view switching.
//
//   python3 -m http.server 8098 --bind 127.0.0.1 --directory build/jaspr
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     node test/home_browser.cjs
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const exportFolder = path.resolve(__dirname, '../../schema/fixtures/export');

async function run(browser, viewport) {
  const page = await browser.newPage({viewport});
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })());
  await page.getByRole('heading', {name: 'Open an Animus data folder'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(exportFolder);
  await page.getByRole('heading', {name: '1 documents need repair'}).waitFor();
  const sidebar = page.locator('.animus-sidebar');
  for (const text of ['Greeter', 'Baker', 'shop:bakery', 'shop:counter', 'greeter:greeter', 'shop:keeper']) {
    await sidebar.getByText(text, {exact: true}).first().waitFor();
  }
  assert.equal(await sidebar.locator('.animus-badge.is-error').count(), 1);
  await page.getByRole('link', {name: 'NPCs: examples/broken'}).click();
  await page.locator('.animus-diagnostics').getByText('$.uuid').waitFor();
  await page.getByRole('tab', {name: 'Code'}).click();
  assert.match(page.url(), /view=code$/);
  await page.locator('.animus-code textarea').waitFor();
  await page.getByRole('tab', {name: 'Split'}).click();
  // An NPC's Split view puts the 3D preview beside the code.
  await page.locator('.animus-views.is-split .animus-npc-preview').waitFor();
  await page.locator('.animus-views.is-split .animus-code').waitFor();
  await sidebar.getByText('shop:counter', {exact: true}).click();
  await page.getByRole('tab', {name: 'Preview'}).click();
  await page.locator('.animus-chest-slot').first().waitFor();
  assert.equal(await page.locator('.animus-chest-slot').count(), 27);
  await page.reload();
  await page.getByRole('heading', {name: 'Bakery counter'}).or(page.locator('.animus-chest')).first().waitFor();
  assert.deepEqual(errors, []);
  await page.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    await run(browser, {width: 1440, height: 1000});
    await run(browser, {width: 390, height: 844});
    console.log('home_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
