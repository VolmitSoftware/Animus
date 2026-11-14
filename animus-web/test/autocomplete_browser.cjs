// The shared inline autocomplete (lib/components/suggest), driven by the
// keyboard alone at 1440 px and 390 px in both themes: a behavior variable
// in a condition, an item id in an action argument with an unknown id
// underlined, a MiniMessage colour in say text, a placeholder hole in a
// hologram line, and key completion in the JSON code view (Advanced mode:
// Simple shows a behavior read only). The input is an ARIA combobox, rows
// are at least 44 px on phones, and the list is flat with the theme's
// rounded corners.
//
//   python3 tool/serve_site.py --port <port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/autocomplete_browser.cjs
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {assertNothingCut} = require('./support/cut_text.cjs');
const {openFromSidebar, showView} = require('./support/shell.cjs');

const url = process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })();
const fixture = path.resolve(__dirname, 'fixtures/autocomplete');
const mod = process.platform === 'darwin' ? 'Meta' : 'Control';

async function noSideways(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 1, `${where} scrolls sideways by ${overflow}px`);
}

/** Focuses [field] and replaces its text by typing [text]. */
async function retype(field, text) {
  await field.focus();
  await field.press(`${mod}+a`);
  await field.press('Backspace');
  await field.pressSequentially(text);
}

/** The open listbox of [field], checked against its combobox attributes. */
async function listOf(page, field, where, phone) {
  await page.waitForFunction(el => el.getAttribute('aria-expanded') === 'true', await field.elementHandle());
  assert.equal(await field.getAttribute('role'), 'combobox', `${where}: the field is a combobox`);
  const list = page.locator(`[id="${await field.getAttribute('aria-controls')}"]`);
  await list.waitFor();
  assert.equal(await list.getAttribute('role'), 'listbox');
  const active = await field.getAttribute('aria-activedescendant');
  assert.ok(active, `${where}: an active row`);
  assert.equal(await page.locator(`[id="${active}"]`).getAttribute('aria-selected'), 'true');
  const look = await list.evaluate(el => ({radius: parseFloat(getComputedStyle(el).borderTopLeftRadius), shadow: getComputedStyle(el).boxShadow}));
  assert.equal(look.shadow, 'none', `${where}: flat`);
  assert.ok(look.radius > 0, `${where}: the theme's rounded corners, got ${look.radius}px`);
  if (phone) {
    const height = await list.getByRole('option').first().evaluate(el => el.getBoundingClientRect().height);
    assert.ok(height >= 44, `${where}: phone rows are 44 px, got ${height}`);
  }
  return list;
}

/** The behavior's Steps view (the node editor). A conversation-shaped
 * behavior opens on its Conversation view, so the view is named. */
async function stepsView(page) {
  await page.evaluate(() => {
    const [route] = location.hash.split('?');
    location.hash = `${route}?view=visual`;
  });
  await page.locator('.animus-behavior-editor').waitFor();
}

async function closed(page, field) {
  await page.waitForFunction(el => el.getAttribute('aria-expanded') !== 'true', await field.elementHandle());
}

