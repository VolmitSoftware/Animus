// Server import from the converter screen against tool/fake_server.dart, at
// desktop and phone widths in both themes: pair with the import scope, choose
// a Citizens saves.yml and a dtlTraders shop file (and a file dtlTraders does
// not use, listed as skipped), upload and stage them, read the server's
// review, apply through its inline confirm (cancelled once with Escape),
// accept one category and then the rest through the Accept all confirm, roll
// back through its confirm, check a forced refusal's copy, and finally check
// that a save larger than a relay frame is refused over the relay with a
// pointer to the direct path. No console errors other than the browser's own
// lines while the direct path is cut or a refusal is forced, and no sideways
// scrolling.
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
  await page.locator('[data-testid="import-state"]', {hasText: wanted}).waitFor({timeout: 20000});
}

/// Confirms (or with [cancel], dismisses by Escape) the open inline confirm.
async function confirm(page, question, button, {cancel = false} = {}) {
  const dialog = page.getByRole('alertdialog');
  await dialog.getByText(question).waitFor();
  if (cancel) {
    await page.keyboard.press('Escape');
    await dialog.waitFor({state: 'detached'});
    return;
  }
  await dialog.getByRole('button', {name: button}).click();
}

async function run(browser, width, height, label, scratch, theme) {
  await control('reset', {});
  await control('restore', {});
  const {code} = await control('pair?paths=both&scopes=import', {});
  const context = await browser.newContext({viewport: {width, height}});
  await context.addInitScript((mode) => window.localStorage.setItem('animus.theme', mode), theme);
  const page = await context.newPage();
  page.setDefaultTimeout(30000);
  const errors = [];
  let directDown = false;
  let forced = false;
  page.on('console', (message) => {
    if (message.type() !== 'error') return;
    if ((directDown || forced) && /Failed to load resource|WebSocket connection|net::ERR_/.test(message.text())) return;
    // The brand lane ships the mark asset; until then its 404 is expected.
    if (message.location().url.includes('animus-mark')) return;
    errors.push(message.text());
  });
  page.on('pageerror', (error) => errors.push(String(error)));

  await page.goto(`${site}/#/pair/${code}`);
  await page.locator('[data-testid="pair-fingerprint"]').waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await page.locator('.animus-live .animus-pill[data-phase="online"]').waitFor({timeout: 20000});

  await page.goto(`${site}/#/citizens`);
  await page.getByRole('heading', {name: 'Convert Citizens, Shopkeepers, dtlTraders and Denizen data'}).waitFor();
  await page.getByRole('heading', {name: 'Import on a server'}).waitFor();
  await page.locator('[data-testid="import-connection"]', {hasText: 'Online (direct)'}).waitFor({timeout: 20000});
  await choose(page, 'Choose saves.yml', saves);
  await choose(page, 'Choose dtlTraders files', [path.join(scratch, 'baker.yml'), path.join(scratch, 'notes.txt')]);
  await page.locator('[data-testid="import-files"]', {hasText: 'dtlTraders file shops/baker/baker.yml'}).waitFor();
  await page.locator('[data-testid="traders-skipped"]', {hasText: 'Skipped notes.txt'}).waitFor();
  await shot(page, `${label}-import-01-chosen`);

  await page.getByRole('button', {name: /Upload and stage/}).click();
  await state(page, 'Staged, not installed');
  assert.equal(await stat(page, 'Citizens records'), '5');
  assert.equal(await stat(page, 'valid'), '5');
  const importId = (await page.locator('[data-testid="import-id"]').textContent()).trim();
  assert.match(importId, /^[0-9a-f]{64}$/);
  await page.locator('[data-testid="import-lines"] li', {hasText: 'Nothing installed'}).waitFor();
  await shot(page, `${label}-import-02-staged`);

  const before = (await control('state')).documents.length;
  await page.getByRole('button', {name: /^\W*Apply$/}).click();
  await shot(page, `${label}-import-02b-apply-confirm`);
  await confirm(page, 'Install this import on the server?', 'Install import', {cancel: true});
  await page.waitForFunction(() => document.activeElement?.id === 'animus-import-apply');
  assert.equal((await control('state')).documents.length, before, 'cancel installs nothing');
  await page.getByRole('button', {name: /^\W*Apply$/}).click();
  await confirm(page, 'Install this import on the server?', 'Install import');
  await state(page, 'Installed, waiting for review');
  assert.equal(await stat(page, 'NPCs installed'), '5');
  assert.ok((await control('state')).documents.length > before, 'apply installs documents on the server');
  const categories = page.locator('[data-testid="import-categories"]');
  await categories.waitFor();
  const spawn = page.getByRole('checkbox', {name: /^Spawning: [1-9]\d* waiting$/});
  await spawn.check();
  await shot(page, `${label}-import-03-review`);
  await page.getByRole('button', {name: 'Accept selected'}).click();
  await page.getByRole('checkbox', {name: 'Spawning: 0 waiting'}).waitFor();
  await page.locator('[data-testid="import-lines"] li', {hasText: 'Accepted spawn'}).waitFor();
  const acceptAll = page.getByRole('button', {name: 'Accept all'});
  if (await acceptAll.isEnabled()) {
    await acceptAll.click();
    await confirm(page, 'Accept every waiting category on the server?', 'Accept all');
    await page.locator('[data-testid="import-lines"] li', {hasText: /Accepted|Nothing waiting/}).waitFor();
  }
  await shot(page, `${label}-import-04-accepted`);

  await page.getByRole('button', {name: /^\W*Roll back$/}).click();
  await confirm(page, 'Roll back this import on the server?', 'Roll back');
  await state(page, 'Rolled back');
  assert.equal((await control('state')).documents.length, before, 'rollback removes what the import installed');
  await page.getByRole('button', {name: /^\W*Apply$/}).waitFor();
  await shot(page, `${label}-import-05-rolled-back`);

  // A forced refusal reads as its cause and next step, plus the server's words.
  forced = true;
  await control('fail?code=busy&times=1&route=/v1/imports', {});
  await page.getByRole('button', {name: 'Load review'}).click();
  await page.locator('[data-testid="import-error"]', {hasText: 'The server is busy with another write. Wait a moment and try again.'}).waitFor();
  await shot(page, `${label}-import-05b-refused`);
  forced = false;

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
  const scratch = fs.mkdtempSync(path.join(process.env.ANIMUS_SCRATCH || os.tmpdir(), 'animus-import-'));
  fs.writeFileSync(path.join(scratch, 'baker.yml'), 'shop:\n  name: Baker\n');
  fs.writeFileSync(path.join(scratch, 'notes.txt'), 'not a dtlTraders file\n');
  fs.writeFileSync(path.join(scratch, 'large-saves.yml'), `# padding\n${'#'.repeat(900 * 1024)}\n`);
  const browser = await chromium.launch();
  try {
    for (const theme of ['dark', 'light']) {
      await run(browser, 1440, 900, `desktop-${theme}`, scratch, theme);
      await run(browser, 390, 844, `phone-${theme}`, scratch, theme);
    }
  } finally {
    await browser.close();
    fs.rmSync(scratch, {recursive: true, force: true});
  }
  console.log('import browser check passed');
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
