// Folds and keys. A section the reader closed (an NPC Basics section, a
// behavior step) stays closed across an edit and a full redraw (the theme
// switch), and one left open stays open. The shortcut sheet opens with `?`
// and from Search, lists the keys in the platform's labels (Cmd on a Mac,
// Ctrl elsewhere), closes with Escape and gives focus back. N opens New
// document and B the NPC list. Delete on a behavior step asks in a dialog,
// and Escape keeps the step. A blank workspace names three ways to start. 1440 px and 390 px, both themes, no console errors,
// nothing scrolls sideways.
//
//   python3 tool/serve_site.py --port <port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/sections_shortcuts_browser.cjs
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {searchBox, setMode, topbarAction, blankWorkspace, startAction} = require('./support/shell.cjs');

const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const mod = process.platform === 'darwin' ? 'Cmd' : 'Ctrl';
const modKey = process.platform === 'darwin' ? 'Meta' : 'Control';

async function noSideways(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 0, `${where} scrolls sideways by ${overflow}px`);
}

const isOpen = locator => locator.evaluate(el => el.open);

/** Waits for a `details` to open or close: the fold redraws after the key. */
const waitOpen = (locator, open, message) =>
  locator.evaluate((el, wanted) => new Promise((resolve, reject) => {
    const started = performance.now();
    const check = () => (el.open === wanted ? resolve() : performance.now() - started > 2000 ? reject(new Error('timeout')) : setTimeout(check, 20));
    check();
  }), open).catch(() => assert.fail(message));

/** Waits for the editor to settle after an edit: its commit and rebuild. */
const settle = page => page.waitForTimeout(450);

