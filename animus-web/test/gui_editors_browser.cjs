// Opens test/fixtures/gui_editors in the built site at desktop and phone
// widths, in the dark and light themes, and checks each GUI page:
//   - chest GUI: the window is the editor (keyboard to a slot, brush drag,
//     lore in slot names, the letter legend), two tabs (Slot, Window) with
//     keys, list, variants and named actions as sections, title completion;
//   - a refused Gloss action flagged at its own row;
//   - shop: a preview slot opens its offer, price edit, kind change strips
//     fields, rows read as "gives for price", container shop round trip,
//     trading screen order, window and layout;
//   - hologram in Simple: two tabs, the advanced settings count with Show,
//     a line edit and a row menu move that keep every hidden key, a plain
//     line's sample in the style of the line above;
//   - menu: Add component lists what each kind does, the new component opens;
//   - text animation: no order is shown as one, the preset fix, frames with
//     their formatted sample;
//   - a Gloss bundle imports.
// Edits round-trip to the document JSON, with no console errors and no
// sideways scrolling.
//
//   python3 tool/serve_site.py --port <port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/gui_editors_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openFromSidebar, setMode, topbarAction, showView, showPreview} = require('./support/shell.cjs');
const {chooseAndWait, optionLabels} = require('./support/select.cjs');

const fixture = path.resolve(__dirname, 'fixtures/gui_editors');
const bundle = path.resolve(__dirname, 'fixtures/gloss_bundle/gloss-workspace.json');
const shots = process.env.ANIMUS_SCREENSHOTS;

async function shot(page, name) {
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: true});
}

async function codeOf(page) {
  await showView(page, 'code');
  const text = await page.locator('.animus-code textarea').inputValue();
  await showView(page, 'visual');
  return JSON.parse(text);
}

async function open(page, text) {
  await openFromSidebar(page, text);
  await page.locator('.ui-page-header').waitFor();
}

/// Lets the store commit an edit before the next field reads the document.
async function settle(page) {
  await page.waitForTimeout(150);
}

async function noSideways(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 0, `${where} scrolls sideways by ${overflow}px`);
}

/// Opens the kit section whose title starts with [title] inside [scope].
async function openSection(scope, title) {
  const section = scope.locator('.ui-section').filter({has: scope.page().locator('.ui-section-title', {hasText: new RegExp(`^${title}`)})}).first();
  const details = section.locator(':scope > details');
  if (!(await details.evaluate(element => element.open))) await details.locator(':scope > summary').click();
  await scope.page().waitForFunction(element => element.open, await details.elementHandle());
  return section;
}

/// Picks [entry] in the row menu named [name] ("Actions for line 2").
async function rowMenu(page, scope, name, entry) {
  await scope.getByRole('button', {name, exact: true}).click();
  await page.getByRole('menuitem', {name: entry, exact: true}).click();
}

/// The preview frame; on phones it opens behind the header's Preview first.
async function preview(page) {
  await showPreview(page);
  return page.getByRole('region', {name: 'Preview'});
}

