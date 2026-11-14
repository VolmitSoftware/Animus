// The app frame against the built site and tools/fake_server.dart:
// - the save bar stays in the viewport after the page body scrolls a long
//   document, at 1600, 768 and 390 px, in a local and a server workspace;
// - its state after an edit, an apply, a missing permission (no Retry) and
//   a forced failure (Retry), and
//   Mod+S while typing in a field (saves, never the browser's Save page);
// - the Mode switch persists across a reload and shows or hides an
//   Advanced-only control (the Code view) without changing the document;
// - a list of 1500 NPCs draws under 60 rows, sorts "Courier 4, 9, 14, 19"
//   naturally and moves by keyboard;
// - the nav lists jobs, never the documents (only Recent and Changed);
// - every icon-only button of the frame has an accessible name;
// - Simple up to date offers no Review; a menu closed with Escape leaves no
//   tooltip open on its button; the phone drawer is a narrow panel with no
//   second box in it; the server switcher never scrolls sideways.
// The fake server runs with its default data.
//
//   python3 tool/serve_site.py --port <site>
//   dart run tool/fake_server.dart --port=<fake> --origin=http://127.0.0.1:<site>
//   ANIMUS_WEB_URL=http://127.0.0.1:<site> ANIMUS_FAKE_URL=http://127.0.0.1:<fake> node test/frame_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {isCompact, openFromSidebar, openSearch, searchBox, setMode, sidebar, closeDrawer, saveState, saveAction, startAction} = require('./support/shell.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;
const widths = [1600, 768, 390];

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined && route === 'state' ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

async function shot(page, name) {
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `frame-${name}.png`)});
}

function watch(page, errors, allow = () => false) {
  page.on('pageerror', error => errors.push(String(error)));
  page.on('console', message => {
    if (message.type() === 'error' && !allow(message.text())) errors.push(message.text());
  });
}

/** Scrolls the page body to its end and checks the save bar is on screen. */
async function saveBarInView(page, where) {
  const scrolled = await page.evaluate(() => {
    const body = document.querySelector('.ui-page');
    const before = body.scrollTop;
    body.scrollTop = body.scrollHeight;
    return {before, after: body.scrollTop, room: body.scrollHeight - body.clientHeight};
  });
  assert.ok(scrolled.room > 100, `${where}: the document is long enough to scroll (${scrolled.room}px)`);
  assert.ok(scrolled.after > scrolled.before, `${where}: the page body scrolled`);
  assert.equal(await page.evaluate(() => window.scrollY), 0, `${where}: the window itself never scrolls`);
  const page_ = await page.evaluate(() => ({document: document.documentElement.scrollHeight, window: window.innerHeight}));
  assert.ok(page_.document <= page_.window + 1, `${where}: the document is ${page_.document}px for a ${page_.window}px window`);
  const box = await page.locator('.ui-save-bar').boundingBox();
  const viewport = page.viewportSize();
  assert.ok(box && box.y >= 0 && box.y + box.height <= viewport.height + 0.5 && box.height >= 40, `${where}: save bar in view ${JSON.stringify(box)}`);
  await page.evaluate(() => { document.querySelector('.ui-page').scrollTop = 0; });
}

/** Every icon-only button in the frame has a name. */
async function namedIconButtons(page, where) {
  const nameless = await page.evaluate(() => [...document.querySelectorAll('.ui-topbar button, .ui-nav button, .ui-save-bar button, .ui-page-header button, .ui-stage-head button, .ui-library-bar button')]
    .filter(button => button.offsetParent !== null && !button.textContent.trim() && !(button.getAttribute('aria-label') || '').trim() && !button.getAttribute('aria-labelledby'))
    .map(button => button.outerHTML.slice(0, 120)));
  assert.deepEqual(nameless, [], `${where}: icon-only buttons without a name`);
}

