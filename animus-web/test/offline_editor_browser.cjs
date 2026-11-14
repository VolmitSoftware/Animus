// Offline editing at desktop and phone widths: a blank workspace from the
// start page, one document of every kind made on the New page (the Kind
// picker, then each kind's form), the plain names in the lists, duplicate
// and delete with undo, another tab following an edit, Search opening a
// document, the Test pane (a dialogue answer, a server-only step), export,
// persistence across a reload, the example workspace, and
// Send to server against the fake server: the empty state with no server,
// then after pairing a pull (Advanced), an edit here, the automatic compare,
// "Use mine" for the changed document, a replace confirmation that Escape
// cancels, and Apply. Without IndexedDB the editor says so. No console
// errors and no sideways scrolling.
//
//   python3 tool/serve_site.py --port <site port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<site port> --data=test/fixtures/npc_browser
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<site port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> \
//     ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/offline_editor_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {closeDrawer, documentHrefs, isCompact, openFromSidebar, pickOption, saveState, setMode, showView, topbarAction, blankWorkspace, startAction} = require('./support/shell.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;

/// Runs the Test from start [label]: picked in the Starts dropdown when the
/// behavior has more than one, then Run.
async function pickStart(page, pane, label) {
  const starts = pane.getByRole('combobox', {name: 'Starts'});
  if (await starts.count()) await pickOption(page, starts, label);
  await pane.getByRole('button', {name: 'Run', exact: true}).click();
}

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

async function go(page, hash) {
  await page.goto(`${site}/${hash}`);
}

/** Opens a kind's form, runs [fill], creates it and waits for its page. */
async function create(page, workspace, kind, noun, fill) {
  await go(page, `#/workspace/${encodeURIComponent(workspace)}/new/${kind}`);
  await page.getByRole('heading', {name: `New ${noun}`}).waitFor();
  if (fill) await fill();
  // Every New ends in the same Create, as the lists' New dialogs do.
  await page.getByRole('button', {name: 'Create', exact: true}).click();
  await page.waitForFunction((wire) => location.hash.includes(`/${wire}/`) && !location.hash.includes('/new'), kind);
  await page.locator('.ui-page-header').waitFor();
  assert.equal(await page.locator('.ui-problems li.is-error').count(), 0, `${kind} has errors`);
}

/** Picks the option whose name starts with [label]: options with a line under them are named by both. */
async function pickLead(page, trigger, label) {
  await trigger.click();
  const list = page.locator(`[id="${await trigger.getAttribute('aria-controls')}"]`);
  const lead = label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  await list.getByRole('option', {name: new RegExp(`^${lead}(?![a-z])`)}).first().click();
}

/** Opens a More section of the form when it is closed. */
async function more(page, title = 'More') {
  const section = page.locator('details', {has: page.locator(':scope > summary', {hasText: title})}).first();
  if (!(await section.evaluate((details) => details.open))) await section.locator(':scope > summary').click();
}

async function code(page, transform) {
  await showView(page, 'code');
  const area = page.locator('.animus-code textarea');
  await area.waitFor();
  const json = JSON.parse(await area.inputValue());
  await area.fill(JSON.stringify(transform(json), null, 2));
  await page.getByRole('button', {name: 'Use this code'}).click();
  await page.getByRole('button', {name: 'Use this code'}).and(page.locator('[disabled]')).waitFor();
}

async function loadSkin(page, skinFile) {
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Choose PNG'}).click();
  await (await chooser).setFiles(skinFile);
  await page.getByRole('img', {name: 'Loaded skin face'}).waitFor();
}