async function chestGui(page, tag, phone) {
  await open(page, 'town:board');
  const form = page.getByRole('region', {name: 'Chest GUI'});
  const slots = page.locator('.animus-chest-slot');
  await slots.first().waitFor();
  assert.equal(await slots.count(), 36);
  await page.locator('.animus-chest-face img').first().waitFor();
  assert.ok(await page.locator('.animus-chest-face img').count() >= 20, 'item icons are drawn');
  // The letters are explained under the window.
  const legend = page.getByRole('list', {name: 'Mask keys'});
  assert.ok((await legend.getByRole('listitem').count()) >= 4, 'every letter has a legend entry');
  await legend.getByText('Previous').waitFor();
  await page.locator('[data-slot="31"]').click();
  await form.getByText('Key T: every T slot shows this.').waitFor();
  assert.equal(await form.getByRole('tab').count(), 2, 'one tab strip: Slot and Window');
  // The item field is as tall as its control: no empty band under it.
  const itemField = await form.locator('.animus-item-picker').first().evaluate((picker) => ({picker: picker.getBoundingClientRect().height, control: picker.querySelector('input').getBoundingClientRect().height}));
  assert.ok(itemField.picker <= itemField.control + 4, `the item field leaves a gap under it: ${JSON.stringify(itemField)}`);
  await shot(page, `${tag}-chest-gui`);
  await noSideways(page, `${tag} chest GUI`);
  // Keyboard: Tab from one slot to the next, Enter opens it in the form.
  await page.locator('[data-slot="0"]').focus();
  await page.keyboard.press('Tab');
  assert.equal(await page.evaluate(() => document.activeElement.getAttribute('data-slot')), '1');
  await page.keyboard.press('Enter');
  await page.getByRole('tab', {name: 'Slot 2', selected: true}).waitFor();
  assert.match(await page.locator('[data-slot="29"]').getAttribute('aria-label'), /Previous\. Page 1 of 1/);
  if (phone) {
    // The whole window fits its frame, with slots no smaller than a small button.
    const box = await page.locator('[data-slot="0"]').boundingBox();
    assert.ok(box.width >= 32 && box.height >= 32, `phone slots are ${box.width}x${box.height}`);
    const fits = await page.evaluate(() => {
      const frame = document.querySelector('.ui-stage').getBoundingClientRect();
      const window = document.querySelector('.animus-chest-screen');
      return window.scrollWidth <= window.clientWidth && window.getBoundingClientRect().right <= frame.right;
    });
    assert.ok(fits, 'the chest window is not clipped on a phone');
  }
  // The brush paints every slot a drag crosses.
  await chooseAndWait(page.getByLabel('Paint', {exact: true}), '#');
  await page.locator('[data-slot="10"]').scrollIntoViewIfNeeded();
  const centre = async slot => {
    const box = await page.locator(`[data-slot="${slot}"]`).boundingBox();
    return [box.x + box.width / 2, box.y + box.height / 2];
  };
  await page.mouse.move(...(await centre(10)));
  await page.mouse.down();
  for (const slot of [11, 12]) {
    await settle(page);
    const [x, y] = await centre(slot);
    await page.mouse.move(x, y, {steps: 4});
  }
  await page.mouse.up();
  await page.waitForTimeout(300);
  let json = await codeOf(page);
  assert.equal(json.mask[1], '####LLLL#', `${tag} brush stroke`);
  await chooseAndWait(page.getByLabel('Paint', {exact: true}), '');
  await topbarAction(page, 'Undo');
  await page.waitForTimeout(200);
  json = await codeOf(page);
  assert.equal(json.mask[1], '#LLLLLLL#');
  // The Window tab: title with completion, Show when with its helper, and
  // keys, list, variants and named actions as sections, not tabs.
  await form.getByRole('tab', {name: 'Window'}).click();
  await form.getByText('Empty shows it to everyone.').first().waitFor();
  for (const title of ['Keys', 'List', 'Variants', 'Named actions', 'Refresh']) await form.locator('.ui-section-title', {hasText: new RegExp(`^${title}`)}).first().waitFor();
  const title = form.getByLabel('Title', {exact: true});
  await title.fill('&2Edited board ');
  await title.press('End');
  await title.pressSequentially('{{ vie');
  const option = page.getByRole('option').filter({hasText: 'viewer.name'}).first();
  await option.waitFor();
  await shot(page, `${tag}-chest-completion`);
  await option.click();
  await title.pressSequentially(' }}');
  await page.waitForTimeout(300);
  // The title's sample sits on the window's grey, readable as in game.
  const strip = await form.locator('.animus-gui-sample:has(> .animus-gui-window-title)').first().evaluate(div => getComputedStyle(div).backgroundColor);
  assert.equal(strip, 'rgb(198, 198, 198)', `${tag}: the window title's sample is on the window grey`);
  // A key opens in place; its letter renames the key and the mask.
  await form.locator('summary', {hasText: /^Key T,/}).click();
  const letter = form.getByLabel('Letter', {exact: true});
  await letter.fill('S');
  await page.waitForTimeout(300);
  json = await codeOf(page);
  assert.equal(json.title, '&2Edited board {{ viewer.name }}');
  assert.ok(json.keys.S && !json.keys.T, 'the key is renamed');
  assert.ok(json.mask.some(row => row.includes('S')) && !json.mask.some(row => row.includes('T')), 'the mask follows the new letter');
  assert.ok(json.list && json.variants, 'untouched fields survive the edit');
  await noSideways(page, `${tag} chest GUI window`);

  // A refused Gloss action is flagged on its own row.
  await open(page, 'town:gloss-only');
  await page.locator('[data-slot="0"]').click();
  const refused = page.locator('[data-path="$.keys.X.actions[1]"][data-refused="true"]');
  await refused.waitFor();
  await refused.getByText(/not available/).first().waitFor();
  await shot(page, `${tag}-chest-refused`);
}

