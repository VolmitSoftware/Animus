// The shop editor against tool/fake_server.dart at 1440, 1024, 768 and
// 390 px in both themes: pair, open two NPCs, make a shop with "+ New shop"
// from the greeter's shop module (back on the NPC with it picked), open it
// with Edit shop, add a trade, a buy and a command offer (two by keyboard,
// one by mouse), fill them in, reorder by Alt+Up and by drag, check both
// windows follow and pick offers, delete and undo, attach the shop to the
// second NPC, apply all three documents, read them back from the fake
// server and reopen the shop. No console errors, no native selects and no
// sideways scrolling.
//
//   python3 tool/serve_site.py --port <web port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<web port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<web port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/shop_editor_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openFromSidebar} = require('./support/shell.cjs');
const {chooseAndWait} = require('./support/select.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;
const greeter = 'examples/greeter';
const baker = 'examples/shopkeeper';

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

async function check(page, name) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  if (overflow > 1) {
    const wide = await page.evaluate(() => [...document.querySelectorAll('body *')]
      .filter(el => el.getBoundingClientRect().right > document.documentElement.clientWidth + 1)
      .slice(0, 6)
      .map(el => `${el.tagName.toLowerCase()}.${String(el.className).trim().split(/\s+/).slice(0, 2).join('.')}`));
    assert.fail(`${name}: ${overflow}px of sideways scrolling from ${wide.join(', ')}`);
  }
  assert.equal(await page.locator('.animus-shop-editor select, .animus-npc-module select').count(), 0, `${name}: a native select`);
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: true});
}

async function notice(page, text) {
  await page.locator('.animus-live-log li', {hasText: text}).first().waitFor({state: 'attached', timeout: 15000});
}

const settle = page => page.waitForTimeout(250);

/** Adds an offer of [label] from the keyboard: focus, Enter, arrows, Enter. */
async function addByKeyboard(page, label) {
  await page.locator('#animus-shop-add').focus();
  await page.keyboard.press('Enter');
  await page.locator('#animus-shop-add-menu [role="menuitem"]').first().waitFor();
  for (let i = 0; i < 6; i++) {
    if (await page.evaluate(text => document.activeElement && document.activeElement.textContent.trim().startsWith(text), label)) break;
    await page.keyboard.press('ArrowDown');
  }
  await page.keyboard.press('Enter');
}

/** The board rows' offer ids, top to bottom. */
const rowIds = page => page.locator('.animus-shop-offer').evaluateAll(rows => rows.map(row => row.getAttribute('data-id')));

/** The trading screen's offer ids. */
async function tradingIds(page) {
  const preview = page.locator('.animus-shop-preview');
  await preview.getByRole('tab', {name: /^Trading screen/}).click();
  return preview.locator('.animus-merchant-offer').evaluateAll(rows => rows.map(row => row.getAttribute('data-offer')));
}