async function run(browser, viewport, label, skinFile) {
  await control('reset', {});
  const context = await browser.newContext({viewport, acceptDownloads: true});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => { if (message.type() === 'error') errors.push(`${message.text()} (${message.location().url})`); });

  await go(page, '');
  await blankWorkspace(page, 'Sketchbook');
  const workspace = workspaceId(page);
  const base = `#/workspace/${encodeURIComponent(workspace)}`;

  // With no kind in the address the page asks for one.
  await go(page, `${base}/new`);
  await page.getByRole('heading', {name: 'New document', exact: true}).waitFor();
  await shot(page, label, '01-new-kind');
  await pickLead(page, page.getByRole('combobox', {name: 'Kind'}), 'NPC');
  await page.getByRole('heading', {name: 'New NPC'}).waitFor();

  // NPC: name, folder, a skin PNG, position and a behavior from a template.
  await create(page, workspace, 'npc', 'NPC', async () => {
    await page.getByLabel('Name', {exact: true}).fill('&6Old Tom');
    await page.getByRole('group', {name: 'Skin'}).getByRole('button', {name: 'PNG file'}).click();
    await loadSkin(page, skinFile);
    await pickLead(page, page.getByRole('combobox', {name: 'Behavior'}), 'New: Dialogue');
    await more(page);
    await page.getByLabel('Folder', {exact: true}).fill('town');
    await page.getByLabel('X', {exact: true}).fill('12');
    await shot(page, label, '02-new-npc');
  });
  assert.match(decodeURIComponent(page.url()), /\/npc\/town\/old-tom/);

  // Every other kind from its form.
  await create(page, workspace, 'behavior', 'behavior', async () => {
    await pickLead(page, page.getByRole('combobox', {name: 'Start from'}), 'Shop');
    await pickLead(page, page.getByRole('combobox', {name: 'Give to NPC'}), 'Old Tom');
  });
  // The Advanced kinds are made in Advanced; Simple neither lists nor opens them.
  await setMode(page, 'advanced');
  await create(page, workspace, 'pack', 'pack', async () => page.getByLabel('Name', {exact: true}).fill('Town pack'));
  await create(page, workspace, 'route', 'route', async () => {
    await page.getByLabel('Name', {exact: true}).fill('Market loop');
    await page.getByRole('group', {name: 'Moves by'}).getByRole('button', {name: 'Swimming'}).click();
  });
  await create(page, workspace, 'shop', 'shop', async () => {
    await page.getByLabel('Title', {exact: true}).fill('&6Market');
    await pickLead(page, page.getByRole('combobox', {name: 'Start from'}), 'Villager trades');
  });
  await create(page, workspace, 'quest', 'quest', async () => page.getByLabel('Title', {exact: true}).fill('Bread run'));
  await create(page, workspace, 'inventory', 'chest GUI', async () => page.getByLabel('Title', {exact: true}).fill('Board'));
  await create(page, workspace, 'menu', 'menu', async () => page.getByLabel('Title', {exact: true}).fill('Hub'));
  await create(page, workspace, 'hologram', 'hologram', async () => page.getByLabel('First line', {exact: true}).fill('&aWelcome'));
  await create(page, workspace, 'rig', 'rig', async () => page.getByLabel('Name', {exact: true}).fill('Golem'));
  await create(page, workspace, 'animation', 'animation', async () => {
    await page.getByLabel('Name', {exact: true}).fill('wave');
    await page.getByRole('group', {name: 'Motion'}).getByRole('button', {name: 'Wave'}).click();
  });
  await create(page, workspace, 'skin', 'skin', async () => loadSkin(page, skinFile));
  await create(page, workspace, 'text-animation', 'text animation', async () => page.getByLabel('Name', {exact: true}).fill('blink'));
  await create(page, workspace, 'presets', 'preset');
  await create(page, workspace, 'group', 'trait group', async () => page.getByLabel('Id', {exact: true}).fill('guards'));

  const hrefs = (await documentHrefs(page)).map((href) => decodeURIComponent(href));
  for (const expected of ['/npc/town/old-tom', '/behavior/sketchbook:old-tom-dialogue', '/behavior/sketchbook:shop', '/pack/town-pack', '/route/market-loop', '/shop/sketchbook:market',
    '/quest/sketchbook:bread-run', '/inventory/sketchbook:board', '/menu/sketchbook:hub', '/hologram/sketchbook:welcome', '/rig/sketchbook:golem', '/animation/sketchbook:golem/wave',
    '/text-animation/sketchbook:blink', '/presets/presets', '/group/guards']) {
    assert.ok(hrefs.some((href) => href.endsWith(expected)), `${label}: ${expected} is listed`);
  }
  // Colour codes are stripped in the list: "&6Old Tom" reads "Old Tom".
  await go(page, `${base}/list/npc`);
  await page.locator('.ui-library-row .ui-library-title', {hasText: /^Old Tom$/}).waitFor();
  assert.equal(await page.locator('.ui-library-row .ui-library-title', {hasText: '&6'}).count(), 0);

  // Duplicate, delete with a confirmation that Escape cancels, and undo.
  await go(page, `${base}/pack/town-pack`);
  await page.locator('#ui-page-actions-button').click();
  await page.getByRole('menuitem', {name: 'Duplicate'}).click();
  await page.getByRole('dialog').getByRole('button', {name: 'Duplicate', exact: true}).click();
  await page.waitForFunction(() => decodeURIComponent(location.hash).includes('/pack/town-pack-copy'));
  await page.locator('#ui-page-actions-button').click();
  await page.getByRole('menuitem', {name: 'Delete'}).click();
  await page.getByRole('alertdialog').waitFor();
  await shot(page, label, '03-confirm-delete');
  await page.keyboard.press('Escape');
  await page.getByRole('alertdialog').waitFor({state: 'detached'});
  assert.ok(decodeURIComponent(page.url()).includes('/pack/town-pack-copy'), 'Escape deletes nothing');
  await page.locator('#ui-page-actions-button').click();
  await page.getByRole('menuitem', {name: 'Delete'}).click();
  await page.getByRole('alertdialog').getByRole('button', {name: 'Delete pack'}).click();
  await page.waitForFunction(() => !location.hash.includes('/pack/'));
  // A second tab on the same workspace follows the edits made here.
  const other = await context.newPage();
  await other.goto(`${site}/${base}/list/pack`);
  await other.locator('.ui-library-row').first().waitFor();
  assert.equal(await other.locator('.ui-library-row[data-key="pack:town-pack-copy"]').count(), 0);
  await page.bringToFront();
  await topbarAction(page, 'Undo');
  await page.locator('.arcane-toast', {hasText: /Undid: delete /}).waitFor();
  await other.locator('.ui-library-row[data-key="pack:town-pack-copy"]').waitFor();
  await other.close();
  await page.bringToFront();
  await setMode(page, 'simple');

  // Search opens a document by its name.
  await openFromSidebar(page, 'market-loop');
  await page.waitForFunction(() => decodeURIComponent(location.hash).includes('/route/market-loop'));

  // Test: the dialogue answers a prompt; the shop's click is server only.
  const test = async (id) => {
    await go(page, `${base}/behavior/${encodeURIComponent(id)}`);
    const pane = page.locator('[data-testid="test-pane"]');
    const reveal = page.locator('.ui-page-header').getByRole('button', {name: 'Test', exact: true});
    if (!(await pane.isVisible().catch(() => false)) && await reveal.isVisible().catch(() => false)) await reveal.click();
    await pickStart(page, pane, 'On click');
    return pane;
  };
  const dialogue = await test('sketchbook:old-tom-dialogue');
  await dialogue.getByRole('button', {name: 'Any news?'}).click();
  await dialogue.locator('.animus-conv-chat').getByText('The harvest festival starts at', {exact: false}).waitFor();
  await dialogue.locator('.animus-mc-text-line', {hasText: 'harvest festival'}).first().waitFor();
  await shot(page, label, '03b-test-dialogue');
  const shop = await test('sketchbook:shop');
  await shop.locator('[data-outcome="serverOnly"]', {hasText: 'open shop'}).first().waitFor();
  await shop.getByText('1 step runs on the server only.').waitFor();
  await shot(page, label, '03c-test-server-only');

  // Export the workspace as a data folder zip.
  const download = page.waitForEvent('download');
  await topbarAction(page, 'Export zip');
  const zip = await download;
  assert.equal(zip.suggestedFilename(), 'sketchbook.zip');
  const bytes = fs.readFileSync(await zip.path());
  for (const entry of ['npcs/town/old-tom.json', 'packs/town-pack/pack.json', 'gui/presets.json', 'editor/skins/']) {
    assert.ok(bytes.includes(Buffer.from(entry)), `zip holds ${entry}`);
  }

  // Everything persists: a reload keeps the documents and the undo steps.
  await page.reload();
  await page.locator('.ui-page-header').waitFor();
  await closeDrawer(page);
  if (await isCompact(page)) {
    await page.locator('#ui-overflow-button').click();
    assert.equal(await page.getByRole('menuitem', {name: /^Undo/}).isDisabled(), false, 'undo survives a reload');
    await page.keyboard.press('Escape');
  } else {
    assert.equal(await page.locator('.ui-topbar-history button').first().isDisabled(), false, 'undo survives a reload');
  }

  // The example workspace lands on its Home.
  await go(page, '');
  await startAction(page, 'Open examples');
  await page.waitForFunction(() => /^#\/workspace\/[^/?]+$/.test(location.hash));
  await page.locator('#animus-main-heading').waitFor();
  await shot(page, label, '04-examples');

  // With no server paired, Send to server says so.
  await go(page, `${base}/sync`);
  await page.getByRole('heading', {name: 'Send to server'}).waitFor();
  await page.getByText('No server paired.', {exact: true}).waitFor();
  await page.getByRole('button', {name: 'Pair a server'}).waitFor();
  await shot(page, label, '05-send-no-server');

  // Pair with the fake server, pull a document (Advanced), change it here.
  const {code: pairing} = await control('pair?paths=direct', {});
  await go(page, `#/pair/${pairing}`);
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await page.locator('.ui-save-bar').waitFor();
  await go(page, `${base}/sync`);
  await page.locator('[data-testid="send-server-state"]', {hasText: 'Online'}).waitFor({timeout: 20000});
  await setMode(page, 'advanced');
  await page.getByRole('tablist', {name: 'Direction'}).getByRole('tab', {name: 'Pull'}).click();
  const pullList = page.locator('#pull-list');
  await pullList.locator('.ui-library-row').first().waitFor();
  await pullList.getByRole('textbox', {name: /Search/}).fill('baker');
  const bakerRow = pullList.locator('.ui-library-row[data-key="npc:town/baker"]');
  await bakerRow.locator('.ui-library-pick').click();
  await pullList.locator('.ui-library-bulk').getByRole('button', {name: 'Pull', exact: true}).click();
  await page.locator('.arcane-toast', {hasText: /Pulled 1 document into Sketchbook\./}).waitFor();
  await setMode(page, 'simple');
  await go(page, `${base}/npc/${encodeURIComponent('town/baker')}`);
  await code(page, (json) => ({...json, name: 'Baker Bea'}));
  await setMode(page, 'simple');

  // Send: compared on arrival; the changed baker is kept on the server until
  // "Use mine"; replacing asks first and Escape sends nothing.
  await go(page, `${base}/sync`);
  const summary = page.locator('[data-testid="send-summary"]');
  await summary.filter({hasText: /new, 1 changed/}).waitFor({timeout: 20000});
  const apply = page.locator('#send-apply');
  const sendsNew = Number((await apply.textContent()).match(/\d+/)[0]);
  assert.ok(sendsNew >= 15, `${label}: Apply sends the new documents: ${sendsNew}`);
  await page.getByRole('group', {name: 'Changed documents'}).getByRole('button', {name: 'Use mine'}).click();
  await apply.filter({hasText: `Apply ${sendsNew + 1}`}).waitFor();
  await shot(page, label, '06-send');
  await apply.click();
  const confirm = page.getByRole('alertdialog');
  await confirm.getByText('Replace 1 document on the server?').waitFor();
  await shot(page, label, '07-send-replace');
  await page.keyboard.press('Escape');
  await confirm.waitFor({state: 'detached'});
  assert.notEqual((await control(`document?kind=npc&id=${encodeURIComponent('town/baker')}`)).source.name, 'Baker Bea', 'cancel sent nothing');
  await apply.click();
  await confirm.getByRole('button', {name: 'Replace and apply'}).click();
  await page.locator('.ui-alert', {hasText: /Applied \d+ documents\./}).first().waitFor({timeout: 20000});
  const baker = await control(`document?kind=npc&id=${encodeURIComponent('town/baker')}`);
  assert.equal(baker.source.name, 'Baker Bea', 'the chosen replace reached the server');
  const state = await control('state');
  assert.ok(state.documents.some((document) => document.kind === 'npc' && document.id === 'town/old-tom'), 'the new NPC reached the server');
  await summary.filter({hasText: 'Everything here matches the server.'}).waitFor();
  assert.equal(await saveState(page).count(), 1, 'the save bar stays');
  await shot(page, label, '08-sent');

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
  await page.getByText('This browser is not keeping workspaces. Export a zip before closing the tab.').waitFor();
  await blankWorkspace(page, 'Scratch');
  await page.getByText('This browser is not keeping workspaces.', {exact: false}).waitFor();
  await shot(page, label, '09-no-storage');
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