/** The nav's document links: only Recent and Changed hold any. */
async function navListsJobs(page, where) {
  const nav = await sidebar(page);
  const links = await nav.locator('a[href]').evaluateAll(anchors => anchors.map(anchor => ({
    href: anchor.getAttribute('href'),
    group: anchor.closest('.arcane-sidebar-group')?.querySelector('.arcane-sidebar-group-label')?.textContent.trim() || 'Jobs',
  })));
  const documents = links.filter(link => /#\/workspace\/[^/]+\/(?!list\/|npcs$|recordings$|new|sync$)[^/]+\/[^?]+/.test(link.href));
  assert.ok(documents.every(link => link.group === 'Recent' || link.group === 'Changed'), `${where}: a document listed outside Recent and Changed: ${JSON.stringify(documents)}`);
  assert.ok(documents.filter(link => link.group === 'Recent').length <= 6, `${where}: Recent holds at most six`);
  const changed = new Set(documents.filter(link => link.group === 'Changed').map(link => link.href));
  assert.deepEqual(documents.filter(link => link.group === 'Recent' && changed.has(link.href)), [], `${where}: a document listed under both Recent and Changed`);
  assert.ok(links.filter(link => link.group === 'Jobs').length >= 6, `${where}: the jobs are listed`);
  await closeDrawer(page);
}

/** Captures whether the next Mod+S keydown was cancelled. */
/** The frame's quieter rules, on a paired server up to date in Simple. */
async function frameManners(page, width) {
  const where = `${width} server`;
  assert.equal(await saveAction(page, 'review').count(), 0, `${where}: up to date, Simple offers no Review`);
  // A menu closed with Escape gives focus back without opening a tooltip.
  const more = page.locator('#ui-overflow-button');
  await more.click();
  await page.getByRole('menu').first().waitFor();
  // A phone has no keyboard, so its menu offers no shortcut sheet.
  const keys = await page.getByRole('menuitem', {name: /^Keyboard shortcuts/}).count();
  assert.equal(keys, width <= 760 ? 0 : 1, `${where}: the overflow menu offers Keyboard shortcuts ${keys} times`);
  await page.keyboard.press('Escape');
  await page.waitForTimeout(600);
  assert.equal(await page.locator('[role="tooltip"][data-arcane-state="open"]').count(), 0, `${where}: a tooltip opened when the menu closed`);
  // The switcher's list fits its popover.
  await page.locator('#ui-context-button').click();
  const all = page.getByRole('button', {name: 'All workspaces', exact: true});
  await all.waitFor();
  const sideways = await all.evaluate(button => {
    const surface = button.closest('[data-arcane-surface]') || button.parentElement;
    // A box that scrolls sideways: a scroll container wider inside than out.
    return [surface, ...surface.querySelectorAll('*')].some(child => child.scrollWidth > child.clientWidth + 1 && /auto|scroll/.test(getComputedStyle(child).overflowX));
  });
  assert.equal(sideways, false, `${where}: the server switcher scrolls sideways`);
  await page.keyboard.press('Escape');
  if (width <= 760) {
    await page.locator('#ui-nav-button').click();
    const drawer = page.locator('#ui-nav-drawer [role="dialog"]').first();
    await drawer.waitFor();
    const box = await drawer.boundingBox();
    assert.ok(box.width <= 300, `${where}: the nav drawer is ${box.width}px wide, not a narrow panel`);
    await page.keyboard.press('Escape');
  }
}

/**
 * Simple keeps to its own jobs: Search leaves the example chest GUI out, the
 * chest GUI list's address offers the switch, and a chest GUI opened in
 * Advanced is not under Simple's Recent or in its Search.
 */
async function simpleKeepsToItsJobs(page, where) {
  const items = page.locator('[data-arcane-command="ui-search"] [data-arcane-command-item]:not([hidden])');
  await openSearch(page);
  await searchBox(page).fill('Bakery counter');
  await page.waitForTimeout(400);
  assert.equal(await items.filter({hasText: 'Bakery counter'}).count(), 0, `${where}: Simple Search lists a chest GUI`);
  await page.keyboard.press('Escape');
  const workspace = /#\/workspace\/([^/?]+)/.exec(page.url())[1];
  const home = page.url();
  await page.evaluate(hash => { location.hash = hash; }, `#/workspace/${workspace}/list/inventory`);
  const toAdvanced = page.getByRole('button', {name: 'Switch to Advanced', exact: true});
  await toAdvanced.waitFor();
  assert.equal(await page.locator('.ui-library-row').count(), 0, `${where}: Simple lists chest GUIs`);
  await toAdvanced.click();
  await page.locator('.ui-library-row', {hasText: 'Bakery counter'}).first().click();
  await page.locator('.ui-title', {hasText: 'Bakery counter'}).waitFor();
  await setMode(page, 'simple');
  const recent = await (await sidebar(page)).getByRole('link', {name: /Bakery counter/}).count();
  await closeDrawer(page);
  assert.equal(recent, 0, `${where}: Simple lists a chest GUI under Recent`);
  await openSearch(page);
  await searchBox(page).fill('Bakery');
  await page.waitForTimeout(400);
  assert.equal(await items.filter({hasText: 'Bakery counter'}).count(), 0, `${where}: Simple Search lists a recent chest GUI`);
  await page.keyboard.press('Escape');
  await page.goto(home);
}

async function pressSave(page) {
  await page.evaluate(() => {
    window.__saveCancelled = null;
    const listen = event => {
      if (event.key.toLowerCase() !== 's') return;
      window.removeEventListener('keydown', listen);
      setTimeout(() => { window.__saveCancelled = event.defaultPrevented; });
    };
    window.addEventListener('keydown', listen);
  });
  await page.keyboard.press('Control+s');
  await page.waitForFunction(() => window.__saveCancelled !== null);
  return page.evaluate(() => window.__saveCancelled);
}

/** 1500 NPCs named Courier 4, 9, 14, ... in a data folder. */
function writeCouriers() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'animus-couriers-'));
  const folder = path.join(root, 'Animus', 'npcs', 'town');
  fs.mkdirSync(folder, {recursive: true});
  for (let index = 0; index < 1500; index++) {
    const number = index * 5 + 4;
    fs.writeFileSync(path.join(folder, `courier-${number}.json`), JSON.stringify({
      schemaVersion: 2,
      revision: 1,
      uuid: `00000000-0000-4000-8000-${String(number).padStart(12, '0')}`,
      id: `courier-${number}`,
      group: 'town',
      name: `Courier ${number}`,
      type: 'minecraft:villager',
      spawned: index % 2 === 0,
      position: {world: 'world', x: index, y: 64, z: 0, yaw: 0, pitch: 0},
    }, null, 2));
  }
  return root;
}

