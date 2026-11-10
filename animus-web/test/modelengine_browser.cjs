// Opens ../schema/fixtures/export in the built site and adds the Model Engine
// module to an NPC from the inspector's "Add a known module" dropdown, fills its
// model and scale from the schema's fields, and checks the saved JSON, at desktop
// and phone widths, with no console errors and no sideways scrolling.
//
//   python3 tool/serve_site.py --port <port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<port> node test/modelengine_browser.cjs
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openFromSidebar} = require('./support/shell.cjs');
const {choose, dropdownShowing} = require('./support/select.cjs');

const exportFolder = path.resolve(__dirname, '../../schema/fixtures/export');
const url = process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })();

async function codeOf(page) {
  await page.getByRole('tab', {name: 'Code'}).click();
  const text = await page.locator('.animus-code textarea').inputValue();
  await page.getByRole('tab', {name: 'Visual'}).click();
  return JSON.parse(text);
}

async function run(browser, width, height) {
  const page = await (await browser.newContext({viewport: {width, height}})).newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(url);
  await page.getByRole('heading', {name: 'From a data folder'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(exportFolder);
  await page.getByRole('heading', {name: '1 document needs repair'}).waitFor();
  await openFromSidebar(page, 'Greeter');
  await page.locator('.animus-document-header').waitFor();
  await page.getByRole('tab', {name: 'Visual'}).click();
  const inspector = page.locator('.animus-inspector').first();
  for (const details of await inspector.locator('details').all()) {
    if (await details.locator('summary', {hasText: 'Modules'}).count() && !(await details.evaluate(element => element.open))) await details.locator('summary').first().click();
  }
  await choose(dropdownShowing(inspector, 'Add a known module'), 'animus:modelengine');
  const module = inspector.locator('[data-module="animus:modelengine"]');
  await module.waitFor();
  await module.locator('input').and(module.getByLabel(/^Model/)).first().fill('drake');
  await module.locator('input').and(module.getByLabel(/^Scale/)).first().fill('1.5');
  await page.waitForTimeout(400);
  const json = await codeOf(page);
  assert.equal(json.modules['animus:modelengine'].model, 'drake');
  assert.equal(json.modules['animus:modelengine'].scale, 1.5);
  assert.equal(json.modules['animus:modelengine'].enabled, true);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert(overflow <= 1, `sideways scrolling at ${width}px: ${overflow}`);
  assert.deepEqual(errors, []);
  console.log(`${width}px: animus:modelengine added and edited: ${JSON.stringify(json.modules['animus:modelengine'])}`);
}

(async () => {
  const browser = await chromium.launch();
  try {
    await run(browser, 1440, 900);
    await run(browser, 390, 844);
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
