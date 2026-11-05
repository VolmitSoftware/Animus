// Converts a Citizens saves.yml in the built site's Convert Citizens save
// screen and prints only aggregate JSON: counts and the canonical output hash
// (the same value as `converter digest <data-dir>/imports/<importId>`). Record
// contents never leave the page. Runs at desktop and phone widths and fails
// on console errors or when the two widths disagree.
//
//   python3 -m http.server 8098 --bind 127.0.0.1 --directory build/jaspr
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     CITIZENS_SAVES=../schema/fixtures/citizens/traits.yml CITIZENS_WORLDS='legacy=world;Old Nether=world_nether' \
//     node test/citizens_converter_browser.cjs
//
// CITIZENS_WORLDS is optional: old=new pairs separated by ';'. ANIMUS_SCREENSHOTS
// (a folder) saves one screenshot per width.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const saves = process.env.CITIZENS_SAVES;
if (!saves || !fs.statSync(saves, {throwIfNoEntry: false})?.isFile()) {
  console.error('Set CITIZENS_SAVES to a saves.yml file');
  process.exit(2);
}
const worlds = (process.env.CITIZENS_WORLDS || '').split(';').map(pair => pair.trim()).filter(pair => pair.length > 0).join('\n');
const shots = process.env.ANIMUS_SCREENSHOTS;

async function run(browser, viewport, label) {
  const page = await browser.newPage({viewport});
  page.setDefaultTimeout(120000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  const requests = [];
  page.on('request', request => requests.push(request.url()));
  await page.goto(process.env.ANIMUS_WEB_URL || 'http://127.0.0.1:8098/');
  await page.getByRole('heading', {name: 'Open an Animus data folder'}).waitFor();
  await page.getByRole('button', {name: 'Convert Citizens save'}).click();
  await page.getByRole('heading', {name: 'Convert a Citizens save'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Choose saves.yml'}).click();
  await (await chooser).setFiles(path.resolve(saves));
  if (worlds) await page.getByRole('textbox').fill(worlds);
  const before = requests.length;
  // The icon font glyph is part of the button's name.
  await page.getByRole('button', {name: /^\W*Convert$/}).click();
  await page.waitForFunction(() => typeof window.animusCitizensResult === 'string');
  const result = JSON.parse(await page.evaluate(() => window.animusCitizensResult));
  if (result.error) throw new Error(`${label}: not converted: ${result.error}`);
  await page.getByRole('heading', {name: 'Review'}).waitFor();
  await page.getByRole('button', {name: 'Download data-folder bundle'}).waitFor();
  const download = page.waitForEvent('download');
  await page.getByRole('button', {name: 'Download data-folder bundle'}).click();
  const bundle = await download;
  assert.match(bundle.suggestedFilename(), /^animus-citizens-import-[0-9a-f]{12}\.zip$/);
  const local = requests.slice(before).filter(url => !url.startsWith('blob:') && !url.startsWith('data:'));
  assert.deepEqual(local, [], 'the conversion made no network requests');
  // The page must not scroll sideways at this width.
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), `${label}: horizontal overflow`);
  if (shots) {
    fs.mkdirSync(shots, {recursive: true});
    await page.screenshot({path: path.join(shots, `citizens-${label}.png`), fullPage: true});
  }
  assert.deepEqual(errors, [], `${label}: console errors`);
  await page.close();
  return result;
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    const desktop = await run(browser, {width: 1440, height: 1000}, 'desktop');
    const phone = await run(browser, {width: 390, height: 844}, 'phone');
    assert.deepEqual(phone, desktop, 'desktop and phone conversions agree');
    console.log(JSON.stringify({
      records: desktop.records,
      valid: desktop.valid,
      skins: desktop.skins,
      traitKinds: desktop.traitKinds,
      classification: desktop.classification,
      issues: desktop.issues,
      warnings: desktop.warnings,
      outputHash: desktop.outputHash,
    }));
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error.message || error); process.exit(1); });
