// Folds and keys. A section the reader closed (an NPC inspector part, a
// behavior step) stays closed across an edit and a full redraw (the theme
// switch), and one left open stays open. The shortcut sheet opens with `?`
// and from Commands, lists the keys in the platform's labels (Cmd on a Mac,
// Ctrl elsewhere), is square and flat, closes with Escape and gives focus
// back. Cmd or Ctrl+B hides and shows the sidebar, N opens New document and
// B the NPC browser. Delete on a behavior step asks in place, and Escape
// keeps the step. A blank workspace names three ways to start. 1440 px and
// 390 px, both themes, no console errors, nothing scrolls sideways.
//
//   python3 tool/serve_site.py --port <port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/sections_shortcuts_browser.cjs
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const mod = process.platform === 'darwin' ? 'Cmd' : 'Ctrl';
const modKey = process.platform === 'darwin' ? 'Meta' : 'Control';

async function noSideways(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 0, `${where} scrolls sideways by ${overflow}px`);
}

const isOpen = locator => locator.evaluate(el => el.open);

/** Waits for the editor to settle after an edit: its commit and rebuild. */
const settle = page => page.waitForTimeout(450);

async function run(browser, {viewport, theme}) {
  const label = `${viewport.width}px ${theme}`;
  const compact = viewport.width <= 760;
  const context = await browser.newContext({viewport, colorScheme: theme});
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });

  await page.goto(url);
  await page.getByRole('button', {name: 'Create with example packs'}).click();
  await page.waitForFunction(() => location.hash.includes('/workspace/'));
  const workspace = await page.evaluate(() => location.hash.split('/')[2]);

  // NPC inspector: close Body (Identity stays open), edit the name, redraw.
  await page.goto(`${url}/#/workspace/${workspace}/npc/${encodeURIComponent('examples/bodyguard')}`);
  const identity = page.locator('details[data-section="identity"]');
  const body = page.locator('details[data-section="body"]');
  await identity.waitFor();
  if (!(await isOpen(identity))) await identity.locator(':scope > summary').click();
  if (!(await isOpen(body))) await body.locator(':scope > summary').click();
  assert.equal(await isOpen(body), true, `${label}: Body opens`);
  await body.locator(':scope > summary .animus-npc-section-summary, :scope > summary').first().click({position: {x: 4, y: 4}});
  assert.equal(await isOpen(body), false, `${label}: Body closes`);
  const name = identity.locator('input[type="text"], textarea, [contenteditable="true"]').first();
  await name.click();
  await page.keyboard.press('End');
  await page.keyboard.type(' x');
  await settle(page);
  assert.equal(await isOpen(body), false, `${label}: Body stays closed after an edit`);
  assert.equal(await isOpen(identity), true, `${label}: Identity stays open after an edit`);
  await page.locator('body').click({position: {x: 1, y: 1}});
  await page.keyboard.press(`${modKey}+z`);
  await settle(page);
  assert.equal(await isOpen(body), false, `${label}: Body stays closed after undo`);
  const themeButton = compact ? null : page.getByRole('button', {name: theme === 'dark' ? 'Use light theme' : 'Use dark theme'});
  if (themeButton) {
    await themeButton.click();
    await settle(page);
    assert.equal(await isOpen(body), false, `${label}: Body stays closed after a redraw`);
    assert.equal(await isOpen(identity), true, `${label}: Identity stays open after a redraw`);
    await page.getByRole('button', {name: theme === 'dark' ? 'Use dark theme' : 'Use light theme'}).click();
  }
  await noSideways(page, `${label} NPC inspector`);

  // A behavior step: Left closes it, an edit elsewhere leaves it closed;
  // Delete asks in place and Escape keeps it.
  await page.goto(`${url}/#/workspace/${workspace}/behavior/${encodeURIComponent('dialogue:innkeeper')}`);
  const step = page.locator('.animus-behavior-editor details.animus-node').first();
  await step.waitFor();
  const path = await step.getAttribute('data-node-path');
  const summary = step.locator(':scope > summary');
  await summary.focus();
  await page.keyboard.press('ArrowLeft');
  assert.equal(await isOpen(step), false, `${label}: Left closes ${path}`);
  const other = page.locator('.animus-behavior-editor').locator(`input[type="text"]:visible`).first();
  await other.click();
  await page.keyboard.press('End');
  await page.keyboard.type('x');
  await settle(page);
  const again = page.locator(`.animus-behavior-editor details[data-node-path="${path}"]`);
  assert.equal(await isOpen(again), false, `${label}: ${path} stays closed after an edit`);
  await page.keyboard.press(`${modKey}+z`).catch(() => {});
  await again.locator(':scope > summary').focus();
  await page.keyboard.press('ArrowRight');
  assert.equal(await isOpen(again), true, `${label}: Right opens ${path}`);
  const removable = page.locator('.animus-behavior-editor details.animus-node:has(> .animus-node-body > .animus-node-tools button[aria-label^="Remove"])').first();
  const removablePath = await removable.getAttribute('data-node-path');
  await removable.locator(':scope > summary').focus();
  await page.keyboard.press('Delete');
  const confirm = removable.locator(':scope > .animus-node-body > .animus-confirm');
  await confirm.waitFor();
  assert.match(await confirm.textContent(), /^Remove /);
  await page.waitForFunction(() => document.activeElement && document.activeElement.closest('.animus-confirm'));
  await page.keyboard.press('Escape');
  await confirm.waitFor({state: 'detached'});
  assert.equal(await page.evaluate(() => document.activeElement.classList.contains('animus-node-summary')), true, `${label}: focus back on the step`);
  await page.locator(`details[data-node-path="${removablePath}"]`).waitFor();
  await noSideways(page, `${label} behavior editor`);

  // The shortcut sheet from `?`, then from Commands.
  await page.goto(`${url}/#/workspace/${workspace}`);
  await page.getByRole('heading', {level: 1}).waitFor();
  const opener = compact ? null : page.getByRole('button', {name: 'Commands'});
  if (opener) await opener.focus(); else await page.locator('body').click({position: {x: 1, y: 1}});
  await page.keyboard.press('?');
  const sheet = page.getByRole('dialog', {name: 'Keyboard shortcuts'});
  await sheet.waitFor();
  const text = await sheet.textContent();
  for (const expected of [`${mod}+K`, `${mod}+Shift+Z`, `${mod}+S`, `${mod}+B`, 'Search documents', 'New document', 'Browse NPCs', 'Behavior steps', '3D preview', 'Conversation']) {
    assert.ok(text.includes(expected), `${label}: the sheet lists ${expected}`);
  }
  // Ctrl+Space completes on every platform (Cmd+Space is the Mac's Spotlight).
  assert.ok(!text.replaceAll('Ctrl+Space', '').includes(mod === 'Cmd' ? 'Ctrl+' : 'Cmd+'), `${label}: only ${mod} labels on this platform`);
  const look = await sheet.evaluate(el => ({radius: getComputedStyle(el).borderRadius, shadow: getComputedStyle(el).boxShadow}));
  assert.equal(look.radius, '0px', `${label}: the sheet is square`);
  assert.equal(look.shadow, 'none', `${label}: the sheet is flat`);
  await noSideways(page, `${label} shortcut sheet`);
  await page.keyboard.press('Escape');
  await sheet.waitFor({state: 'detached'});
  if (opener) assert.match(await page.evaluate(() => document.activeElement.textContent), /Commands/, `${label}: focus back where it was`);

  await page.keyboard.press(`${modKey}+k`);
  await page.getByRole('combobox', {name: 'Search commands and documents'}).fill('keyboard');
  await page.keyboard.press('Enter');
  await sheet.waitFor();
  await page.getByRole('button', {name: 'Close'}).last().click();
  await sheet.waitFor({state: 'detached'});

  // Sidebar, New document and Browse NPCs from the keyboard.
  if (!compact) {
    await page.locator('body').click({position: {x: 1, y: 1}});
    await page.keyboard.press(`${modKey}+b`);
    await page.locator('#animus-sidebar').waitFor({state: 'hidden'});
    await page.getByRole('button', {name: 'Show sidebar'}).click();
    await page.locator('#animus-sidebar').waitFor({state: 'visible'});
  }
  await page.locator('body').click({position: {x: 1, y: 1}});
  await page.keyboard.press('n');
  await page.waitForFunction(() => location.hash.endsWith('/new'));
  await page.locator('body').click({position: {x: 1, y: 1}});
  await page.keyboard.press('b');
  await page.waitForFunction(() => location.hash.endsWith('/npcs'));

  // A blank workspace says what to do first.
  await page.goto(url);
  await page.getByLabel('Workspace name').fill(`Blank ${label}`);
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => location.hash.includes('/new'));
  const blank = await page.evaluate(() => location.hash.split('/')[2]);
  await page.goto(`${url}/#/workspace/${blank}`);
  const steps = page.locator('.animus-first-steps li');
  await steps.first().waitFor();
  assert.equal(await steps.count(), 3, `${label}: three ways to start`);
  assert.deepEqual(await page.locator('.animus-first-steps .arcane-button').allInnerTexts(), ['Create an NPC', 'Import', 'Pair a server']);
  assert.equal(await page.locator(".animus-overview .arcane-button[data-variant='primary']").count(), 1, `${label}: one primary`);
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