async function shop(page, tag) {
  await open(page, 'town:market');
  const form = page.getByRole('region', {name: 'Shop'});
  // Rows read as what the player gets for what it costs.
  await form.locator('summary', {hasText: /4 bread for 12 money/}).waitFor();
  await (await preview(page)).locator('[data-slot="10"]').click();
  await form.getByLabel('Price', {exact: true}).fill('15');
  await page.waitForTimeout(300);
  await shot(page, `${tag}-shop`);
  let json = await codeOf(page);
  assert.equal(json.pages[0].buy['10'].price, 15);
  assert.equal(json.pages[0].buy['10'].limit.resetSeconds, 86400);
  await noSideways(page, `${tag} shop`);

  // Changing an offer's kind removes what the new kind refuses.
  await (await preview(page)).locator('[data-slot="12"]').click();
  await chooseAndWait(form.getByLabel('Kind', {exact: true}), 'static');
  await page.waitForTimeout(300);
  json = await codeOf(page);
  assert.equal(json.pages[0].buy['12'].type, 'static');
  assert.equal(json.pages[0].buy['12'].stock, undefined, 'stock goes with the kind change');
  assert.equal(json.pages[0].buy['12'].permission, 'town.market.rare', 'other fields stay');

  // A container shop: owner and containers round-trip, buy entries are refused.
  await form.getByRole('tab', {name: /^Settings/}).click();
  const containers = await openSection(form, 'Containers');
  await containers.getByRole('button', {name: 'Add container'}).click();
  await form.getByLabel('Container 1 world').fill('world_nether');
  await settle(page);
  await form.getByLabel('Container 1 y').fill('70');
  await settle(page);
  await openSection(form, 'Owner');
  await form.getByLabel('Owner UUID').fill('069a79f4-44e9-4726-a5be-fca90e38aaf5');
  await settle(page);
  await form.getByLabel('Owner name').fill('Notch');
  await page.waitForTimeout(300);
  await shot(page, `${tag}-shop-containers`);
  json = await codeOf(page);
  assert.deepEqual(json.containers, [{world: 'world_nether', x: 0, y: 70, z: 0}]);
  assert.deepEqual(json.owner, {uuid: '069a79f4-44e9-4726-a5be-fca90e38aaf5', name: 'Notch'});
  await (await preview(page)).locator('[data-slot="10"]').click();
  await form.getByText(/only trade and static entries/).first().waitFor();
  assert.deepEqual((await optionLabels(form.getByLabel('Kind', {exact: true}))).sort(), ['Buy (player pays money)', 'Decoration', 'Trade (items for items)']);
  await form.getByRole('tab', {name: /^Settings/}).click();
  await rowMenu(page, await openSection(form, 'Containers'), 'Actions for container 1', 'Delete');
  await page.getByRole('alertdialog').getByRole('button', {name: 'Delete', exact: true}).click();
  await page.waitForTimeout(300);
  json = await codeOf(page);
  assert.equal(json.containers, undefined, 'the last container goes with its list');

  // Window and layout: the trading screen lists offers in mode, page and slot order.
  await form.getByRole('tab', {name: /^Settings/}).click();
  await chooseAndWait(form.getByLabel('Opens as', {exact: true}), 'native');
  await settle(page);
  const offers = (await preview(page)).locator('.animus-merchant-offer');
  await offers.first().waitFor();
  assert.deepEqual(await offers.evaluateAll(rows => rows.map(row => row.getAttribute('data-offer'))), ['bread', 'town-kit', 'iron-pickaxe', 'wheat', 'emerald-for-diamond'], 'the static golden apple is left out');
  assert.equal(await offers.first().locator('.animus-merchant-price').innerText(), '15');
  assert.equal(await offers.nth(3).locator('.animus-merchant-price').innerText(), 'Pays 4');
  await chooseAndWait(form.getByLabel('Layout', {exact: true}), 'town:board');
  await page.waitForTimeout(300);
  await form.getByText(/only the chest style uses a layout/).first().waitFor();
  await shot(page, `${tag}-shop-native`);
  await noSideways(page, `${tag} shop native`);
  json = await codeOf(page);
  assert.equal(json.style, 'native');
  assert.equal(json.layout, 'town:board');
  await form.getByRole('tab', {name: /^Settings/}).click();
  await chooseAndWait(form.getByLabel('Layout', {exact: true}), '');
  await settle(page);
  await chooseAndWait(form.getByLabel('Opens as', {exact: true}), '');
  await page.waitForTimeout(300);
  json = await codeOf(page);
  assert.equal(json.style, undefined);
  assert.equal(json.layout, undefined);
}

