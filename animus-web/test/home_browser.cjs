// The home page and the shell around it, at desktop and phone widths: the
// start rows, the workspace list with rename (Enter and Escape, focus back
// on the button), delete with Escape and restore, the skip link, routes to a
// missing workspace or document, then ../schema/fixtures/export opened as a
// data folder with its listing, diagnostics and view tabs (arrow keys).
//
//   python3 -m http.server <port> --bind 127.0.0.1 --directory build/jaspr
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/home_browser.cjs
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openFromSidebar, sidebar} = require('./support/shell.cjs');

const exportFolder = path.resolve(__dirname, '../../schema/fixtures/export');
const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');

async function noSideways(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 0, `${where} scrolls sideways by ${overflow}px`);
}

const focusedId = page => page.evaluate(() => document.activeElement && document.activeElement.id);

async function run(browser, viewport, label) {
  const context = await browser.newContext({viewport});
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(url);

  // One focal point and three ways to start.
  await page.getByRole('heading', {level: 1, name: 'Start a workspace'}).waitFor();
  assert.deepEqual(await page.locator('.animus-start-way h2').allTextContents(), ['Blank', 'From the example packs', 'From a data folder']);
  assert.equal(await page.locator(".animus-start .arcane-button[data-variant='primary']").count(), 1, 'one primary action on the home page');
  await page.getByText('edits do not go back into the folder', {exact: false}).waitFor();
  await page.getByRole('button', {name: 'Convert a Citizens, Shopkeepers, dtlTraders or Denizen save'}).waitFor();
  await page.getByText('None yet. A workspace you start above', {exact: false}).waitFor();
  await noSideways(page, `${label} home`);

  // The skip link is the first stop and moves focus to the main content.
  await page.keyboard.press('Tab');
  assert.equal(await page.evaluate(() => document.activeElement.textContent), 'Skip to content');
  await page.keyboard.press('Enter');
  assert.equal(await focusedId(page), 'animus-main');

  // Two workspaces; rename one from the keyboard.
  await page.getByLabel('Workspace name').fill('First');
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => location.hash.includes('/new'));
  await page.goto(url);
  await page.getByRole('button', {name: 'Create with example packs'}).click();
  await page.getByRole('heading', {name: 'Try it in the browser'}).waitFor();
  await page.goto(url);
  const rename = page.getByRole('button', {name: 'Rename First'});
  await rename.click();
  await page.waitForFunction(() => document.activeElement && document.activeElement.id === 'animus-workspace-rename');
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => document.activeElement && document.activeElement.getAttribute('aria-label') === 'Rename First');
  await rename.click();
  await page.waitForFunction(() => document.activeElement && document.activeElement.id === 'animus-workspace-rename');
  await page.locator('#animus-workspace-rename').fill('Renamed');
  await page.keyboard.press('Enter');
  await page.getByRole('link', {name: 'Renamed'}).waitFor();
  await page.waitForFunction(() => document.activeElement && document.activeElement.getAttribute('aria-label') === 'Rename Renamed');

  // Delete asks first; Escape keeps it; Restore lasts until the tab closes.
  await page.getByRole('button', {name: 'Delete Renamed'}).click();
  await page.getByRole('alertdialog').waitFor();
  await page.waitForFunction(() => document.activeElement && document.activeElement.closest('[role="alertdialog"]'));
  await page.keyboard.press('Escape');
  await page.getByRole('alertdialog').waitFor({state: 'detached'});
  await page.waitForFunction(() => document.activeElement && document.activeElement.getAttribute('aria-label') === 'Delete Renamed');
  await page.getByRole('button', {name: 'Delete Renamed'}).click();
  await page.getByRole('button', {name: 'Delete workspace'}).click();
  await page.getByText('You can restore it until you close the tab.', {exact: false}).waitFor();
  await page.getByRole('button', {name: 'Restore Renamed'}).click();
  await page.getByRole('link', {name: 'Renamed'}).waitFor();
  await noSideways(page, `${label} workspace list`);

  // Routes to things that are not here name the cause and a way back.
  await page.goto(`${url}/#/workspace/nope`);
  await page.locator('.animus-banner').getByText('There is no workspace "nope" in this browser.', {exact: false}).waitFor();
  await page.locator('.animus-banner').getByRole('button', {name: 'Dismiss'}).click();
  await page.locator('.animus-banner').waitFor({state: 'detached'});
  await page.getByRole('link', {name: 'Example packs'}).click();
  await page.getByRole('heading', {name: 'Try it in the browser'}).waitFor();
  const workspace = /#\/workspace\/([^/?]+)/.exec(page.url())[1];
  await page.goto(`${url}/#/workspace/${workspace}/npc/${encodeURIComponent('no/such')}`);
  await page.getByRole('heading', {name: 'NPCs: no/such is not in this workspace'}).waitFor();
  await page.getByRole('link', {name: 'Open the workspace overview'}).click();
  await page.getByRole('heading', {name: 'Try it in the browser'}).waitFor();

  // The export fixture as a data folder.
  await page.goto(url);
  await page.getByRole('heading', {name: 'From a data folder'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(exportFolder);
  await page.getByRole('heading', {name: '1 document needs repair'}).waitFor();
  const side = await sidebar(page);
  for (const text of ['Greeter', 'Baker', 'shop:bakery', 'shop:counter', 'greeter:greeter', 'shop:keeper']) {
    await side.getByText(text, {exact: true}).first().waitFor();
  }
  assert.equal(await side.locator('.animus-badge.is-error').count(), 1);
  // Arrow keys move between the documents in the list.
  await side.locator('.animus-tree-item').first().focus();
  await page.keyboard.press('ArrowDown');
  assert.match(await page.evaluate(() => document.activeElement.textContent), /Greeter/);
  await page.keyboard.press('Escape');
  await page.getByRole('link', {name: 'NPCs: examples/broken'}).click();
  await page.locator('.animus-diagnostics').getByText('$.uuid').waitFor();
  // The document title is the page's h1, and its path wraps.
  await page.getByRole('heading', {level: 1, name: 'Broken'}).waitFor();
  // View tabs: one tab stop, arrows move and select.
  const tabs = page.getByRole('tablist', {name: 'View'});
  assert.equal(await tabs.locator('[tabindex="0"]').count(), 1);
  await page.getByRole('tab', {name: 'Visual'}).focus();
  await page.keyboard.press('ArrowRight');
  await page.waitForFunction(() => location.hash.endsWith('view=preview'));
  await page.keyboard.press('ArrowRight');
  await page.waitForFunction(() => location.hash.endsWith('view=code'));
  await page.locator('[role="tab"][aria-selected="true"]', {hasText: 'Code'}).waitFor();
  assert.equal(await page.getByRole('tabpanel').getAttribute('aria-labelledby'), await page.getByRole('tab', {name: 'Code'}).getAttribute('id'));
  await page.locator('.animus-code textarea').waitFor();
  // A broken draft says so in the header instead of claiming the document is valid.
  await page.locator('.animus-code textarea').fill('{"broken": ');
  await page.getByText('The draft in Code has a JSON error, so it is not applied.', {exact: false}).waitFor();
  await page.getByRole('button', {name: 'Discard'}).click();
  await page.getByRole('tab', {name: 'Split'}).click();
  // An NPC's Split view puts the 3D preview beside the code.
  await page.locator('.animus-views.is-split .animus-npc-preview').waitFor();
  await page.locator('.animus-views.is-split .animus-code').waitFor();
  await openFromSidebar(page, 'shop:counter');
  await page.getByRole('tab', {name: 'Preview'}).click();
  await page.locator('.animus-chest-slot').first().waitFor();
  assert.equal(await page.locator('.animus-chest-slot').count(), 27);
  await noSideways(page, `${label} document`);
  await page.reload();
  await page.getByRole('heading', {name: 'Bakery counter'}).or(page.locator('.animus-chest')).first().waitFor();
  assert.deepEqual(errors, []);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    await run(browser, {width: 1440, height: 1000}, 'desktop');
    await run(browser, {width: 390, height: 844}, 'phone');
    console.log('home_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
