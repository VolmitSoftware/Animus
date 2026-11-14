// The shop page against tool/fake_server.dart at 1440, 1024, 768 and 390 px
// in both themes: pair, make a blank shop from New, add a buy offer (Add
// offer), a trade and a command offer from the "..." menu by keyboard, fill
// them in, read the rows as "gives for price", reorder from a row menu by
// keyboard and by drag, check both windows follow and pick offers, cross out
// an offer with a used-up limit, delete from a row menu and undo, attach the
// shop to two NPCs from Settings (replacing another shop asks first), apply
// through the save bar, read the documents back from the fake server and
// reopen the shop. No console errors, no native selects and no sideways
// scrolling.
// Then a real-shaped native shop of 18 offers opened by a villager: the
// trading screen shows a page of offers at a time (seven beside the form or
// on a phone, three in the band above it at 761-1099 px) with a pager in the
// preview header, the preview frame stays within 240 px in the band and 60%
// of the window otherwise, every offer row keeps its sentence at every
// width, Settings names the window field once ("Opens as" under "Window"),
// the "Opened by" link is foreground text, the preview menu checks what it
// shows, and Sold out dims the chest window's offers.
//
//   python3 tool/serve_site.py --port <web port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<web port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<web port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/shop_editor_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openFromSidebar, setMode, waitOnline, saveAction, saveState, topbarAction, pickOption, showPreview} = require('./support/shell.cjs');
const {chooseText} = require('./support/select.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;
const greeter = 'examples/greeter';
const baker = 'examples/shopkeeper';

/**
 * Records every painted frame of the shop's chest window for the next 40
 * frames: whether it is shown, its slot count and any slot still waiting
 * for its icon (a fallback name or an undecoded image).
 */
async function sampleChestFrames(page) {
  await page.evaluate(() => {
    const frames = [];
    window.__chestFrames = frames;
    const sample = () => {
      const screen = document.querySelector('[aria-label="Preview"] .animus-chest-screen');
      if (screen) {
        const shown = getComputedStyle(screen).visibility !== 'hidden';
        const waiting = screen.querySelectorAll('.animus-chest-missing').length +
          [...screen.querySelectorAll('.animus-chest-face img')].filter((img) => !img.complete || img.naturalWidth === 0).length;
        frames.push({shown, slots: screen.querySelectorAll('.animus-chest-slot').length, waiting});
      }
      if (frames.length < 40) requestAnimationFrame(sample);
    };
    requestAnimationFrame(sample);
  });
}

/** Every shown frame recorded by [sampleChestFrames] is the whole window. */
async function chestFramesWhole(page, label) {
  await page.waitForFunction(() => window.__chestFrames.length >= 40);
  const frames = await page.evaluate(() => window.__chestFrames);
  const partial = frames.filter((frame) => frame.shown && (frame.waiting > 0 || frame.slots === 0));
  assert.deepEqual(partial, [], `${label}: the chest window shows only whole frames, got ${JSON.stringify(frames)}`);
  assert.ok(frames.some((frame) => frame.shown), `${label}: the chest window shows`);
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

async function check(page, name) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  if (overflow > 1) {
    const wide = await page.evaluate(() => [...document.querySelectorAll('body *')]
      .filter(el => el.getBoundingClientRect().right > document.documentElement.clientWidth + 1)
      .slice(0, 6)
      .map(el => `${el.tagName.toLowerCase()}.${String(el.className).trim().split(/\s+/).slice(0, 2).join('.')}`));
    assert.fail(`${name}: ${overflow}px of sideways scrolling from ${wide.join(', ')}`);
  }
  assert.equal(await page.locator('#animus-main select').count(), 0, `${name}: a native select`);
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: true});
}

const settle = page => page.waitForTimeout(250);

