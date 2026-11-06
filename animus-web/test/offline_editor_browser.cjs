// Offline editing at desktop and phone widths: a new blank workspace, one
// document of every kind made from its guided step, duplicate and delete
// with undo, a fix for a broken field, the NPC playground (skin, rig and a
// playing animation), behavior dry runs (an answered prompt, a server-only
// step), export, the starter workspace, persistence across a reload, and a
// push to the fake server after pairing (a pulled document changed here
// shows as a conflict and replaces the server copy only when chosen). No
// console errors and no sideways scrolling. The shell parts: the documents
// drawer on phones, the command palette, Undo and Redo with their shortcuts
// and notices, another tab following an edit, and the banner when the browser
// keeps no IndexedDB.
//
//   python3 -m http.server 8098 --bind 127.0.0.1 --directory build/jaspr
//   dart run tool/fake_server.dart --data=test/fixtures/npc_browser
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/offline_editor_browser.cjs
//
// ANIMUS_WEB_URL and ANIMUS_FAKE_URL point it elsewhere.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {closeDrawer, isCompact, sidebar, topbarAction} = require('./support/shell.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
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

async function shot(page, label, name) {
  await noSidewaysScroll(page, `${label} ${name}`);
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${label}-${name}.png`), fullPage: true});
}

function workspaceId(page) {
  const match = /#\/workspace\/([^/?]+)/.exec(page.url());
  assert.ok(match, `not in a workspace: ${page.url()}`);
  return decodeURIComponent(match[1]);
}

/// The select inside the field labelled [text] (a label wrapping a select
/// also holds the options' text, so getByLabel cannot match it exactly).
function select(page, text) {
  return page.locator('label.animus-field-control', {has: page.locator('.animus-field-label', {hasText: new RegExp(`^${text}$`)})}).locator('select');
}

async function go(page, hash) {
  await page.goto(`${site}/${hash}`);
}

/// Opens a kind's guided step, runs [fill], creates it and waits for its page.
async function create(page, workspace, kind, title, fill) {
  await go(page, `#/workspace/${encodeURIComponent(workspace)}/new/${kind}`);
  await page.getByRole('heading', {name: `New ${title}`}).waitFor();
  if (fill) await fill();
  await page.getByRole('button', {name: `Create ${title}`}).click();
  await page.waitForFunction((wire) => location.hash.includes(`/${wire}/`) && !location.hash.includes('/new'), kind);
  await page.locator('.animus-document-header').waitFor();
  assert.equal(await page.locator('.animus-diagnostics li.is-error').count(), 0, `${kind} has errors`);
}

async function code(page, transform) {
  await page.getByRole('tab', {name: 'Code'}).click();
  const area = page.locator('.animus-code textarea');
  await area.waitFor();
  const json = JSON.parse(await area.inputValue());
  await area.fill(JSON.stringify(transform(json), null, 2));
  await page.getByRole('button', {name: 'Apply to draft'}).click();
  await page.getByRole('button', {name: 'Apply to draft'}).and(page.locator('[disabled]')).waitFor();
}