async function hologram(page, tag) {
  await open(page, 'town:welcome');
  const form = page.getByRole('region', {name: 'Hologram'});
  // Code switches to Advanced, so read the document first.
  const before = await codeOf(page);
  await setMode(page, 'simple');
  assert.deepEqual(await form.getByRole('tab').allInnerTexts(), ['Lines', 'Where'], 'Simple shows lines and where it hangs');
  await form.getByText(/^\d+ advanced settings?$/).waitFor();
  const first = form.getByLabel('Line 1', {exact: true});
  await first.fill('&6Welcome, traveller');
  await settle(page);
  await rowMenu(page, form, 'Actions for line 2', 'Move up');
  await page.waitForTimeout(300);
  // Text rows share one display: a plain line takes the style of the line above.
  await form.getByRole('button', {name: 'Add line', exact: true}).click();
  const added = form.locator('input[aria-label="Line 6"], textarea[aria-label="Line 6"]');
  await added.fill('plain');
  // The field's own stack: the nearest box round the input that holds its sample.
  const carried = added.locator('xpath=ancestor::*[./div[contains(concat(" ", @class, " "), " animus-gui-sample ")]][1]').locator('.animus-gui-sample span[style]').first();
  await carried.waitFor();
  assert.equal(await carried.evaluate(span => span.style.color), 'rgb(170, 170, 170)', `${tag}: a plain line carries the gray of the line above`);
  await form.getByRole('tab', {name: 'Where'}).click();
  await form.getByLabel('World', {exact: true}).waitFor();
  assert.equal(await form.getByText('Anchor', {exact: true}).count(), 0, 'no Anchor in Simple');
  await shot(page, `${tag}-hologram-simple`);
  await noSideways(page, `${tag} hologram`);
  await form.getByRole('button', {name: 'Show', exact: true}).click();
  await form.getByRole('tab', {name: 'Clicks'}).waitFor();
  const after = await codeOf(page);
  assert.equal(after.lines[1], '&6Welcome, traveller', 'the edited line moved down');
  assert.deepEqual(after.lines[0], before.lines[1]);
  for (const key of ['anchor', 'actions', 'hitbox']) assert.equal(JSON.stringify(after[key]), JSON.stringify(before[key]), `Simple keeps ${key}`);
}

async function menu(page, tag) {
  await open(page, 'town:hub');
  const form = page.getByRole('region', {name: 'Menu'});
  await form.getByRole('button', {name: 'Add component'}).click();
  const item = page.getByRole('menuitem', {name: /^Slider/});
  await item.waitFor();
  await item.getByText('Picks a number along a bar.').waitFor();
  await shot(page, `${tag}-menu-add`);
  await item.click();
  await form.getByLabel('Component name', {exact: true}).waitFor();
  await page.waitForTimeout(300);
  const json = await codeOf(page);
  const added = json.components[json.components.length - 1];
  assert.equal(added.data.type, 'slider');
  await noSideways(page, `${tag} menu`);
}

