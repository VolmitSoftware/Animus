// Converts a Citizens saves.yml in the built site's converter screen and
// prints only aggregate JSON: counts and the canonical output hash (the same
// value as `converter digest <data-dir>/imports/<importId>`). Record contents
// never leave the page. Then picks a Denizen folder (DENIZEN_FOLDER, default
// schema/fixtures/denizen/town/Denizen), converts it against the same save and
// downloads its bundle. Runs at desktop and phone widths in both themes and
// fails on console errors, sideways scrolling, network requests during a
// conversion, or when the runs disagree.
//
//   python3 tool/serve_site.py --port 8098
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
const denizen = path.resolve(process.env.DENIZEN_FOLDER || path.join(__dirname, '../../schema/fixtures/denizen/town/Denizen'));
const worlds = (process.env.CITIZENS_WORLDS || '').split(';').map(pair => pair.trim()).filter(pair => pair.length > 0).join('\n');
const shots = process.env.ANIMUS_SCREENSHOTS;

async function run(browser, viewport, label, theme) {
  const context = await browser.newContext({viewport});
  await context.addInitScript((mode) => window.localStorage.setItem('animus.theme', mode), theme);
  const page = await context.newPage();
  page.setDefaultTimeout(120000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  // The brand lane ships the mark asset; until then its 404 is expected.
  page.on('console', message => { if (message.type() === 'error') errors.push(`${message.text()} ${message.location().url}`); });
  const requests = [];
  page.on('request', request => requests.push(request.url()));
  await page.goto(process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })());
  await page.getByRole('heading', {name: 'From a data folder'}).waitFor();
  await page.getByRole('button', {name: 'Convert a Citizens, Shopkeepers, dtlTraders or Denizen save'}).click();
  await page.getByRole('heading', {name: 'Convert Citizens, Shopkeepers, dtlTraders and Denizen data'}).waitFor();
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
  // No count on the review reads "null".
  assert.ok(!(await page.locator('main').textContent()).includes('null'), `${label}: a count reads null`);

  // The Denizen folder, bound to the same Citizens save.
  const folder = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: /Choose Denizen folder/}).click();
  await (await folder).setFiles(denizen);
  await page.locator('[data-testid="denizen-folder"]', {hasText: /\d+ scripts?/}).waitFor();
  const denizenBefore = requests.length;
  await page.getByRole('button', {name: /Convert Denizen/}).click();
  await page.waitForFunction(() => typeof window.animusDenizenResult === 'string');
  const denizenResult = JSON.parse(await page.evaluate(() => window.animusDenizenResult));
  if (denizenResult.error) throw new Error(`${label}: Denizen not converted: ${denizenResult.error}`);
  const denizenDownload = page.waitForEvent('download');
  await page.getByRole('button', {name: /Download Denizen bundle/}).click();
  assert.match((await denizenDownload).suggestedFilename(), /^animus-denizen-import-[0-9a-f]{12}\.zip$/);
  assert.deepEqual(requests.slice(denizenBefore).filter(url => !url.startsWith('blob:') && !url.startsWith('data:')), [], 'the Denizen conversion made no network requests');
  result.denizen = denizenResult;
  // The page must not scroll sideways at this width.
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), `${label}: horizontal overflow`);
  if (shots) {
    fs.mkdirSync(shots, {recursive: true});
    await page.screenshot({path: path.join(shots, `citizens-${label}.png`), fullPage: true});
  }
  assert.deepEqual(errors, [], `${label}: console errors`);
  await context.close();
  return result;
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    const desktop = await run(browser, {width: 1440, height: 1000}, 'desktop-dark', 'dark');
    for (const [viewport, label, theme] of [[{width: 390, height: 844}, 'phone-light', 'light'], [{width: 1440, height: 1000}, 'desktop-light', 'light'], [{width: 390, height: 844}, 'phone-dark', 'dark']]) {
      assert.deepEqual(await run(browser, viewport, label, theme), desktop, `${label} and desktop-dark conversions agree`);
    }
    console.log(JSON.stringify({
      records: desktop.records,
      valid: desktop.valid,
      skins: desktop.skins,
      behaviors: desktop.behaviors,
      conversions: desktop.conversions,
      traitKinds: desktop.traitKinds,
      classification: desktop.classification,
      issues: desktop.issues,
      warnings: desktop.warnings,
      outputHash: desktop.outputHash,
      denizen: desktop.denizen,
    }));
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error.message || error); process.exit(1); });
