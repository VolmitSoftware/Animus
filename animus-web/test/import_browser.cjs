// Import on a paired server through the Import wizard against
// tool/fake_server.dart, at desktop and phone widths in both themes, in
// Simple and Advanced: pair with the import scope, pick dtlTraders, choose a
// Citizens saves.yml and a dtlTraders shop file (and a file dtlTraders does
// not use, listed as skipped), convert on the server, read its review,
// install through the confirm (cancelled once with Escape, focus back on
// Install) and see nothing left to accept, roll back through its confirm,
// check that an import converted by an older Animus is refused in the
// server's words, check a forced refusal's copy from Check server, and
// finally check that a save larger than a relay frame is refused over the
// relay before it is sent. Simple shows no import id and no server report.
// No console errors other than the browser's own lines while the direct
// path is cut or a refusal is forced, and no sideways scrolling.
//
//   dart run tool/fake_server.dart --port=<fake> --origin=http://127.0.0.1:<site>
//   python3 tool/serve_site.py --port <site>
//   PLAYWRIGHT_MODULE=/path/to/node_modules/playwright ANIMUS_WEB_URL=http://127.0.0.1:<site> \
//     ANIMUS_FAKE_URL=http://127.0.0.1:<fake> ANIMUS_SCREENSHOTS=/tmp/shots node test/import_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;
const saves = path.resolve(__dirname, '../../schema/fixtures/citizens/runtime.yml');
// The import id of runtime.yml, read from the first (Advanced) run: Simple
// does not show it.
let importId;

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
  await page.getByRole('button', {name: button, exact: true}).click();
  await (await chooser).setFiles(files);
}

function step(page) {
  return page.locator('#animus-import-step');
}

async function state(page, wanted) {
  await page.locator('[data-testid="import-state"]', {hasText: wanted}).waitFor({timeout: 20000});
}

/// Confirms (or with [cancel], dismisses by Escape) the open confirm.
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