async function run(browser, viewport, label, skinFile) {
  await control('reset', {});
  const context = await browser.newContext({viewport, acceptDownloads: true});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  // The brand lane ships the mark asset; until then its 404 is expected.
  page.on('console', (message) => { if (message.type() === 'error' && !message.location().url.includes('animus-mark')) errors.push(`${message.text()} (${message.location().url})`); });

  // A blank, named workspace lands on the New page.
  await go(page, '');
  await page.getByRole('heading', {name: 'Start a workspace'}).waitFor();
  await page.getByRole('heading', {name: 'From a data folder'}).waitFor();
  await shot(page, label, '01-home');
  await page.getByLabel('Workspace name').fill('Sketchbook');
  await page.getByRole('button', {name: 'Create blank workspace'}).click();
  await page.getByRole('heading', {name: 'New document'}).waitFor();
  const workspace = workspaceId(page);
  await shot(page, label, '02-new-page');

  // NPC: name, body, skin PNG, position and a behavior from a template.
  await create(page, workspace, 'npc', 'NPC', async () => {
    await page.getByLabel('Name', {exact: true}).fill('&6Old Tom');
    await page.getByLabel('Folder', {exact: true}).fill('town');
    await page.getByRole('radio', {name: /PNG file/}).click();
    const chooser = page.waitForEvent('filechooser');
    await page.getByRole('button', {name: 'Choose skin PNG'}).click();
    await (await chooser).setFiles(skinFile);
    await page.getByRole('img', {name: 'Loaded skin face'}).waitFor();
    await page.getByLabel('X', {exact: true}).fill('12');
    await select(page, 'Behavior').selectOption('new:dialogue');
    await shot(page, label, '03-new-npc');
  });
  assert.match(decodeURIComponent(page.url()), /\/npc\/town\/old-tom/);
  await (await sidebar(page)).getByText('sketchbook:old-tom-dialogue', {exact: false}).first().waitFor();

  // Every other kind from its guided step.
  await create(page, workspace, 'behavior', 'behavior', async () => {
    await page.getByRole('radio', {name: /^Shop/}).click();
    await select(page, 'Give it to an NPC').selectOption('town/old-tom');
  });
  await create(page, workspace, 'pack', 'pack', async () => page.getByLabel('Name', {exact: true}).fill('Town pack'));
  await create(page, workspace, 'route', 'route', async () => page.getByLabel('Name', {exact: true}).fill('Market loop'));
  await create(page, workspace, 'shop', 'shop', async () => page.getByLabel('Title', {exact: true}).fill('&6Market'));
  await create(page, workspace, 'inventory', 'chest GUI', async () => page.getByLabel('Title', {exact: true}).fill('Board'));
  await create(page, workspace, 'menu', 'menu', async () => page.getByLabel('Title', {exact: true}).fill('Hub'));
  await create(page, workspace, 'hologram', 'hologram', async () => page.getByLabel('First line', {exact: true}).fill('&aWelcome'));
  await create(page, workspace, 'rig', 'rig', async () => page.getByLabel('Name', {exact: true}).fill('Golem'));
  await create(page, workspace, 'animation', 'animation', async () => {
    await page.getByLabel('Name', {exact: true}).fill('wave');
    await page.getByRole('radio', {name: /^Wave/}).click();
  });
  await create(page, workspace, 'skin', 'skin', async () => {
    const chooser = page.waitForEvent('filechooser');
    await page.getByRole('button', {name: 'Choose skin PNG'}).click();
    await (await chooser).setFiles(skinFile);
    await page.getByRole('img', {name: 'Loaded skin face'}).waitFor();
  });
  await create(page, workspace, 'text-animation', 'text animation', async () => page.getByLabel('Name', {exact: true}).fill('blink'));
  await create(page, workspace, 'presets', 'presets');
  const side = await sidebar(page);
  // Legacy colour codes are stripped in the list: "&6Old Tom" reads "Old Tom".
  for (const text of ['Old Tom', 'sketchbook:old-tom-dialogue', 'sketchbook:shop', 'town-pack', 'market-loop', 'sketchbook:market', 'sketchbook:golem', 'sketchbook:golem/wave', 'sketchbook:blink', 'presets']) {
    await side.getByText(text, {exact: true}).first().waitFor();
  }
  assert.equal(await side.getByText('&6Old Tom', {exact: true}).count(), 0);

  // Duplicate, delete with a confirmation, and undo.
  await go(page, `#/workspace/${encodeURIComponent(workspace)}/pack/town-pack`);
  await page.getByRole('button', {name: 'Duplicate'}).click();
  await page.getByRole('button', {name: 'Copy', exact: true}).click();
  await page.waitForFunction(() => decodeURIComponent(location.hash).includes('/pack/town-pack-copy'));
  await page.getByRole('button', {name: 'Delete', exact: true}).click();
  await page.getByRole('alertdialog').getByText('Delete town-pack-copy?').waitFor();
  await shot(page, label, '04-confirm-delete');
  // Escape belongs to the confirmation that has focus.
  await page.waitForFunction(() => document.activeElement && document.activeElement.closest('[role="alertdialog"]'));
  await page.keyboard.press('Escape');
  await page.getByRole('alertdialog').waitFor({state: 'detached'});
  await page.getByRole('button', {name: 'Delete', exact: true}).click();
  await page.getByRole('button', {name: 'Delete document'}).click();
  await page.waitForFunction(() => !location.hash.includes('/pack/'));
  assert.equal(await side.getByText('town-pack-copy', {exact: true}).count(), 0);
  // A second tab on the same workspace follows the edits made here.
  const other = await context.newPage();
  await other.goto(`${site}/#/workspace/${encodeURIComponent(workspace)}`);
  await other.getByRole('heading', {name: 'Sketchbook'}).waitFor();
  assert.equal(await other.evaluate(() => [...document.querySelectorAll('#animus-sidebar .animus-tree-title, #animus-sidebar .animus-tree-id')]
      .some((el) => el.textContent === 'town-pack-copy')), false);
  await topbarAction(page, 'Undo');
  await page.locator('.arcane-toast', {hasText: 'Undid: delete town-pack-copy'}).waitFor();
  await (await sidebar(page)).getByText('town-pack-copy', {exact: true}).waitFor();
  await other.waitForFunction(() => [...document.querySelectorAll('#animus-sidebar .animus-tree-title, #animus-sidebar .animus-tree-id')].some((el) => el.textContent === 'town-pack-copy'));
  assert.equal(await other.locator('.animus-banner').count(), 0, 'an idle tab reloads without a warning');
  await other.close();
  // The first tab is in front again (rAF-based waits stall in a background tab).
  await page.bringToFront();
  // Redo and Undo from the keyboard, outside any text box.
  await closeDrawer(page);
  await page.locator('#animus-main-heading').focus();
  await page.keyboard.press('Control+Shift+Z');
  await page.locator('.arcane-toast', {hasText: 'Redid: delete town-pack-copy'}).waitFor();
  await page.waitForFunction(() => ![...document.querySelectorAll('#animus-sidebar .animus-tree-title, #animus-sidebar .animus-tree-id')].some((el) => el.textContent === 'town-pack-copy'));
  await page.keyboard.press('Control+z');
  await page.waitForFunction(() => [...document.querySelectorAll('#animus-sidebar .animus-tree-title, #animus-sidebar .animus-tree-id')].some((el) => el.textContent === 'town-pack-copy'));
  // A text box keeps its own keys: Ctrl+Z inside the search box is not a workspace undo.
  await (await sidebar(page)).getByRole('textbox', {name: 'Search documents'}).focus();
  await page.keyboard.press('Control+z');
  await page.waitForTimeout(150);
  assert.ok(await page.evaluate(() => [...document.querySelectorAll('#animus-sidebar .animus-tree-title, #animus-sidebar .animus-tree-id')].some((el) => el.textContent === 'town-pack-copy')),
      'Ctrl+Z in a text box did not undo the duplicate');
  await page.keyboard.press('Escape');
  // "/" focuses the document search (opening the drawer on phones); Escape closes the drawer.
  await page.locator('#animus-main-heading').focus();
  await page.keyboard.press('/');
  await page.waitForFunction(() => document.activeElement && document.activeElement.id === 'animus-doc-search');
  if (await isCompact(page)) {
    await page.locator('#animus-sidebar.is-open').waitFor();
    await shot(page, label, '04b-drawer');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Escape');
    await page.locator('#animus-sidebar.is-open').waitFor({state: 'detached'});
    await page.waitForFunction(() => document.activeElement && document.activeElement.id === 'animus-documents-button');
  } else {
    await page.keyboard.press('Escape');
  }
  // The command palette opens a document by name.
  if (await isCompact(page)) {
    await topbarAction(page, 'Commands');
  } else {
    await page.locator('#animus-main-heading').focus();
    await page.keyboard.press('Control+k');
  }
  const palette = page.getByRole('dialog', {name: 'Commands'});
  await palette.waitFor();
  await page.waitForFunction(() => document.activeElement && document.activeElement.id === 'animus-palette-input');
  await page.keyboard.type('market-loop');
  assert.equal(await page.locator('#animus-palette-input').inputValue(), 'market-loop');
  await palette.getByRole('option', {name: /market-loop/}).first().waitFor();
  await shot(page, label, '04c-palette');
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => decodeURIComponent(location.hash).includes('/route/market-loop'));
  await palette.waitFor({state: 'detached'});
  // Escape closes the palette and gives focus back.
  await page.locator('#animus-main-heading').focus();
  if (await isCompact(page)) {
    await topbarAction(page, 'Commands');
  } else {
    await page.keyboard.press('Meta+k');
  }
  await palette.waitFor();
  await page.waitForFunction(() => document.activeElement && document.activeElement.id === 'animus-palette-input');
  await page.keyboard.type('nothing like this at all');
  await palette.getByText('Nothing matches', {exact: false}).waitFor();
  await page.keyboard.press('Escape');
  await palette.waitFor({state: 'detached'});

  // A broken field offers a fix.
  await go(page, `#/workspace/${encodeURIComponent(workspace)}/npc/${encodeURIComponent('town/old-tom')}`);
  await code(page, (json) => ({...json, id: 'wrong'}));
  await page.locator('.animus-diagnostics li.is-error').first().waitFor();
  await page.getByRole('button', {name: 'Set id to old-tom'}).click();
  await page.getByText('Valid against the shared schema.').waitFor();

  // Playground: the NPC with its skin and the rig playing an animation.
  await code(page, (json) => ({...json, rig: {id: 'sketchbook:golem'}}));
  await page.getByRole('tab', {name: 'Preview'}).click();
  await page.getByText('Browser preview.', {exact: false}).first().waitFor();
  await page.locator('.animus-mc-stage[data-webgl="ready"]').waitFor();
  await page.locator('.animus-npc-facts').getByText(/^(tom\.png|the PNG kept in this workspace) \(classic\)$/).waitFor();
  await page.getByLabel('Animation').selectOption('sketchbook:golem/wave');
  await page.getByRole('button', {name: 'Play'}).click();
  await page.locator('.animus-npc-facts').getByText(/sketchbook:golem\/wave at tick [1-9]/).waitFor();
  await shot(page, label, '05-npc-preview');

  // Dry runs: the dialogue answers a prompt; the shop's click is server only.
  await go(page, `#/workspace/${encodeURIComponent(workspace)}/behavior/${encodeURIComponent('sketchbook:old-tom-dialogue')}?view=preview`);
  await page.getByRole('heading', {name: 'Dry run'}).waitFor();
  await page.getByRole('button', {name: /^click: a player right-clicks/}).click();
  await page.getByRole('button', {name: 'Show all'}).click();
  await page.getByRole('button', {name: 'Any news?'}).click();
  await page.locator('.animus-dryrun-speech').getByText('The harvest festival starts at', {exact: false}).waitFor();
  await page.locator('.animus-dryrun-stage .animus-mc-text-line', {hasText: 'harvest festival'}).first().waitFor();
  await shot(page, label, '06-dry-run-dialogue');
  await go(page, `#/workspace/${encodeURIComponent(workspace)}/behavior/${encodeURIComponent('sketchbook:shop')}?view=preview`);
  await page.getByRole('button', {name: /^click: a player right-clicks/}).click();
  await page.locator('.animus-dryrun-step.is-serverOnly', {hasText: 'Would open shop'}).waitFor();
  await page.getByText('1 step would run on the server and was not run here.').waitFor();
  await shot(page, label, '07-dry-run-server-only');

  // Export the workspace as a data folder zip.
  const download = page.waitForEvent('download');
  await topbarAction(page, 'Export the data folder zip', {menu: 'export'});
  const zip = await download;
  assert.equal(zip.suggestedFilename(), 'sketchbook.zip');
  const bytes = fs.readFileSync(await zip.path());
  for (const entry of ['npcs/town/old-tom.json', 'packs/town-pack/pack.json', 'gui/presets.json', 'editor/skins/']) {
    assert.ok(bytes.includes(Buffer.from(entry)), `zip holds ${entry}`);
  }

  // Everything persists: reload keeps the documents and the undo steps.
  await page.reload();
  await page.getByRole('heading', {name: 'Dry run'}).waitFor();
  if (await isCompact(page)) {
    await page.locator('#animus-menu-more').click();
    assert.equal(await page.getByRole('menuitem', {name: 'Undo'}).isDisabled(), false, 'undo survives a reload');
    await page.keyboard.press('Escape');
  } else {
    assert.equal(await page.locator('.animus-topbar').getByRole('button', {name: 'Undo'}).isDisabled(), false, 'undo survives a reload');
  }

  // The starter workspace.
  await go(page, '');
  await page.getByRole('heading', {name: 'Workspaces in this browser'}).waitFor();
  await page.getByRole('link', {name: 'Sketchbook'}).waitFor();
  await page.getByRole('button', {name: 'Create with example packs'}).click();
  await page.getByRole('heading', {name: 'Try it in the browser'}).waitFor();
  await page.getByRole('link', {name: /Preview Greeter/}).waitFor();
  await shot(page, label, '08-starter');

  // With no server paired, the sync page says how to pair one.
  await go(page, `#/workspace/${encodeURIComponent(workspace)}/sync`);
  await page.locator('[data-testid="sync-no-server"]', {hasText: 'Run /animus editor pair on the server'}).waitFor();
  await page.getByRole('button', {name: 'Pair a server'}).waitFor();
  await shot(page, label, '08b-sync-no-server');

  // Pair with the fake server, pull a document, change it here, push.
  const {code: pairing} = await control('pair?paths=direct', {});
  await go(page, `#/pair/${pairing}`);
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await go(page, `#/workspace/${encodeURIComponent(workspace)}/sync`);
  await page.getByRole('heading', {name: 'Send to a server'}).waitFor();
  await page.locator('.animus-pill[data-phase="online"]').waitFor();
  await page.getByRole('button', {name: 'Load the server catalog'}).click();
  await page.getByRole('checkbox', {name: 'npc town/baker'}).check();
  await page.getByRole('button', {name: 'Compare 1 selected'}).click();
  await page.getByRole('button', {name: 'Pull 1 document'}).click();
  await (await sidebar(page)).getByText('town/baker', {exact: false}).first().waitFor();
  await go(page, `#/workspace/${encodeURIComponent(workspace)}/npc/${encodeURIComponent('town/baker')}`);
  await code(page, (json) => ({...json, name: 'Baker Bea'}));
  await go(page, `#/workspace/${encodeURIComponent(workspace)}/sync`);
  await page.locator('.animus-pill[data-phase="online"]').waitFor();
  await page.getByRole('button', {name: /^Compare with/}).click();
  await page.getByRole('group', {name: 'New on the server'}).or(page.getByText('New on the server')).first().waitFor();
  await page.getByText('Different on the server').waitFor();
  await page.getByRole('radio', {name: 'Replace it with mine'}).click();
  const pushCount = Number((await page.getByRole('button', {name: /^Push \d+ documents?$/}).textContent()).match(/\d+/)[0]);
  assert.ok(pushCount >= 14, `pushes the new documents and the baker: ${pushCount}`);
  assert.equal(await page.getByRole('button', {name: /^Push \d+/}).isDisabled(), true, 'push waits for validation');
  await page.getByRole('button', {name: /^Validate \d+ on the server/}).click();
  await page.getByText('The server accepts this selection.').waitFor();
  await shot(page, label, '09-push-validated');
  await page.getByLabel('Change message', {exact: true}).fill('Offline sketches');
  // Replacing a server copy asks first; Escape cancels and sends nothing.
  await page.getByRole('button', {name: /^Push \d+/}).click();
  await page.getByRole('alertdialog').getByText('Replace 1 server copy with yours?').waitFor();
  await shot(page, label, '09b-replace-confirm');
  await page.keyboard.press('Escape');
  await page.getByRole('alertdialog').waitFor({state: 'detached'});
  await page.waitForFunction(() => document.activeElement?.id === 'animus-sync-push');
  assert.notEqual((await control(`document?kind=npc&id=${encodeURIComponent('town/baker')}`)).source.name, 'Baker Bea', 'cancel sent nothing');
  await page.getByRole('button', {name: /^Push \d+/}).click();
  await page.getByRole('alertdialog').getByRole('button', {name: 'Replace and push'}).click();
  await page.locator('.animus-ok', {hasText: /The server applied \d+ documents? as change/}).waitFor();
  const state = await control('state');
  const baker = await control(`document?kind=npc&id=${encodeURIComponent('town/baker')}`);
  assert.equal(baker.source.name, 'Baker Bea', 'the chosen overwrite reached the server');
  assert.ok(state.documents.some((document) => document.kind === 'npc' && document.id === 'town/old-tom'), 'the new NPC reached the server');
  await shot(page, label, '10-pushed');
  await page.locator('[data-testid="sync-nothing"]', {hasText: /already match the server\. Nothing to push\./}).waitFor();

  assert.deepEqual(errors, []);
  await context.close();
}