async function textAnimation(page, tag) {
  await open(page, 'town:sparkle');
  const form = page.getByRole('region', {name: 'Text animation'});
  const order = form.getByLabel('Order', {exact: true});
  assert.ok(!(await optionLabels(order)).includes('default'), 'no order called default');
  await form.getByText('Pick the order the frames play in.').waitFor();
  await form.getByRole('button', {name: 'Copy fields in'}).click();
  await page.waitForTimeout(300);
  const json = await codeOf(page);
  assert.equal(json.preset, undefined, 'the preset is copied in and removed');
  assert.ok(await form.locator('.animus-gui-sample').count() >= 3, 'every frame shows its formatted sample');
  await shot(page, `${tag}-text-animation`);
  await noSideways(page, `${tag} text animation`);
}

// Frames, hologram Style and Where.
// Colour frames are swatches with the picked frame's field under them; a
// long text frame list pages by thirty; a clock name the server reads is no
// problem while an unknown one is counted in the header; the hologram's
// Style tab shows its groups open; a long coordinate shows two decimals and
// the document keeps every digit.

/** The header's problem count ("2 problems"); Valid and Checking are 0. */
async function headerProblemCount(page) {
  const badge = page.locator('.ui-page-header [aria-controls="ui-doc-problems"]').first();
  if (!(await badge.count())) return 0;
  return Number.parseInt(await badge.textContent(), 10) || 0;
}

async function textAnimationFrames(page, tag) {
  await open(page, 'town:sparkle');
  const form = page.getByRole('region', {name: 'Text animation'});
  const colours = ['[FF0000]', '[00FF00]', '[0000FF]'];
  for (let index = 0; index < colours.length; index++) {
    await form.getByLabel(`Frame ${index + 1}`, {exact: true}).fill(colours[index]);
    await settle(page);
  }
  // The grid or the list is picked as the page opens, never while typing.
  await open(page, 'town:welcome');
  await open(page, 'town:sparkle');
  const swatches = form.getByRole('group', {name: 'Frame colours'}).getByRole('button');
  await page.waitForFunction(() => document.querySelectorAll('.animus-anim-swatch').length === 3);
  assert.equal(await swatches.nth(1).getAttribute('aria-label'), 'Frame 2, #00FF00');
  await swatches.nth(1).click();
  assert.equal(await form.getByLabel('Frame 2', {exact: true}).inputValue(), '[00FF00]', 'the picked swatch opens its frame');
  assert.equal(await swatches.nth(1).getAttribute('aria-pressed'), 'true');
  for (let added = 0; added < 31; added++) {
    await form.getByRole('button', {name: 'Add frame', exact: true}).click();
    await page.waitForTimeout(60);
  }
  await page.waitForFunction(() => document.querySelectorAll('.animus-anim-swatch').length === 34);
  await shot(page, `${tag}-text-animation-swatches`);
  await noSideways(page, `${tag} text animation swatches`);
  // A frame typed into text keeps the grid while it is edited; the next
  // added frame shows the list, thirty to a page, on the new frame's page.
  await form.getByLabel('Frame 34', {exact: true}).fill('&aGo');
  await settle(page);
  assert.equal(await swatches.count(), 34, 'the grid stays while a frame is typed in');
  await form.getByRole('button', {name: 'Add frame', exact: true}).click();
  await form.getByText('Frames 31 to 35 of 35', {exact: true}).waitFor();
  assert.equal(await form.getByLabel('Frame 1', {exact: true}).count(), 0, 'frame 1 is on the first page');
  assert.equal(await form.getByLabel('Frame 34', {exact: true}).inputValue(), '&aGo');
  await form.getByRole('button', {name: 'Previous frames', exact: true}).click();
  await form.getByText('Frames 1 to 30 of 35', {exact: true}).waitFor();
  await noSideways(page, `${tag} text animation pages`);
  await form.getByRole('button', {name: 'Next frames', exact: true}).click();
  // The server reads time.seconds: no problem; an unknown name is one.
  const before = await headerProblemCount(page);
  await form.getByLabel('Frame 34', {exact: true}).fill('{{ floor(time.seconds) }}');
  await settle(page);
  await page.waitForTimeout(400);
  assert.equal(await headerProblemCount(page), before, 'time.seconds is a name the server reads');
  assert.equal(await form.getByText(/unknown name/).count(), 0);
  await form.getByLabel('Frame 34', {exact: true}).fill('{{ nope.thing }}');
  await settle(page);
  await page.waitForFunction(count => {
    const badge = document.querySelector('.ui-page-header [aria-controls="ui-doc-problems"]');
    return badge !== null && (Number.parseInt(badge.textContent, 10) || 0) === count + 1;
  }, before);
  await topbarAction(page, 'Undo');
  await settle(page);
}