async function run(browser, width, height, label, scratch, theme, mode) {
  const advanced = mode === 'advanced';
  await control('reset', {});
  await control('restore', {});
  const {code} = await control('pair?paths=both&scopes=import', {});
  const context = await browser.newContext({viewport: {width, height}});
  await context.addInitScript(([theme, mode]) => {
    window.localStorage.setItem('animus.theme', theme);
    window.localStorage.setItem('animus.mode', mode);
  }, [theme, mode]);
  const page = await context.newPage();
  page.setDefaultTimeout(30000);
  const errors = [];
  let directDown = false;
  let forced = false;
  page.on('console', (message) => {
    if (message.type() !== 'error') return;
    if ((directDown || forced) && /Failed to load resource|WebSocket connection|net::ERR_/.test(message.text())) return;
    errors.push(message.text());
  });
  page.on('pageerror', (error) => errors.push(String(error)));

  await page.goto(`${site}/#/pair/${code}`);
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await page.locator('[data-testid="context-state"]', {hasText: 'Online'}).waitFor({timeout: 20000});

  // One main landmark per page: the workspace's Import page sits in the frame's main.
  const workspace = await page.evaluate(() => `server-${location.hash.split('/')[2]}`);
  await page.goto(`${site}/#/workspace/${workspace}/import`);
  await page.getByRole('heading', {name: 'Import', exact: true}).waitFor();
  assert.equal(await page.locator('main').count(), 1, 'the Import page in a workspace adds no second main');
  await page.goto(`${site}/#/citizens`);
  await page.getByRole('heading', {name: 'Import', exact: true}).waitFor();
  assert.equal(await page.locator('main').count(), 1, 'the Import page on its own is the main');
  await page.locator('label', {hasText: 'dtlTraders'}).click();
  await page.getByRole('button', {name: 'Next', exact: true}).click();
  await page.locator('[data-testid="import-connection"]', {hasText: advanced ? 'Online (direct)' : 'Online'}).waitFor({timeout: 20000});
  if (!advanced) assert.ok(!(await page.locator('[data-testid="import-connection"]').textContent()).includes('direct'), 'Simple does not name the path');
  // Convert says why it waits.
  assert.ok(await page.getByRole('button', {name: 'Convert', exact: true}).isDisabled(), 'Convert waits for files');
  await page.locator('#animus-import-why', {hasText: 'Choose the dtlTraders files first.'}).waitFor();
  await choose(page, 'Choose files', [path.join(scratch, 'baker.yml'), path.join(scratch, 'notes.txt')]);
  await choose(page, 'Choose saves.yml', saves);
  await page.locator('[data-testid="traders-files"]', {hasText: 'shops/baker/baker.yml'}).waitFor();
  await page.locator('[data-testid="traders-skipped"]', {hasText: 'Skipped notes.txt'}).waitFor();
  await shot(page, `import-${label}-01-files`);

  await page.getByRole('button', {name: 'Convert', exact: true}).click();
  await state(page, 'Ready to install');
  await page.locator('[data-testid="import-counts"]', {hasText: '6 NPCs'}).waitFor();
  if (advanced) {
    importId = (await page.locator('[data-testid="import-id"]').textContent()).trim();
    assert.match(importId, /^[0-9a-f]{64}$/);
    await page.locator('[data-testid="import-lines"] [role="listitem"]', {hasText: 'Nothing installed'}).waitFor();
  } else {
    assert.equal(await page.locator('[data-testid="import-id"]').count(), 0, 'Simple shows no import id');
    assert.equal(await page.locator('[data-testid="import-lines"]').count(), 0, 'Simple shows no server report');
    assert.doesNotMatch(await step(page).textContent(), /stag|import id|revision|catalog/i, 'Simple review wording');
  }
  await shot(page, `import-${label}-02-review`);

  const before = (await control('state')).documents.length;
  await page.getByRole('button', {name: 'Install', exact: true}).click();
  await confirm(page, 'Install this import on the server?', 'Install import', {cancel: true});
  await page.waitForFunction(() => document.activeElement?.id === 'animus-import-install');
  assert.equal((await control('state')).documents.length, before, 'cancel installs nothing');
  await page.getByRole('button', {name: 'Install', exact: true}).click();
  await shot(page, `import-${label}-03-confirm`);
  await confirm(page, 'Install this import on the server?', 'Install import');
  await state(page, 'Installed');
  await page.locator('[data-testid="import-counts"]', {hasText: '6 NPCs installed'}).waitFor();
  assert.ok((await control('state')).documents.length > before, 'install puts documents on the server');
  if (advanced) {
    await page.locator('[data-testid="import-lines"] [role="listitem"]', {hasText: 'NPCs spawn with their patrols, schedules, combat, riding, shops and builds on'}).waitFor();
    const lines = await page.locator('[data-testid="import-lines"] [role="listitem"]').allTextContents();
    assert.match(lines[0], new RegExp(`^Installed import ${importId.slice(0, 12)}; `), 'the result line reads as plain text');
    for (const line of lines) assert.doesNotMatch(line, /§|&#|^\[Animus\]/, `chat formatting in the import review: ${line}`);
  }
  assert.equal(await step(page).getByRole('button', {name: /Accept/}).count(), 0, 'install leaves nothing to accept');
  assert.equal(await step(page).getByRole('checkbox').count(), 0, 'no review categories');
  await page.getByRole('button', {name: 'Open server', exact: true}).waitFor();
  await shot(page, `import-${label}-04-result`);

  await page.getByRole('button', {name: 'Roll back', exact: true}).click();
  await confirm(page, 'Roll back this import?', 'Roll back');
  await state(page, 'Rolled back');
  assert.equal((await control('state')).documents.length, before, 'rollback removes what the import installed');
  await page.getByRole('button', {name: 'Install', exact: true}).waitFor();

  // An import converted by an older Animus is refused at install in the
  // server's own words, which name the step that converts it again.
  forced = true;
  const stale = 'Import 0d7bc5863a64 was previewed by an older Animus and is not applied: npcs/world/citizens-1.json is schemaVersion 1, '
    + 'this one writes 2; upload and stage the save again, then apply';
  await control(`fail?code=conflict&times=1&route=${encodeURIComponent(`/v1/imports/${importId}/apply`)}&message=${encodeURIComponent(stale)}`, {});
  await page.getByRole('button', {name: 'Install', exact: true}).click();
  await confirm(page, 'Install this import on the server?', 'Install import');
  const staleError = page.locator('[data-testid="import-error"]', {hasText: stale});
  await staleError.waitFor();
  assert.ok(!(await staleError.textContent()).includes('Changed on the server'), 'an import conflict is not an edit conflict');
  await state(page, 'Rolled back');
  await shot(page, `import-${label}-05-refused`);
  forced = false;

  // A forced refusal reads as its cause and next step.
  forced = true;
  await control('fail?code=busy&times=1&route=/v1/imports', {});
  await page.getByRole('button', {name: 'Back', exact: true}).click();
  await page.getByRole('button', {name: 'Check server', exact: true}).click();
  await page.locator('[data-testid="import-error"]', {hasText: 'Server is busy with another write. Try again in a moment.'}).waitFor();
  forced = false;

  // Over the relay a save larger than one relay frame needs the direct path.
  directDown = true;
  await control('drop', {path: 'direct', seconds: 60});
  if (advanced) {
    await page.locator('[data-testid="import-connection"]', {hasText: 'Online (relay)'}).waitFor({timeout: 30000});
    await page.locator('[data-testid="import-relay-note"]').waitFor();
  }
  await choose(page, 'Choose saves.yml', path.join(scratch, 'large-saves.yml'));
  await page.locator('[data-testid="import-problem"]', {hasText: 'Connect over the direct path'}).waitFor({timeout: 30000});
  assert.ok(await page.getByRole('button', {name: 'Convert', exact: true}).isDisabled(), 'the upload is refused before it is sent');
  await shot(page, `import-${label}-06-relay-limit`);
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
    await run(browser, 1440, 900, 'desktop-dark', scratch, 'dark', 'advanced');
    await run(browser, 390, 844, 'phone-dark', scratch, 'dark', 'simple');
    await run(browser, 1440, 900, 'desktop-light', scratch, 'light', 'simple');
    await run(browser, 390, 844, 'phone-light', scratch, 'light', 'advanced');
  } finally {
    await browser.close();
    fs.rmSync(scratch, {recursive: true, force: true});
  }
  console.log('import browser check passed');
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