/** Picks [label] from the offers' "..." menu by keyboard: focus, Enter, arrows, Enter. */
async function addByKeyboard(page, label) {
  await page.locator('#animus-shop-add-more-button').focus();
  await page.keyboard.press('Enter');
  await page.getByRole('menuitem', {name: label}).waitFor();
  // Focus lands in the menu after it renders; each arrow press moves it a frame later.
  await page.waitForFunction(() => document.activeElement && document.activeElement.closest('[role="menu"]'));
  for (let i = 0; i < 6; i++) {
    const current = await page.evaluate(() => document.activeElement.textContent.trim());
    if (current.startsWith(label)) break;
    await page.keyboard.press('ArrowDown');
    await page.waitForFunction(previous => document.activeElement && document.activeElement.textContent.trim() !== previous, current);
  }
  await page.keyboard.press('Enter');
}

/** Picks [entry] in the row menu named [name] ("Actions for offer 2"). */
async function rowMenu(page, scope, name, entry) {
  await scope.getByRole('button', {name, exact: true}).click();
  await page.getByRole('menuitem', {name: entry, exact: true}).click();
}

/** The preview frame; on phones it opens behind the header's Preview first. */
/**
 * Picks [option] of the preview's [label] choice: a toggle button, the
 * dropdown a squeezed switch turns into, or an entry of the header's menu.
 */
async function pickChoice(page, frame, label, option) {
  const button = frame.getByRole('button', {name: option, exact: true});
  if (await button.count()) return button.first().click();
  const select = frame.getByRole('combobox', {name: label});
  if (await select.count()) return pickOption(page, select.first(), option);
  // The window drawn sits in the preview header's menu.
  await frame.getByRole('button', {name: 'More actions'}).first().click();
  await page.getByRole('menuitem', {name: option}).click();
}

async function preview(page) {
  await showPreview(page);
  return page.getByRole('region', {name: 'Preview'});
}

/** The rows' offer ids, top to bottom. */
const rowIds = page => page.locator('#animus-main li[data-offer]').evaluateAll(rows => rows.map(row => row.getAttribute('data-id')));

