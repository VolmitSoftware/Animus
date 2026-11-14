// The start page and a workspace's Home, at desktop and phone widths: a
// first visit's three ways in (pairing first, the only primary), the skip
// link, Blank workspace and Open examples, then the returning visit's
// Continue list (each workspace once, Open, Rename with Enter and Escape,
// Duplicate, Delete with Escape and confirm, Restore) with the ways in folded
// into one row. Routes to a missing workspace or document name the cause and
// a way back. ../schema/fixtures/export opened as a data folder shows Home
// with the job cards and the document that needs attention, and
// ../schema/fixtures/upgrades/before (schemaVersion 1) lists the upgraded
// files in Advanced. The first steps hide for good.
//
//   python3 tool/serve_site.py --port <port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/home_browser.cjs
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {setMode, startAction} = require('./support/shell.cjs');

const exportFolder = path.resolve(__dirname, '../../schema/fixtures/export');
const upgradesFolder = path.resolve(__dirname, '../../schema/fixtures/upgrades/before');
const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');

async function noSideways(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 0, `${where} scrolls sideways by ${overflow}px`);
}

const focusedId = page => page.evaluate(() => document.activeElement && document.activeElement.id);
const row = (page, name) => page.locator('.ui-continue-row', {has: page.locator('.ui-continue-name', {hasText: new RegExp(`^${name}$`)})});

/** Opens the "..." menu of a Continue row and picks [entry]. */
async function rowMenu(page, name, entry) {
  await row(page, name).getByRole('button', {name: 'More actions'}).click();
  await page.getByRole('menuitem', {name: entry, exact: true}).click();
}