async function hologramStyleAndWhere(page, tag) {
  await open(page, 'town:welcome');
  await setMode(page, 'advanced');
  const form = page.getByRole('region', {name: 'Hologram'});
  await form.getByRole('tab', {name: 'Style'}).click();
  // The tab is the style: its groups on the page, the common two open.
  for (const group of ['Facing and size', 'Text']) {
    const details = form.locator('.ui-section').filter({has: page.locator('.ui-section-title', {hasText: new RegExp(`^${group}`)})}).first().locator(':scope > details');
    assert.ok(await details.evaluate(element => element.open), `${tag}: ${group} is open`);
  }
  assert.equal(await form.locator('.ui-section-title', {hasText: /^Style/}).count(), 0, 'no folded Style row inside the Style tab');
  await form.getByLabel('Billboard', {exact: true}).waitFor();
  await shot(page, `${tag}-hologram-style`);
  await form.getByRole('tab', {name: 'Where'}).click();
  // Each position box is captioned X, Y or Z, as an NPC's are.
  for (const axis of ['X', 'Y', 'Z']) await form.locator('label', {hasText: new RegExp(`^${axis}$`)}).first().waitFor({timeout: 3000});
  const x = form.getByLabel('Position x', {exact: true});
  await x.fill('-22.411723669167085');
  await settle(page);
  await form.getByLabel('Position y', {exact: true}).click();
  await page.waitForTimeout(200);
  assert.equal(await x.inputValue(), '-22.41', 'the field shows two decimals');
  assert.equal((await codeOf(page)).anchor.position[0], -22.411723669167085, 'the document keeps every digit');
  await topbarAction(page, 'Undo');
  await settle(page);
}

async function run(browser, viewport, tag, theme) {
  const context = await browser.newContext({viewport, acceptDownloads: true});
  await context.addInitScript(mode => window.localStorage.setItem('animus.theme', mode), theme);
  const page = await context.newPage();
  page.on('dialog', dialog => dialog.accept());
  const phone = viewport.width < 520;
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })());
  await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(fixture);
  await page.waitForFunction(() => /#\/workspace\//.test(location.hash));
  await page.locator('.ui-save-bar').waitFor();
  assert.ok(await page.evaluate(mode => document.documentElement.classList.contains(mode), theme), `${theme} theme is on`);
  await setMode(page, 'advanced');

  await chestGui(page, tag, phone);
  await shop(page, tag);
  await hologram(page, tag);
  await menu(page, tag);
  await textAnimation(page, tag);
  await textAnimationFrames(page, tag);
  await hologramStyleAndWhere(page, tag);

  const bundleChooser = page.waitForEvent('filechooser');
  await topbarAction(page, 'Import a Gloss bundle');
  await (await bundleChooser).setFiles(bundle);
  await page.waitForTimeout(500);
  await open(page, 'gloss:chest');
  await page.waitForFunction(() => document.querySelectorAll('.animus-chest-slot').length === 9);

  assert.deepEqual(errors, []);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    await run(browser, {width: 1440, height: 900}, 'desktop-dark', 'dark');
    await run(browser, {width: 1440, height: 900}, 'desktop-light', 'light');
    await run(browser, {width: 390, height: 844}, 'phone-dark', 'dark');
    await run(browser, {width: 390, height: 844}, 'phone-light', 'light');
    console.log('gui_editors_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