async function local(browser, width) {
  const context = await browser.newContext({viewport: {width, height: width <= 390 ? 844 : 900}});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  watch(page, errors);
  await page.goto(site);
  await page.evaluate(() => window.localStorage.removeItem('animus.mode'));
  await page.reload();
  {
    await startAction(page, 'Open examples');
  }
  await page.waitForFunction(() => /#\/workspace\//.test(location.hash));
  await saveState(page).filter({hasText: 'Saved in this browser'}).waitFor();
  await navListsJobs(page, `${width} local home`);
  await namedIconButtons(page, `${width} local home`);
  await simpleKeepsToItsJobs(page, `${width} local`);

  // A long document (the quest giver's behavior): the bar stays in view.
  await openFromSidebar(page, 'quest:giver');
  await page.locator('.ui-title').filter({hasText: /^Giver$/}).waitFor();
  await saveBarInView(page, `${width} local document`);
  await namedIconButtons(page, `${width} local document`);

  // An edit is kept, Mod+S while typing says Saved and never reaches the
  // browser.
  await openFromSidebar(page, 'Baker');
  await page.locator('.ui-title').filter({hasText: 'Baker'}).waitFor();
  const name = page.locator('#animus-npc-name');
  await name.fill('Baker Two');
  await saveState(page).filter({hasText: /Saved in this browser|Saving/}).waitFor();
  await name.focus();
  assert.equal(await pressSave(page), true, `${width}: Mod+S in a field is cancelled`);
  await saveState(page).filter({hasText: /^Saved$/}).waitFor();
  await saveState(page).filter({hasText: 'Saved in this browser'}).waitFor({timeout: 5000});
  await navListsJobs(page, `${width} local document`);
  await shot(page, `${width}-local-document`);

  // Mode: Code is Advanced only, the choice survives a reload, and switching
  // changes nothing in the document.
  if (width !== 390) {
    assert.equal(await page.getByRole('button', {name: 'Code', exact: true}).count(), 0, 'Simple has no Code view');
    await setMode(page, 'advanced');
    await page.getByRole('button', {name: 'Code', exact: true}).click();
    const before = await page.locator('.animus-code textarea').first().inputValue();
    assert.match(before, /Baker Two/);
    await page.reload();
    await page.locator('.animus-code textarea').first().waitFor();
    assert.equal(await page.evaluate(() => window.localStorage.getItem('animus.mode')), 'advanced');
    assert.equal(await (await sidebar(page)).getByRole('link', {name: /GUIs/}).count(), 1, 'Advanced lists GUIs');
    await setMode(page, 'simple');
    assert.equal(await page.getByRole('button', {name: 'Code', exact: true}).count(), 0, 'Simple hides Code again');
    assert.equal(await (await sidebar(page)).getByRole('link', {name: /GUIs/}).count(), 0, 'Simple hides GUIs');
    await closeDrawer(page);
    await setMode(page, 'advanced');
    await page.getByRole('button', {name: 'Code', exact: true}).click();
    assert.equal(await page.locator('.animus-code textarea').first().inputValue(), before, 'the document is unchanged by the mode');
    await setMode(page, 'simple');
  }
  assert.deepEqual(errors, [], `${width} local console`);
  await context.close();
}

async function library(browser) {
  const folder = writeCouriers();
  try {
    const context = await browser.newContext({viewport: {width: 1600, height: 900}});
    const page = await context.newPage();
    page.setDefaultTimeout(60000);
    const errors = [];
    watch(page, errors);
    await page.goto(site);
    const chooser = page.waitForEvent('filechooser');
    await page.getByRole('button', {name: 'Open folder'}).first().click();
    await (await chooser).setFiles(path.join(folder, 'Animus'));
    await page.waitForFunction(() => /#\/workspace\//.test(location.hash));
    const workspace = /#\/workspace\/([^/?]+)/.exec(page.url())[1];
    await navListsJobs(page, 'library home');
    const started = Date.now();
    await page.evaluate(hash => { location.hash = hash; }, `#/workspace/${workspace}/list/npc`);
    await page.locator('.ui-library-row').first().waitFor();
    const opened = Date.now() - started;
    const titles = await page.locator('.ui-library-title').evaluateAll(spans => spans.slice(0, 4).map(span => span.textContent));
    assert.deepEqual(titles, ['Courier 4', 'Courier 9', 'Courier 14', 'Courier 19'], 'natural order');
    assert.ok(await page.locator('.ui-library-row').count() < 60, `rows drawn: ${await page.locator('.ui-library-row').count()}`);
    await page.locator('.ui-library-count', {hasText: '1500 NPCs'}).waitFor();
    // Keyboard: End goes to the last row, which is drawn and active.
    await page.locator('.ui-library-rows').focus();
    await page.keyboard.press('End');
    await page.locator('.ui-library-row.is-active .ui-library-title', {hasText: 'Courier 7499'}).waitFor();
    assert.ok(await page.locator('.ui-library-row').count() < 60, 'still windowed at the end');
    await page.keyboard.press('Home');
    await page.locator('.ui-library-row.is-active .ui-library-title', {hasText: 'Courier 4'}).waitFor();
    // Search narrows; a keystroke stays quick at 1500 rows.
    const search = page.getByRole('searchbox', {name: /Search/}).or(page.getByRole('textbox', {name: /Search/})).first();
    const typed = Date.now();
    await search.fill('courier 749');
    await page.locator('.ui-library-count', {hasText: /of 1500 NPCs/}).waitFor();
    assert.ok(Date.now() - typed < 2000, `search took ${Date.now() - typed} ms`);
    await search.fill('');
    await page.keyboard.press('Tab');
    await page.locator('.ui-library-row').nth(1).click();
    await page.locator('.ui-title', {hasText: 'Courier 9'}).waitFor();
    await shot(page, 'library');
    assert.deepEqual(errors, [], 'library console');
    console.log(`frame_browser: 1500 rows listed in ${opened} ms`);
    await context.close();
  } finally {
    fs.rmSync(folder, {recursive: true, force: true});
  }
}

async function server(browser, width) {
  await control('reset', {});
  await control('restore', {});
  const {code} = await control('pair?paths=both', {});
  const context = await browser.newContext({viewport: {width, height: width <= 390 ? 844 : 900}});
  const page = await context.newPage();
  page.setDefaultTimeout(20000);
  const errors = [];
  let refusal = false;
  watch(page, errors, text => refusal && /status of (400|403)/.test(text));
  await page.goto(`${site}/#/pair/${code}`);
  await page.getByTestId('pair-submit').waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await saveState(page).filter({hasText: 'Up to date'}).waitFor();
  await frameManners(page, width);
  // Home lists the server; the NPC opens by its address. Advanced adds the
  // fields that make its Basics tab longer than a 900 px window.
  await page.locator('[data-testid="home-count"]').first().waitFor();
  await setMode(page, 'advanced');
  await page.evaluate(() => { location.hash = location.hash.replace(/^#\/server\/([^/]+).*$/, '#/workspace/server-$1/npc/examples%2Fgreeter'); });
  await page.waitForFunction(() => decodeURIComponent(location.hash).includes('/npc/examples/greeter'));
  await page.locator('.ui-title').waitFor();
  await saveBarInView(page, `${width} server document`);
  await namedIconButtons(page, `${width} server document`);

  const name = page.locator('#animus-npc-name');
  await name.fill('Greeter Prime');
  await saveState(page).filter({hasText: '1 change not applied'}).waitFor();
  await navListsJobs(page, `${width} server changed`);
  await saveAction(page, 'apply').click();
  await saveState(page).filter({hasText: 'Applied 1 change'}).waitFor();

  // A pairing that lacks a permission says which and how to get it, with
  // no Retry that cannot work.
  await name.fill('Greeter Refused');
  await saveState(page).filter({hasText: '1 change not applied'}).waitFor();
  refusal = true;
  await control('fail?code=scope-missing&times=1&route=/v1/changes&scope=apply:commands', {});
  await name.focus();
  assert.equal(await pressSave(page), true, `${width}: Mod+S in a field is cancelled`);
  await saveState(page).filter({hasText: 'Apply failed: This pairing lacks the Run commands permission.'}).waitFor();
  assert.equal(await saveAction(page, 'retry').count(), 0, `${width}: no Retry for a missing permission`);
  await saveAction(page, 'review').waitFor();

  // A refused apply says why and offers Retry; Mod+S from the field applies.
  await control('fail?code=invalid-request&times=1&route=/v1/changes', {});
  await name.focus();
  assert.equal(await pressSave(page), true, `${width}: Mod+S in a field is cancelled`);
  await saveState(page).filter({hasText: /^Apply failed: /}).waitFor();
  await saveAction(page, 'retry').waitFor();
  await shot(page, `${width}-server-failed`);
  refusal = false;
  await saveAction(page, 'retry').click();
  await saveState(page).filter({hasText: 'Applied 1 change'}).waitFor();
  const stored = await (await fetch(`${fake}/__fake/document?kind=npc&id=examples/greeter`)).json();
  assert.equal(stored.source.name, 'Greeter Refused', 'the retry applied the edit');
  await saveBarInView(page, `${width} server after apply`);
  assert.deepEqual(errors, [], `${width} server console`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    for (const width of widths) {
      await local(browser, width);
      console.log(`frame_browser: local ${width} ok`);
    }
    await library(browser);
    for (const width of widths) {
      await server(browser, width);
      console.log(`frame_browser: server ${width} ok`);
    }
    console.log('frame_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