/** The trading screen's offer ids. */
async function tradingIds(page) {
  const frame = await preview(page);
  await pickChoice(page, frame, 'Window', 'Trading screen');
  return frame.locator('.animus-merchant-offer').evaluateAll(rows => rows.map(row => row.getAttribute('data-offer')));
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
  await page.getByTestId('pair-submit').waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await waitOnline(page);
  // The workspace fills from the server's list by itself.
  await page.locator('[data-testid="home-count"]').first().waitFor();
  const workspace = await page.evaluate(() => `#/workspace/server-${location.hash.split('/')[2]}`);
  await setMode(page, 'advanced');

  // A blank shop from New (Start from is Blank by default); the new address carries the id.
  await page.goto(`${site}/${workspace}/new/shop`);
  await page.getByLabel('Title', {exact: true}).fill('&3Harbor');
  await page.getByRole('button', {name: 'Create', exact: true}).click();
  await page.waitForFunction(() => location.hash.includes('/shop/'));
  const shop = decodeURIComponent((await page.evaluate(() => location.hash)).split('/shop/')[1].split('?')[0]);
  const form = page.getByRole('region', {name: 'Shop'});
  await form.getByText('No offers yet.').waitFor();
  assert.equal(await page.locator('#animus-main [role="tablist"]').count(), 1, `${label}: one tab strip`);

  // A buy offer with Add offer, with a price.
  await page.locator('#animus-shop-add').click();
  const buy = form.locator('li[data-offer][data-id="buy"].is-open');
  await buy.getByLabel('Price', {exact: true}).fill('25');
  await settle(page);
  // A new item shows in the chest window with its icon, never part drawn.
  await preview(page);
  await sampleChestFrames(page);
  await buy.getByLabel('Gives item', {exact: true}).fill('minecraft:cake');
  await chestFramesWhole(page, `${label} new item`);
  await settle(page);
  await form.locator('summary', {hasText: /1 cake for 25 money/}).waitFor();

  // A trade by keyboard: 1 enchanted book for 2 emeralds + 1 book.
  await addByKeyboard(page, 'Add trade');
  const trade = form.locator('li[data-offer][data-id="trade"].is-open');
  await trade.waitFor();
  await trade.getByRole('button', {name: 'More: Takes 1 amount'}).click();
  await settle(page);
  await trade.getByRole('button', {name: 'Add an item to Takes'}).click();
  await settle(page);
  await trade.getByLabel('Takes 2 item', {exact: true}).fill('minecraft:book');
  await settle(page);
  await trade.getByLabel('Gives 1 item', {exact: true}).fill('minecraft:enchanted_book');
  await settle(page);
  await form.locator('summary', {hasText: /1 enchanted book for 2 emeralds \+ 1 book/}).waitFor();

  // A command offer by keyboard.
  await addByKeyboard(page, 'Add command offer');
  const command = form.locator('li[data-offer][data-id="command"].is-open');
  assert.equal(await command.getByLabel('Command 1', {exact: true}).inputValue(), '', 'a new command offer starts empty');
  assert.equal(await page.getByText(/server refuses this shop/).count(), 0, 'no allow-list note before a command is written');
  await command.getByLabel('Command 1', {exact: true}).fill('give {player} diamond 1');
  await settle(page);
  // Once written, the note names the config keys to add.
  await command.getByText(`add "${shop.split(':')[0]}" to commandNamespaces, or "${shop}" to commandBehaviors, under [behaviors.actions]`).first().waitFor();
  assert.deepEqual(await rowIds(page), ['buy', 'command', 'trade']);
  assert.deepEqual(await tradingIds(page), ['buy', 'command', 'trade']);
  await check(page, `${label}-three-offers`);

  // Reorder by keyboard: the command row's menu, Move up.
  const buying = form.locator('section[data-group="0:buy"]');
  await buying.getByRole('button', {name: 'Actions for offer 2'}).focus();
  await page.keyboard.press('Enter');
  await page.getByRole('menuitem', {name: 'Move up', exact: true}).waitFor();
  await page.getByRole('menuitem', {name: 'Move up', exact: true}).click();
  await page.waitForFunction(() => document.querySelector('#animus-main li[data-offer]')?.getAttribute('data-id') === 'command');
  assert.deepEqual(await rowIds(page), ['command', 'buy', 'trade']);
  assert.deepEqual(await tradingIds(page), ['command', 'buy', 'trade'], 'the trading screen follows');

  // Reorder by drag (pointer, wide screens): the buy row back on top.
  if (viewport.width > 640) {
    const open = form.locator('li[data-offer].is-open details[open] > summary');
    if (await open.count()) await open.first().click();
    await buying.locator('li[data-id="buy"] .animus-rows-grip').dragTo(buying.locator('li[data-id="command"]'));
  } else {
    await rowMenu(page, buying, 'Actions for offer 2', 'Move up');
  }
  await page.waitForFunction(() => document.querySelector('#animus-main li[data-offer]')?.getAttribute('data-id') === 'buy');
  await settle(page);
  assert.deepEqual(await rowIds(page), ['buy', 'command', 'trade']);

  // The chest window shows the buy grid and picks an offer in the form.
  let frame = await preview(page);
  await pickChoice(page, frame, 'Window', 'Trading screen');
  await sampleChestFrames(page);
  await pickChoice(page, frame, 'Window', 'Chest window');
  await chestFramesWhole(page, `${label} window switch`);
  await frame.locator('[data-slot="1"]').click();
  await form.locator('li[data-id="command"].is-open').waitFor();
  // The trading screen picks too, and crosses out offers out of limits.
  frame = await preview(page);
  await pickChoice(page, frame, 'Window', 'Trading screen');
  await frame.locator('.animus-merchant-offer[data-offer="trade"]').click();
  const opened = form.locator('li[data-id="trade"]');
  await form.locator('li[data-id="trade"].is-open').waitFor();
  await opened.locator('.ui-section-title', {hasText: /^Rules/}).click();
  await opened.getByLabel('Per player limit', {exact: true}).fill('3');
  await settle(page);
  frame = await preview(page);
  // Rare cases sit in the preview header's menu.
  await frame.getByRole('button', {name: 'More actions'}).first().click();
  await page.getByRole('menuitem', {name: /^Sold out/}).click();
  await frame.locator('.animus-merchant-offer.is-crossed[data-offer="trade"]').waitFor();
  assert.equal(await frame.locator('.animus-merchant-offer.is-crossed').count(), 1);
  await check(page, `${label}-previews`);

  // Delete from the row menu, then undo.
  await rowMenu(page, buying, 'Actions for offer 1', 'Delete');
  await page.getByRole('alertdialog').getByRole('button', {name: 'Delete', exact: true}).click();
  await page.waitForFunction(() => document.querySelectorAll('#animus-main li[data-offer]').length === 2);
  assert.deepEqual(await rowIds(page), ['command', 'trade']);
  await topbarAction(page, 'Undo');
  await page.waitForFunction(() => document.querySelectorAll('#animus-main li[data-offer]').length === 3);
  assert.deepEqual(await rowIds(page), ['buy', 'command', 'trade']);

  // Attach to the greeter and the baker from Settings.
  await form.getByRole('tab', {name: /^Settings/}).click();
  const npcPicker = form.getByLabel('NPC that opens this shop', {exact: true});
  await chooseText(npcPicker, 'Greeter');
  await form.locator(`li[data-user="npc:${greeter}"]`).waitFor();
  // Replacing another shop asks first; Escape keeps the old one.
  await chooseText(npcPicker, 'Baker');
  const confirm = page.getByRole('alertdialog', {name: /^Replace shop:bakery on /});
  await confirm.waitFor();
  await page.keyboard.press('Escape');
  await confirm.waitFor({state: 'detached'});
  assert.equal(await form.locator(`li[data-user="npc:${baker}"]`).count(), 0, 'cancel keeps the old shop');
  await chooseText(npcPicker, 'Baker');
  await confirm.getByRole('button', {name: 'Replace', exact: true}).click();
  await form.locator(`li[data-user="npc:${baker}"]`).waitFor();
  await check(page, `${label}-attached`);

  // Apply the shop and both NPCs through the save bar; the server has them.
  await saveAction(page, 'apply').click();
  await page.waitForFunction(() => /Applied 3 changes|Up to date/.test(document.querySelector('.ui-save-bar .ui-save-message')?.textContent || ''), null, {timeout: 20000});
  assert.match(await saveState(page).innerText(), /Applied 3 changes|Up to date/);
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

  // Reopen: a fresh load shows the same rows.
  await page.reload();
  await waitOnline(page);
  await openFromSidebar(page, shop);
  await page.locator('#animus-main li[data-offer]').first().waitFor();
  assert.deepEqual(await rowIds(page), ['buy', 'command', 'trade']);
  await page.getByRole('region', {name: 'Shop'}).locator('summary', {hasText: /1 enchanted book for 2 emeralds \+ 1 book/}).waitFor();
  await check(page, `${label}-reopened`);
  assert.deepEqual(errors, [], label);
  await context.close();
}