async function openFolder(page, folder) {
  await page.goto(url);
  await page.locator('h1').waitFor();
  const chooser = page.waitForEvent('filechooser');
  const button = page.getByRole('button', {name: 'Open folder', exact: true});
  await button.click();
  await (await chooser).setFiles(folder);
  await page.waitForFunction(() => /#\/workspace\/[^/]+$/.test(location.hash));
}

async function run(browser, viewport, label) {
  const context = await browser.newContext({viewport});
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(url);

  // A first visit: three ways in, pairing first and the only primary.
  await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
  // The skip link is the first stop and moves focus to the page heading.
  await page.keyboard.press('Tab');
  assert.equal(await page.evaluate(() => document.activeElement.textContent), 'Skip to content');
  await page.keyboard.press('Enter');
  assert.equal(await focusedId(page), 'animus-main-heading');
  assert.deepEqual((await page.locator('.ui-start-card h2').allInnerTexts()).map(text => text.replace(/[\ue000-\uf8ff]/g, '').trim()), ['Pair a server', 'Try the examples', 'Open my files']);
  assert.equal(await page.locator('.ui-start-card').first().getAttribute('data-testid'), 'start-pair');
  assert.equal(await page.locator(".ui-start .arcane-button[data-variant='primary']:visible").count(), 1, 'one primary action on the start page');
  await page.getByText('Run /animus editor pair in game, then open the link or paste it here.', {exact: true}).waitFor();
  // Simple starts from a server, the examples or files; Import and a blank workspace are Advanced.
  assert.equal(await page.getByRole('button', {name: 'Import', exact: true}).count(), 0, 'no Import in Simple');
  assert.equal(await page.getByRole('button', {name: 'Blank workspace', exact: true}).count(), 0, 'no blank workspace in Simple');
  assert.equal(await page.getByRole('heading', {name: 'Continue'}).count(), 0, 'nothing to continue yet');
  // A link that is not a pairing link says so beside the field.
  await page.getByLabel('Pairing link').fill('hello');
  await page.getByTestId('start-pair-link-submit').click();
  await page.getByText('This is not a pairing link.', {exact: true}).waitFor();
  await noSideways(page, `${label} first visit`);
  await page.evaluate(() => window.localStorage.setItem('animus.mode', 'advanced'));
  await page.reload();
  await page.getByRole('button', {name: 'Import', exact: true}).waitFor();
  await page.getByRole('button', {name: 'Blank workspace', exact: true}).waitFor();

  // A blank workspace from its dialog, then the examples.
  await page.getByRole('button', {name: 'Blank workspace', exact: true}).click();
  const blank = page.getByRole('dialog', {name: 'Blank workspace'});
  await blank.locator('#start-workspace-name').fill('First');
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => /#\/workspace\/[^/]+$/.test(location.hash));
  await page.getByText('No documents yet.', {exact: true}).waitFor();
  // Back to Simple, where an owner starts.
  await page.evaluate(() => window.localStorage.setItem('animus.mode', 'simple'));
  await page.goto(url);
  await page.getByRole('heading', {name: 'Continue'}).waitFor();
  // Returning, the start row keeps Open examples beside its heading (in its
  await startAction(page, 'Open examples');
  await page.waitForFunction(() => /#\/workspace\/[^/]+$/.test(location.hash));
  await page.getByRole('heading', {level: 1, name: 'Example packs'}).waitFor();
  await page.locator('[data-testid="home-count"]').first().waitFor();
  const firstSteps = page.getByTestId('first-steps');
  await firstSteps.waitFor();
  assert.deepEqual(await firstSteps.locator('[data-done]').evaluateAll(items => items.map(item => item.getAttribute('data-done'))), ['false', 'false']);
  await noSideways(page, `${label} workspace home`);

  // Returning: Continue first, each workspace once, the ways in as one row.
  await page.goto(url);
  await page.getByRole('heading', {name: 'Continue'}).waitFor();
  assert.deepEqual(await page.locator('.ui-continue-name').allInnerTexts(), ['Example packs', 'First'], 'the last opened first');
  assert.equal(await page.locator('.ui-start-card').count(), 0, 'the cards fold away');
  await page.getByRole('button', {name: 'Open First'}).click();
  await page.waitForFunction(() => /#\/workspace\/[^/]+$/.test(location.hash));
  await page.getByRole('heading', {level: 1, name: 'First'}).waitFor();
  await page.goto(url);

  // Rename from the row's menu: Escape keeps the name, Enter saves it.
  await rowMenu(page, 'First', 'Rename');
  const rename = page.getByRole('dialog', {name: 'Rename workspace'});
  await page.waitForFunction(() => document.activeElement && document.activeElement.id === 'continue-rename-name');
  await page.keyboard.press('Escape');
  await rename.waitFor({state: 'hidden'});
  await rowMenu(page, 'First', 'Rename');
  await page.waitForFunction(() => document.activeElement && document.activeElement.id === 'continue-rename-name');
  await page.locator('#continue-rename-name').fill('Renamed');
  await page.keyboard.press('Enter');
  await row(page, 'Renamed').waitFor();

  // Duplicate; Delete asks first, Escape keeps it, Restore lasts until the tab closes.
  await rowMenu(page, 'Renamed', 'Duplicate');
  await row(page, 'Renamed copy').waitFor();
  await rowMenu(page, 'Renamed copy', 'Delete workspace');
  const confirm = page.getByRole('alertdialog');
  await confirm.waitFor();
  await page.waitForFunction(() => document.activeElement && document.activeElement.closest('[role="alertdialog"]'));
  await page.keyboard.press('Escape');
  await confirm.waitFor({state: 'hidden'});
  await row(page, 'Renamed copy').waitFor();
  await rowMenu(page, 'Renamed copy', 'Delete workspace');
  await confirm.getByRole('button', {name: 'Delete workspace'}).click();
  await page.getByText('Deleted Renamed copy. You can restore it until you close this tab.', {exact: true}).waitFor();
  assert.equal(await row(page, 'Renamed copy').count(), 0);
  await page.getByRole('button', {name: 'Restore', exact: true}).click();
  await row(page, 'Renamed copy').waitFor();
  await noSideways(page, `${label} continue list`);

  // Routes to things that are not here name the cause and a way back.
  await page.goto(`${url}/#/workspace/nope`);
  await page.getByText('There is no workspace "nope" in this browser.', {exact: false}).waitFor();
  await page.goto(url);
  await page.getByRole('button', {name: 'Open Example packs'}).click();
  await page.waitForFunction(() => /#\/workspace\/[^/]+$/.test(location.hash));
  const workspace = /#\/workspace\/([^/?]+)/.exec(page.url())[1];
  await page.goto(`${url}/#/workspace/${workspace}/npc/${encodeURIComponent('no/such')}`);
  await page.getByText('NPC not in this workspace', {exact: true}).waitFor();
  await page.getByRole('button', {name: 'Home', exact: true}).click();
  await page.getByRole('heading', {level: 1, name: 'Example packs'}).waitFor();

  // The first steps hide for good.
  await page.getByRole('button', {name: 'Hide first steps'}).click();
  await page.getByTestId('first-steps').waitFor({state: 'detached'});
  await page.reload();
  await page.locator('[data-testid="home-count"]').first().waitFor();
  assert.equal(await page.getByTestId('first-steps').count(), 0, 'the first steps stay hidden');

  // The export fixture as a data folder: Home counts, and the broken NPC needs attention.
  await openFolder(page, exportFolder);
  await page.getByRole('heading', {name: 'Needs attention (1)'}).waitFor();
  const attention = page.locator('.ui-attention-row');
  assert.equal(await attention.count(), 1);
  assert.match(await attention.innerText(), /Broken/);
  const cards = await page.locator('.ui-home-card').evaluateAll(found => found.map(card => [card.getAttribute('data-job'), card.querySelector('[data-testid="home-count"]').textContent]));
  assert.deepEqual(Object.fromEntries(cards).npcs, '3', `${label}: NPC count on Home`);
  await attention.click();
  await page.waitForFunction(() => decodeURIComponent(location.hash).includes('/npc/examples/broken'));
  await page.getByRole('heading', {level: 1, name: 'Broken'}).waitFor();
  await noSideways(page, `${label} document`);

  // Files from an older Animus are upgraded as they open; Advanced lists them.
  await openFolder(page, upgradesFolder);
  await page.locator('[data-testid="home-count"]').first().waitFor();
  assert.equal(await page.locator('[data-testid="upgraded-files"]').count(), 0, 'Simple leaves the file list out');
  await setMode(page, 'advanced');
  const upgraded = page.locator('[data-testid="upgraded-files"]');
  await upgraded.getByText('3 files upgraded to schemaVersion 2').waitFor();
  await upgraded.locator('summary').click();
  for (const file of ['groups/guards.json', 'npcs/town/citizens-10.json', 'npcs/town/citizens-9.json']) await upgraded.getByText(file, {exact: true}).waitFor();
  await noSideways(page, `${label} upgraded folder`);
  await setMode(page, 'simple');
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