async function run(browser, viewport, label, theme) {
  await control('reset', {});
  await control('restore', {});
  const {code} = await control('pair?paths=both', {});
  const context = await browser.newContext({viewport});
  await context.addInitScript(mode => window.localStorage.setItem('animus.theme', mode), theme);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  page.on('pageerror', error => errors.push(String(error)));

  await page.goto(`${site}/#/pair/${code}`);
  await page.locator('[data-testid="pair-fingerprint"]', {hasText: 'sha256:'}).waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await page.locator('.animus-live .animus-pill[data-phase="online"]').waitFor({timeout: 20000});
  await page.getByText(/documents on the server/).waitFor();
  await page.getByRole('checkbox', {name: greeter, exact: true}).check();
  await page.getByRole('checkbox', {name: baker, exact: true}).check();
  await page.getByRole('button', {name: 'Open 2'}).click();
  await notice(page, 'Opened 2 documents');

  // "+ New shop" from the greeter's shop module, back with the shop picked.
  await openFromSidebar(page, 'Greeter');
  const modules = page.locator('details[data-section="modules"]');
  await modules.waitFor();
  if (!(await modules.evaluate(el => el.open))) await modules.locator(':scope > summary').click();
  await modules.getByRole('button', {name: '+ New shop'}).click();
  await page.waitForFunction(() => location.hash.includes('/new/shop'));
  await page.getByText('Create takes you back and picks it there.').waitFor();
  await page.getByRole('radio', {name: /^Blank/}).click();
  await page.getByLabel('Title', {exact: true}).fill('&3Harbor');
  const shop = await page.getByLabel('Id', {exact: true}).inputValue();
  await page.getByRole('button', {name: /^Create /}).click();
  await page.waitForFunction(() => location.hash.includes('/npc/'));
  await page.waitForFunction(made => document.getElementById('animus-npc-shop')?.getAttribute('data-value') === made, shop);
  await check(page, `${label}-npc-shop-module`);
  await modules.getByRole('button', {name: `Edit shop ${shop}`}).click();
  await page.waitForFunction(() => location.hash.includes('/shop/'));
  const board = page.locator('.animus-shop-board');
  await board.getByText('No offers yet.').waitFor();

  // A trade by keyboard: 2 emerald + 1 book for 1 enchanted book.
  await addByKeyboard(page, 'Trade');
  const trade = board.locator('.animus-shop-offer.is-open[data-id="trade"]');
  await trade.waitFor();
  await trade.getByRole('button', {name: 'More: Takes 1 amount'}).click();
  await settle(page);
  await trade.getByRole('button', {name: 'Add an item to Takes'}).click();
  await settle(page);
  await trade.getByLabel('Takes 2 item', {exact: true}).fill('minecraft:book');
  await settle(page);
  await trade.getByLabel('Gives 1 item', {exact: true}).fill('minecraft:enchanted_book');
  await settle(page);
  await board.locator('.animus-shop-offer', {hasText: '2 emerald + 1 book → 1 enchanted book'}).waitFor();

  // A buy offer by mouse, with a price.
  await page.locator('#animus-shop-add').click();
  await page.getByRole('menuitem', {name: 'Buy (player pays money)'}).click();
  const buy = board.locator('.animus-shop-offer.is-open[data-id="buy"]');
  await buy.getByLabel('Price', {exact: true}).fill('25');
  await settle(page);
  await buy.getByLabel('Gives item', {exact: true}).fill('minecraft:cake');
  await settle(page);
  await board.locator('.animus-shop-offer', {hasText: '25 money → 1 cake'}).waitFor();

  // A command offer by keyboard.
  await addByKeyboard(page, 'Command');
  const command = board.locator('.animus-shop-offer.is-open[data-id="command"]');
  await command.getByLabel('Command 1', {exact: true}).fill('give {player} diamond 1');
  await settle(page);
  await board.locator('.animus-shop-offer[data-id="command"]').waitFor();
  assert.deepEqual(await rowIds(page), ['buy', 'command', 'trade']);
  assert.deepEqual(await tradingIds(page), ['buy', 'command', 'trade']);
  await check(page, `${label}-three-offers`);

  // Reorder by keyboard: Alt+Up on the command row; focus follows it.
  await page.locator('[data-id="command"] .animus-shop-offer-main').focus();
  await page.keyboard.press('Alt+ArrowUp');
  await page.waitForFunction(() => document.querySelector('.animus-shop-offer')?.getAttribute('data-id') === 'command');
  await page.waitForFunction(() => document.activeElement && document.activeElement.closest('[data-id="command"]'));
  assert.deepEqual(await rowIds(page), ['command', 'buy', 'trade']);
  // Arrow keys move between rows.
  await page.keyboard.press('ArrowDown');
  await page.waitForFunction(() => document.activeElement && document.activeElement.closest('[data-id="buy"]'));
  assert.deepEqual(await tradingIds(page), ['command', 'buy', 'trade'], 'the trading screen follows');

  // Reorder by drag (pointer only, on wide screens): the buy row back on top.
  if (viewport.width > 640) {
    // Close the open row so the two rows sit together, then drag.
    const open = board.locator('.animus-shop-offer-main[aria-expanded="true"]');
    if (await open.count()) await open.click();
    await board.locator('[data-id="trade"]').scrollIntoViewIfNeeded();
    await page.locator('[data-id="buy"] .animus-shop-offer-row').dragTo(page.locator('[data-id="command"] .animus-shop-offer-row'));
    await page.waitForFunction(() => document.querySelector('.animus-shop-offer')?.getAttribute('data-id') === 'buy');
  } else {
    await page.getByRole('button', {name: /^Move earlier: 25 money/}).click();
    await page.waitForFunction(() => document.querySelector('.animus-shop-offer')?.getAttribute('data-id') === 'buy');
  }
  await settle(page);
  assert.deepEqual(await rowIds(page), ['buy', 'command', 'trade']);

  // The chest window shows the buy grid and picks an offer on the board.
  const preview = page.locator('.animus-shop-preview');
  await preview.getByRole('tab', {name: /^Chest window/}).click();
  await preview.locator('[data-slot="1"]').click();
  await board.locator('[data-id="command"] .animus-shop-offer-main[aria-expanded="true"]').waitFor();
  // The trading screen picks too, and crosses out offers out of limits.
  await preview.getByRole('tab', {name: /^Trading screen/}).click();
  await preview.locator('.animus-merchant-offer[data-offer="trade"]').click();
  await board.locator('[data-id="trade"] .animus-shop-offer-main[aria-expanded="true"]').waitFor();
  await board.locator('[data-id="trade"] details.animus-gui-section > summary', {hasText: 'Rules'}).click();
  await board.locator('[data-id="trade"]').getByLabel('Per player limit', {exact: true}).fill('3');
  await settle(page);
  await preview.getByRole('checkbox', {name: 'Limits used up'}).check();
  await preview.locator('.animus-merchant-offer.is-crossed[data-offer="trade"]').waitFor();
  assert.equal(await preview.locator('.animus-merchant-offer.is-crossed').count(), 1);
  await check(page, `${label}-previews`);

  // Delete and undo.
  await board.getByRole('button', {name: /^Delete: 25 money/}).click();
  await board.getByText('Deleted 1 offer.').waitFor();
  await page.waitForFunction(() => document.querySelectorAll('.animus-shop-offer').length === 2);
  assert.deepEqual(await rowIds(page), ['command', 'trade']);
  await board.getByRole('button', {name: 'Undo', exact: true}).click();
  await page.waitForFunction(() => document.querySelectorAll('.animus-shop-offer').length === 3);
  assert.deepEqual(await rowIds(page), ['buy', 'command', 'trade']);

  // Attach to the baker from the NPCs tab: one undoable change.
  const settings = page.locator('.animus-shop-settings');
  await settings.getByRole('tab', {name: /^NPCs/}).click();
  await settings.locator('[data-user="npc:examples/greeter"]').waitFor();
  await chooseAndWait(settings.getByLabel('NPC to attach this shop to'), `npc:${baker}`);
  await settings.getByText('Replaces shop:bakery.').waitFor();
  await settings.getByRole('button', {name: 'Attach', exact: true}).click();
  await settings.getByRole('tab', {name: 'NPCs (2)'}).waitFor();
  await check(page, `${label}-attached`);

  // Apply the shop and both NPCs; the server has them.
  await page.getByRole('button', {name: 'Apply 3'}).click();
  await notice(page, 'Applied 3 documents');
  const stored = (await control(`document?kind=shop&id=${encodeURIComponent(shop)}`)).source;
  assert.equal(stored.title, '&3Harbor');
  assert.deepEqual(Object.values(stored.pages[0].buy).map(entry => entry.id), ['buy', 'command']);
  assert.equal(stored.pages[0].buy['0'].price, 25);
  assert.deepEqual(stored.pages[0].trade['0'].needs.map(need => [need.item.id, need.amount]), [['minecraft:emerald', 2], ['minecraft:book', 1]]);
  assert.equal(stored.pages[0].trade['0'].limit.perPlayer, 3);
  assert.equal(stored.pages[0].buy['1'].commands[0].command, 'give {player} diamond 1');
  for (const npc of [greeter, baker]) {
    const definition = (await control(`document?kind=npc&id=${encodeURIComponent(npc)}`)).source;
    assert.equal(definition.modules['animus:shop'].shop, shop, `${npc} opens the shop`);
  }

  // Reopen: a fresh load shows the same board.
  await page.reload();
  await page.locator('.animus-live .animus-pill[data-phase="online"]').waitFor({timeout: 20000});
  await openFromSidebar(page, shop);
  await page.locator('.animus-shop-offer').first().waitFor();
  assert.deepEqual(await rowIds(page), ['buy', 'command', 'trade']);
  await board.locator('.animus-shop-offer', {hasText: '2 emerald + 1 book → 1 enchanted book'}).waitFor();
  await check(page, `${label}-reopened`);
  assert.deepEqual(errors, [], label);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    for (const theme of ['dark', 'light']) {
      for (const width of [1440, 1024, 768, 390]) {
        await run(browser, {width, height: 900}, `shop-${width}-${theme}`, theme);
        console.log(`shop-${width}-${theme}: ok`);
      }
    }
    console.log('shop_editor_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