/** Without IndexedDB the editor still works in memory and says so. */
async function withoutStorage(browser, viewport, label) {
  const context = await browser.newContext({viewport});
  await context.addInitScript(() => { delete window.indexedDB; });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(site);
  await page.getByRole('heading', {name: 'Start a workspace'}).waitFor();
  await page.getByRole('status').filter({hasText: 'This browser is not keeping workspaces between visits. Export a zip before you close the tab.'}).waitFor();
  await page.getByRole('button', {name: 'Create blank workspace'}).click();
  await page.waitForFunction(() => location.hash.includes('/new'));
  await page.getByText('This browser is not keeping workspaces between visits.', {exact: false}).waitFor();
  await shot(page, label, '11-no-storage');
  assert.deepEqual(errors, []);
  await context.close();
}

(async () => {
  const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'animus-offline-'));
  const browser = await chromium.launch({headless: true});
  try {
    const page = await browser.newPage();
    const skin = await page.evaluate(() => {
      const canvas = document.createElement('canvas');
      canvas.width = 64;
      canvas.height = 64;
      const context = canvas.getContext('2d');
      context.fillStyle = '#c8874a';
      context.fillRect(0, 0, 64, 64);
      context.fillStyle = '#2f5d8a';
      context.fillRect(0, 16, 64, 16);
      return canvas.toDataURL('image/png').split(',')[1];
    });
    await page.close();
    const skinFile = path.join(folder, 'tom.png');
    fs.writeFileSync(skinFile, Buffer.from(skin, 'base64'));
    await run(browser, {width: 1440, height: 1000}, 'desktop', skinFile);
    await run(browser, {width: 390, height: 844}, 'phone', skinFile);
    await withoutStorage(browser, {width: 1440, height: 1000}, 'desktop');
    await withoutStorage(browser, {width: 390, height: 844}, 'phone');
    console.log('offline_editor_browser: ok');
  } finally {
    await browser.close();
    fs.rmSync(folder, {recursive: true, force: true});
  }
})().catch((error) => { console.error(error); process.exit(1); });
