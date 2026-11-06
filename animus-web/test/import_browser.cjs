// Server import from the Convert Citizens save screen against
// tool/fake_server.dart, at desktop and phone widths: pair with the import
// scope, choose a Citizens saves.yml and a dtlTraders shop file, upload and
// stage them, read the server's review, apply, accept one category and then
// the rest, roll back, and finally check that a save larger than a relay frame
// is refused over the relay with a pointer to the direct path. No console
// errors other than the browser's own lines while the direct path is cut, and
// no sideways scrolling.
//
//   dart run tool/fake_server.dart &
//   python3 -m http.server 8098 --bind 127.0.0.1 --directory build/jaspr
//   PLAYWRIGHT_MODULE=/path/to/node_modules/playwright ANIMUS_SCREENSHOTS=/tmp/shots node test/import_browser.cjs
//
// ANIMUS_WEB_URL and ANIMUS_FAKE_URL point it elsewhere.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;
const saves = path.resolve(__dirname, '../../schema/fixtures/citizens/runtime.yml');

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined && route === 'state' ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

async function noSidewaysScroll(page, where) {
  const overflow = await page.evaluate(() => {
    const width = document.documentElement.clientWidth;
    if (document.documentElement.scrollWidth <= width + 1) return null;
    return [...document.querySelectorAll('body *')]
      .filter((element) => element.getBoundingClientRect().right > width + 1)
      .slice(0, 6)
      .map((element) => `${element.tagName.toLowerCase()}.${[...element.classList].join('.')} ${Math.round(element.getBoundingClientRect().right)}`);
  });
  assert.equal(overflow, null, `${where} scrolls sideways: ${JSON.stringify(overflow)}`);
}

async function shot(page, name) {
  await noSidewaysScroll(page, name);
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: true});
}

async function choose(page, button, files) {
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: button}).click();
  await (await chooser).setFiles(files);
}

async function stat(page, label) {
  const value = page.locator('[data-testid="import-review"] .animus-stat', {has: page.locator('span', {hasText: new RegExp(`^${label}$`)})}).locator('strong');
  return (await value.textContent()).trim();
}

async function state(page, wanted) {
  await page.locator('[data-testid="import-review"] .animus-stat', {hasText: wanted}).first().waitFor({timeout: 20000});
  assert.equal(await stat(page, 'state'), wanted);
}

async function run(browser, width, height, label, scratch) {
  await control('reset', {});
  await control('restore', {});
  const {code} = await control('pair?paths=both&scopes=import', {});
  const context = await browser.newContext({viewport: {width, height}});
  const page = await context.newPage();
  page.setDefaultTimeout(30000);
  const errors = [];
  let directDown = false;
  page.on('console', (message) => {
    if (message.type() !== 'error') return;
    if (directDown && /Failed to load resource|WebSocket connection|net::ERR_/.test(message.text())) return;
    errors.push(message.text());
  });
  page.on('pageerror', (error) => errors.push(String(error)));

  await page.goto(`${site}/#/pair/${code}`);
  await page.getByRole('heading', {name: /Pair with/}).waitFor();
  await page.getByRole('button', {name: 'Pair'}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await page.locator('.animus-live .animus-pill[data-phase="online"]').waitFor({timeout: 20000});

  await page.goto(`${site}/#/citizens`);
  await page.getByRole('heading', {name: 'Convert a Citizens save'}).waitFor();
  await page.getByRole('heading', {name: 'Import on a server'}).waitFor();
  await page.locator('[data-testid="import-connection"]', {hasText: 'Online (direct)'}).waitFor({timeout: 20000});
  await choose(page, 'Choose saves.yml', saves);
  await choose(page, 'Choose dtlTraders files', path.join(scratch, 'baker.yml'));
  await page.locator('[data-testid="import-files"]', {hasText: 'dtlTraders file shops/baker/baker.yml'}).waitFor();
  await shot(page, `${label}-import-01-chosen`);

  await page.getByRole('button', {name: /Upload and stage/}).click();
  await state(page, 'staged');
  assert.equal(await stat(page, 'Citizens records'), '5');
  assert.equal(await stat(page, 'valid'), '5');
  const importId = (await page.locator('[data-testid="import-id"]').textContent()).trim();
  assert.match(importId, /^[0-9a-f]{64}$/);
  await page.locator('[data-testid="import-lines"] li', {hasText: 'Nothing installed'}).waitFor();
  await shot(page, `${label}-import-02-staged`);

  const before = (await control('state')).documents.length;
  await page.getByRole('button', {name: /^\W*Apply$/}).click();
  await state(page, 'applied');
  assert.equal(await stat(page, 'NPCs installed'), '5');
  assert.ok((await control('state')).documents.length > before, 'apply installs documents on the server');
  const categories = page.locator('[data-testid="import-categories"]');
  await categories.waitFor();
  const spawn = page.getByRole('checkbox', {name: /^spawn: [1-9]\d* waiting$/});
  await spawn.check();
  await shot(page, `${label}-import-03-review`);
  await page.getByRole('button', {name: 'Accept selected'}).click();
  await page.getByRole('checkbox', {name: 'spawn: 0 waiting'}).waitFor();
  await page.locator('[data-testid="import-lines"] li', {hasText: 'Accepted spawn'}).waitFor();
  const acceptAll = page.getByRole('button', {name: 'Accept all'});
  if (await acceptAll.isEnabled()) {
    await acceptAll.click();
    await page.locator('[data-testid="import-lines"] li', {hasText: /Accepted|Nothing waiting/}).waitFor();
  }
  await shot(page, `${label}-import-04-accepted`);

  await page.getByRole('button', {name: /^\W*Roll back$/}).click();
  await page.getByRole('button', {name: /Confirm roll back/}).click();
  await state(page, 'rolled-back');
  assert.equal((await control('state')).documents.length, before, 'rollback removes what the import installed');
  await page.getByRole('button', {name: /^\W*Apply$/}).waitFor();
  await shot(page, `${label}-import-05-rolled-back`);

  // Over the relay a save larger than one relay frame needs the direct path.
  directDown = true;
  await control('drop', {path: 'direct', seconds: 60});
  await page.locator('[data-testid="import-connection"]', {hasText: 'Online (relay)'}).waitFor({timeout: 30000});
  await page.locator('[data-testid="import-relay-note"]').waitFor();
  await choose(page, 'Choose saves.yml', path.join(scratch, 'large-saves.yml'));
  await page.locator('[data-testid="import-problem"]', {hasText: 'Connect over the direct path'}).waitFor();
  assert.ok(await page.getByRole('button', {name: /Upload and stage/}).isDisabled(), 'the upload is refused before it is sent');
  await shot(page, `${label}-import-06-relay-limit`);
  await control('restore', {});
  directDown = false;

  assert.deepEqual(errors, [], `${label}: console errors`);
  await context.close();
}

(async () => {
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'animus-import-'));
  fs.writeFileSync(path.join(scratch, 'baker.yml'), 'shop:\n  name: Baker\n');
  fs.writeFileSync(path.join(scratch, 'large-saves.yml'), `# padding\n${'#'.repeat(900 * 1024)}\n`);
  const browser = await chromium.launch();
  try {
    await run(browser, 1440, 900, 'desktop', scratch);
    await run(browser, 390, 844, 'phone', scratch);
  } finally {
    await browser.close();
    fs.rmSync(scratch, {recursive: true, force: true});
  }
  console.log('import browser check passed');
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