async function run(browser, {viewport, theme}) {
  const label = `${viewport.width}px ${theme}`;
  const compact = viewport.width <= 760;
  const context = await browser.newContext({viewport, colorScheme: theme});
  // The editor starts dark whatever the system says; the light runs pick light.
  await context.addInitScript(mode => { try { window.localStorage.setItem('animus.theme', mode); } catch (error) { /* storage off */ } }, theme);
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });

  await page.goto(url);
  {
    await startAction(page, 'Open examples');
  }
  await page.waitForFunction(() => location.hash.includes('/workspace/'));
  const workspace = await page.evaluate(() => location.hash.split('/')[2]);

  // NPC Basics in Advanced: open Identity, close Facings and anchors again,
  // edit the name, redraw.
  await setMode(page, 'advanced');
  await page.goto(`${url}/#/workspace/${workspace}/npc/${encodeURIComponent('examples/bodyguard')}`);
  const basics = page.locator('[data-npc-tab="basics"]');
  const identity = basics.locator('.ui-section[data-npc="identity"] > details');
  const body = basics.locator('.ui-section').filter({has: page.locator(':scope > details > summary', {hasText: 'Facings and anchors'})}).locator(':scope > details');
  await identity.waitFor();
  // Pressed at their left edge: a narrow summary has its (i) in the middle.
  if (!(await isOpen(identity))) await identity.locator(':scope > summary').click({position: {x: 4, y: 4}});
  await waitOpen(identity, true, `${label}: Identity opens`);
  if (!(await isOpen(body))) await body.locator(':scope > summary').click({position: {x: 4, y: 4}});
  await waitOpen(body, true, `${label}: Facings and anchors opens`);
  await body.locator(':scope > summary').click({position: {x: 4, y: 4}});
  await waitOpen(body, false, `${label}: Facings and anchors closes`);
  const name = basics.locator('#animus-npc-name');
  await name.click();
  await page.keyboard.press('End');
  await page.keyboard.type(' x');
  await settle(page);
  assert.equal(await isOpen(body), false, `${label}: Facings and anchors stays closed after an edit`);
  assert.equal(await isOpen(identity), true, `${label}: Identity stays open after an edit`);
  await page.locator('body').click({position: {x: 1, y: 1}});
  await page.keyboard.press(`${modKey}+z`);
  await settle(page);
  assert.equal(await isOpen(body), false, `${label}: Facings and anchors stays closed after undo`);
  if (!compact) {
    await topbarAction(page, theme === 'dark' ? 'Light theme' : 'Dark theme');
    await settle(page);
    assert.equal(await isOpen(body), false, `${label}: Facings and anchors stays closed after a redraw`);
    assert.equal(await isOpen(identity), true, `${label}: Identity stays open after a redraw`);
    await topbarAction(page, theme === 'dark' ? 'Dark theme' : 'Light theme');
  }
  await noSideways(page, `${label} NPC Basics`);

  // A behavior step in Advanced's Steps view (a conversation-shaped
  // behavior opens on its Conversation view first): Left closes it, an edit
  // in another step leaves it closed; Delete asks in a dialog and Escape
  // keeps it.
  await setMode(page, 'advanced');
  await page.goto(`${url}/#/workspace/${workspace}/behavior/${encodeURIComponent('dialogue:innkeeper')}`);
  await page.locator('#animus-main .animus-conv').waitFor();
  await page.evaluate(() => { location.hash = `${location.hash.split('?')[0]}?view=visual`; });
  const steps = '.animus-behavior-editor .ui-section.animus-node';
  const step = page.locator(steps).first();
  await step.waitFor();
  const path = await step.getAttribute('data-node-path');
  const folds = node => node.locator(':scope > details');
  await folds(step).locator(':scope > summary').focus();
  await page.keyboard.press('ArrowLeft');
  await waitOpen(folds(step), false, `${label}: Left closes ${path}`);
  const other = page.locator(`${steps}:not([data-node-path="${path}"])`).locator('input[type="text"]:visible, textarea:visible').first();
  await other.click();
  await page.keyboard.press('End');
  await page.keyboard.type('x');
  await settle(page);
  const again = folds(page.locator(`${steps}[data-node-path="${path}"]`));
  assert.equal(await isOpen(again), false, `${label}: ${path} stays closed after an edit`);
  await page.keyboard.press(`${modKey}+z`).catch(() => {});
  await again.locator(':scope > summary').focus();
  await page.keyboard.press('ArrowRight');
  await waitOpen(again, true, `${label}: Right opens ${path}`);
  // A step of a list can go; Delete on it asks first.
  const removable = page.locator('.animus-behavior-editor .animus-node-children[role="group"] > .ui-section.animus-node').first();
  const removablePath = await removable.getAttribute('data-node-path');
  await folds(removable).locator(':scope > summary').focus();
  await page.keyboard.press('Delete');
  const confirm = page.getByRole('alertdialog', {name: /^Delete /});
  await confirm.waitFor();
  await page.waitForFunction(() => document.activeElement && document.activeElement.closest('[role="alertdialog"]'));
  await page.keyboard.press('Escape');
  await confirm.waitFor({state: 'hidden'});
  await page.waitForFunction(() => document.activeElement && document.activeElement.matches('.animus-node > details > summary'), null, {timeout: 2000})
    .catch(() => assert.fail(`${label}: focus back on the step`));
  await page.locator(`${steps}[data-node-path="${removablePath}"]`).waitFor();
  await noSideways(page, `${label} behavior editor`);
  await setMode(page, 'simple');

  // The shortcut sheet from `?`, then from Search.
  await page.goto(`${url}/#/workspace/${workspace}`);
  await page.locator('.ui-save-bar').waitFor();
  const opener = page.locator('.ui-topbar').getByRole('button', {name: 'Search'});
  await opener.focus();
  await page.keyboard.press('?');
  const sheet = page.getByRole('dialog', {name: 'Keyboard shortcuts'});
  await sheet.waitFor();
  const text = await sheet.textContent();
  for (const expected of [`${mod}+K`, `${mod}+Shift+Z`, `${mod}+S`, 'Search', 'New document', 'NPCs', 'Also while typing', 'Behavior steps', '3D preview', 'Conversation']) {
    assert.ok(text.includes(expected), `${label}: the sheet lists ${expected}`);
  }
  assert.ok(!text.includes(`${mod}+B`), `${label}: no sidebar key any more`);
  // Ctrl+Space completes on every platform (Cmd+Space is the Mac's Spotlight).
  assert.ok(!text.replaceAll('Ctrl+Space', '').includes(mod === 'Cmd' ? 'Ctrl+' : 'Cmd+'), `${label}: only ${mod} labels on this platform`);
  await noSideways(page, `${label} shortcut sheet`);
  await page.keyboard.press('Escape');
  await sheet.waitFor({state: 'hidden'});
  // Phones show Search as an icon button named by its label.
  await page.waitForFunction(() => document.activeElement && (document.activeElement.getAttribute('aria-label') || document.activeElement.textContent).includes('Search'));

  await page.keyboard.press(`${modKey}+k`);
  await searchBox(page).fill('keyboard');
  // Search filters as it is typed in; Enter picks once the match is listed.
  await page.locator('[data-arcane-command="ui-search"] [role="option"]', {hasText: 'Keyboard shortcuts'}).first().waitFor();
  await page.keyboard.press('Enter');
  await sheet.waitFor();
  await sheet.getByRole('button', {name: 'Close'}).last().click();
  await sheet.waitFor({state: 'hidden'});

  // New document and the NPC list from the keyboard.
  await page.locator('body').click({position: {x: 1, y: 1}});
  await page.keyboard.press('n');
  await page.waitForFunction(() => location.hash.endsWith('/new'));
  await page.locator('body').click({position: {x: 1, y: 1}});
  await page.keyboard.press('b');
  await page.waitForFunction(() => location.hash.endsWith('/npcs'));

  // A blank workspace says what to do first.
  await page.goto(url);
  await blankWorkspace(page, `Blank ${label}`);
  await page.getByText('No documents yet.', {exact: true}).waitFor();
  const empty = page.locator('.ui-home');
  // Import is an Advanced start; Simple offers the first NPC.
  assert.deepEqual((await empty.locator('.arcane-button').allInnerTexts()).map(text => text.trim()), ['New NPC']);
  assert.equal(await empty.locator(".arcane-button[data-variant='primary']").count(), 1, `${label}: one primary`);
  await noSideways(page, `${label} blank overview`);

  assert.deepEqual(errors, [], `${label}: console errors`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch();
  try {
    for (const viewport of [{width: 1440, height: 900}, {width: 390, height: 844}]) {
      for (const theme of ['dark', 'light']) await run(browser, {viewport, theme});
    }
    console.log('sections and shortcuts: ok');
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exit(1);
});