const trader = 'realshape:trader';
const traderNpc = 'realshape/trader-npc';

/** A native shop of 18 trades as an import leaves it, a few with stock. */
function traderShop() {
  const trade = {};
  for (let slot = 0; slot < 18; slot++) {
    trade[String(slot)] = {
      id: `trade-${slot}`,
      type: 'trade',
      item: {id: 'minecraft:saddle', count: 1},
      needs: [{item: {id: 'minecraft:emerald'}, amount: slot + 1}],
      gives: [{item: {id: 'minecraft:saddle'}, amount: 1}],
      ...(slot % 4 === 0 ? {stock: {maximum: 5}} : {}),
    };
  }
  return {schemaVersion: 1, id: trader, title: '&2Wandering trader', style: 'native', modes: ['trade'], pages: [{name: 'Page 1', rows: 3, trade}]};
}

async function nativePaging(browser, viewport, label) {
  await control('reset', {});
  await control('restore', {});
  await control('edit', {kind: 'shop', id: trader, source: traderShop()});
  await control('edit', {kind: 'npc', id: traderNpc, source: {
    schemaVersion: 2, uuid: '0f0e0d0c-0b0a-4000-8000-00000000a001', id: 'trader-npc', group: 'realshape', name: 'Trader',
    type: 'minecraft:villager', spawned: true, position: {world: 'world', x: 4.5, y: 64, z: 4.5, yaw: 0, pitch: 0},
    modules: {'animus:shop': {shop: trader}},
  }});
  const {code} = await control('pair?paths=both', {});
  const context = await browser.newContext({viewport});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  page.on('pageerror', error => errors.push(String(error)));
  await page.goto(`${site}/#/pair/${code}`);
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await waitOnline(page);
  await page.locator('[data-testid="home-count"]').first().waitFor();
  const workspace = await page.evaluate(() => `#/workspace/server-${location.hash.split('/')[2]}`);
  await setMode(page, 'advanced');
  await page.goto(`${site}/${workspace}/shop/${encodeURIComponent(trader)}`);
  const form = page.getByRole('region', {name: 'Shop'});
  await form.locator('li[data-offer]').first().waitFor();

  // Every row keeps its sentence, phones included, and says "for" once.
  const sentences = await form.locator('li[data-offer] .animus-shop-sentence').evaluateAll(spans => spans.map(span => ({text: span.textContent, width: span.getBoundingClientRect().width})));
  assert.equal(sentences.length, 18, `${label}: 18 rows`);
  for (const {text, width} of sentences) {
    assert.match(text, /^1 saddle for \d+ emeralds?$/, `${label}: a row reads as a trade`);
    assert.ok(width > 40, `${label}: the sentence "${text}" shows (${width}px)`);
  }
  assert.equal(await form.locator('li[data-offer] summary').first().getByText('for', {exact: true}).count(), 0, `${label}: no "for" between the tiles`);

  // One page of trades with a pager, the frame within its cap.
  const band = viewport.width >= 761 && viewport.width < 1100;
  const frame = await preview(page);
  const offers = frame.locator('.animus-merchant-offer');
  await offers.first().waitFor();
  const perPage = band ? 3 : 7;
  assert.equal(await offers.count(), perPage, `${label}: ${perPage} trades on a page`);
  await frame.getByText(`Page 1 of ${Math.ceil(18 / perPage)}.`, {exact: false}).waitFor();
  await page.waitForTimeout(400);
  const cap = band ? 240 : viewport.height * 0.6;
  const height = (await frame.boundingBox()).height;
  assert.ok(height <= cap + 1, `${label}: the preview is ${height}px tall, over ${cap}`);
  const fits = await frame.evaluate(el => el.scrollHeight <= el.clientHeight + 1 && el.scrollWidth <= el.clientWidth + 1);
  assert.ok(fits, `${label}: nothing scrolls or is cut inside the preview`);
  await frame.getByRole('button', {name: 'Next page'}).click();
  await frame.locator(`.animus-merchant-offer[data-offer="trade-${perPage}"]`).waitFor();
  // Picking a row on a later page turns the trading screen to it.
  await form.locator('li[data-id="trade-17"] summary').first().click();
  await frame.locator('.animus-merchant-offer[data-offer="trade-17"].is-selected').waitFor();
  await check(page, `${label}-native-paged`);

  // The preview menu checks the window shown; Sold out dims the chest window's stocked offers.
  await frame.getByRole('button', {name: 'More actions'}).first().click();
  // A menu row's icon is the library's icon glyph beside its label.
  assert.equal(await page.getByRole('menuitem', {name: /^Trading screen/}).locator('[aria-hidden="true"] > i').count(), 1, `${label}: the shown window is checked`);
  assert.equal(await page.getByRole('menuitem', {name: /^Chest window/}).locator('[aria-hidden="true"] > i').count(), 0);
  await page.getByRole('menuitem', {name: /^Chest window/}).click();
  await frame.locator('.animus-chest-slot').first().waitFor();
  assert.equal(await frame.locator('.animus-chest-slot.is-hidden').count(), 0, `${label}: nothing dimmed before Sold out`);
  await frame.getByRole('button', {name: 'More actions'}).first().click();
  await page.getByRole('menuitem', {name: /^Sold out/}).click();
  await page.waitForFunction(() => document.querySelectorAll('.ui-stage .animus-chest-slot.is-hidden').length === 5);
  assert.match(await frame.locator('.animus-chest-slot.is-hidden').first().getAttribute('aria-label'), /Stock: 0/);

  // Settings: the window field is named once, and Opened by is foreground text.
  await form.getByRole('tab', {name: /^Settings/}).click();
  await form.getByLabel('Opens as', {exact: true}).waitFor();
  assert.equal(await form.getByLabel('Window', {exact: true}).count(), 0, `${label}: no field called Window under Window`);
  const link = form.locator(`li[data-user="npc:${traderNpc}"] button`).first();
  await link.waitFor();
  const colours = await link.evaluate(button => {
    const probe = document.createElement('span');
    probe.style.color = 'var(--primary)';
    button.parentElement.appendChild(probe);
    const primary = getComputedStyle(probe).color;
    probe.remove();
    return {link: getComputedStyle(button).color, primary};
  });
  assert.notEqual(colours.link, colours.primary, `${label}: the Opened by link is not the primary colour`);
  await check(page, `${label}-native-settings`);

  // Simple names an offer's items; typed words search the list and write
  // nothing until a name is taken, which writes its id.
  await setMode(page, 'simple');
  await form.getByRole('tab', {name: /^Offers/}).click();
  await form.locator('li[data-id="trade-0"] summary').first().click();
  const offer = form.locator('li[data-offer][data-id="trade-0"].is-open');
  const takes = offer.getByLabel('Takes 1 item', {exact: true});
  await takes.waitFor();
  assert.equal(await takes.inputValue(), 'Emerald', `${label}: Simple names the item it takes`);
  assert.equal(await offer.getByLabel('Gives 1 item', {exact: true}).inputValue(), 'Saddle', `${label}: Simple names the item it gives`);
  const ids = await offer.evaluate(el => [el.innerText, ...[...el.querySelectorAll('input')].flatMap(input => [input.value, input.placeholder])].join('\n').match(/\b[a-z0-9_.-]+:[a-z0-9_./-]+/g));
  assert.equal(ids, null, `${label}: no item id in a Simple offer: ${ids}`);
  const sentence = offer.locator('.animus-shop-sentence').first();
  await takes.fill('gold ingot');
  await page.locator('[role="option"][data-value="minecraft:gold_ingot"]').first().waitFor();
  await settle(page);
  assert.equal(await sentence.textContent(), '1 saddle for 1 emerald', `${label}: a search writes nothing`);
  await takes.press('Tab');
  await settle(page);
  assert.equal(await takes.inputValue(), 'Gold ingot', `${label}: the named item is taken`);
  assert.equal(await sentence.textContent(), '1 saddle for 1 gold ingot', `${label}: taking it writes its id`);
  assert.deepEqual(errors, [], label);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    for (const width of [1440, 1024, 768, 390]) {
      await nativePaging(browser, {width, height: 900}, `shop-native-${width}`);
      console.log(`shop-native-${width}: ok`);
    }
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