async function run(browser, viewport, theme) {
  const where = `${viewport.width}px ${theme}`;
  const phone = viewport.width < 520;
  const context = await browser.newContext({viewport, hasTouch: phone, colorScheme: theme});
  // Behaviors are edited in Advanced; Simple shows them read only.
  await context.addInitScript(mode => {
    window.localStorage.setItem('animus.theme', mode);
    window.localStorage.setItem('animus.mode', 'advanced');
  }, theme);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(url);
  await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(fixture);

  await openFromSidebar(page, 'town:crier');
  // It is shaped like a conversation, so it opens there first.
  await page.locator('#animus-main .animus-conv').waitFor();
  await stepsView(page);
  const editor = page.locator('.animus-behavior-editor');

  // A declared variable in a condition, with its type and scope.
  const condition = editor.locator('textarea.animus-expr-input').first();
  await retype(condition, 'vis');
  let list = await listOf(page, condition, `${where} variable`, phone);
  const visits = list.getByRole('option').filter({hasText: 'visits'}).first();
  assert.match(await visits.textContent(), /var.*visits.*integer.*Player variable/);
  await page.keyboard.press('Enter');
  await closed(page, condition);
  assert.equal(await condition.inputValue(), 'visits');

  // An item id in an action argument: fuzzy rows, Escape, Ctrl+Space, and
  // an unknown id underlined with its reason.
  const item = editor.getByLabel('Item', {exact: true});
  await retype(item, 'dsw');
  list = await listOf(page, item, `${where} item`, phone);
  assert.equal((await list.getByRole('option').first().locator('.animus-suggest-label').textContent()), 'diamond_sword');
  await page.keyboard.press('Escape');
  await closed(page, item);
  await page.keyboard.press('Control+Space');
  list = await listOf(page, item, `${where} item reopened`, phone);
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('ArrowUp');
  await page.keyboard.press('Tab');
  await closed(page, item);
  assert.equal(await item.inputValue(), 'diamond_sword');
  await retype(item, 'diamnd_swrd');
  await page.keyboard.press('Escape');
  const hint = item.locator('xpath=..').locator('.animus-ac-hint');
  await hint.waitFor();
  const described = await item.getAttribute('aria-describedby');
  assert.match(await page.locator(`[id="${described.split(' ').pop()}"]`).textContent(), /Unknown item/);
  await retype(item, 'diamond_sword');
  await page.keyboard.press('Escape');
  await hint.waitFor({state: 'detached'});
  await retype(item, 'minecraft:diamond_sword');
  await page.keyboard.press('Escape');
  assert.equal(await hint.count(), 0, `${where}: a namespaced id is not underlined`);

  // Typed fast and left at once (Escape, Tab), the document holds every
  // keystroke and the sibling argument is untouched.
  for (const [key, typed] of [['Escape', 'minecraft:golden_sword'], ['Tab', 'minecraft:diamond_sword']]) {
    await retype(item, typed);
    await page.keyboard.press(key);
    await showView(page, 'code');
    const action = JSON.parse(await page.locator('[data-testid="code-view"] textarea').inputValue()).routines.main.steps
      .find(step => step.capability === 'animus:give-item');
    assert.deepEqual(action.arguments, {item: typed, amount: 1}, `${where}: ${key} keeps the typed item`);
    await stepsView(page);
  }

  // Near the window's bottom edge the list still opens whole on screen and
  // never over the top bar.
  if (!phone) {
    await page.setViewportSize({width: viewport.width, height: 600});
    for (const offset of [560, 380]) {
      await item.evaluate((element, at) => window.scrollBy(0, element.getBoundingClientRect().top - at), offset);
      await retype(item, 'dsw');
      list = await listOf(page, item, `${where} item at ${offset}`, phone);
      const placed = await list.evaluate(element => ({
        top: element.getBoundingClientRect().top,
        bottom: element.getBoundingClientRect().bottom,
        bar: document.querySelector('.ui-topbar').getBoundingClientRect().bottom,
        height: window.innerHeight,
      }));
      assert.ok(placed.top >= placed.bar && placed.bottom <= placed.height, `${where}: item list at ${offset} is on screen below the top bar, got ${JSON.stringify(placed)}`);
      await page.keyboard.press('Escape');
      await closed(page, item);
    }
    await retype(item, 'minecraft:diamond_sword');
    await page.keyboard.press('Escape');
    await page.setViewportSize(viewport);
  }

  // A MiniMessage colour in say text, with a swatch.
  const say = editor.getByLabel('Text', {exact: true});
  await say.focus();
  await say.press('End');
  await say.pressSequentially(' <re');
  list = await listOf(page, say, `${where} colour`, phone);
  const red = list.getByRole('option').first();
  assert.equal(await red.locator('.animus-suggest-label').textContent(), 'red');
  assert.equal(await red.locator('.animus-suggest-swatch').count(), 1);
  await page.keyboard.press('Enter');
  await closed(page, say);
  assert.equal(await say.inputValue(), 'Welcome back <red>');
  // A placeholder in the same text: the kind column reads whole.
  await say.pressSequentially(' {pl');
  list = await listOf(page, say, `${where} placeholder`, phone);
  assert.ok((await list.locator('.animus-suggest-kind').allTextContents()).includes('placeholder'), `${where}: placeholder rows`);
  await assertNothingCut(list, '.animus-suggest-kind', `${where} placeholder kinds`);
  await page.keyboard.press('Escape');
  await closed(page, say);
  for (let typed = 0; typed < 4; typed++) await say.press('Backspace');
  assert.equal(await say.inputValue(), 'Welcome back <red>');
  await noSideways(page, `${where} behavior editor`);

  // JSON key completion from the capability's arguments.
  await showView(page, 'code');
  const area = page.locator('[data-testid="code-view"] textarea');
  await area.evaluate(element => {
    const at = element.value.indexOf('"amount": 1') + '"amount": 1'.length;
    element.focus();
    element.setSelectionRange(at, at);
  });
  await area.pressSequentially(', "pla');
  list = await listOf(page, area, `${where} JSON key`, false);
  assert.equal(await list.getByRole('option').first().locator('.animus-suggest-label').textContent(), 'player');
  await page.keyboard.press('Enter');
  assert.match(await area.inputValue(), /"amount": 1, "player": /);
  await page.keyboard.press('Escape');
  await page.locator('[data-testid="code-view"]').getByRole('button', {name: 'Discard'}).click();

  // A placeholder hole in a hologram line.
  await openFromSidebar(page, 'town:sign');
  await showView(page, 'visual');
  const line = page.getByLabel('Line 1', {exact: true});
  await line.focus();
  await line.press('End');
  await line.pressSequentially(' {vie');
  list = await listOf(page, line, `${where} placeholder`, phone);
  const hole = list.getByRole('option').filter({hasText: '{{ viewer.name }}'}).first();
  await hole.waitFor();
  while ((await line.getAttribute('aria-activedescendant')) !== (await hole.getAttribute('id'))) await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  await closed(page, line);
  assert.equal(await line.inputValue(), 'Town square {{ viewer.name }}');
  await noSideways(page, `${where} hologram editor`);

  assert.deepEqual(errors, [], `${where}: no console errors`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    for (const viewport of [{width: 1440, height: 900}, {width: 390, height: 844}]) {
      for (const theme of ['dark', 'light']) await run(browser, viewport, theme);
    }
    console.log('autocomplete_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
