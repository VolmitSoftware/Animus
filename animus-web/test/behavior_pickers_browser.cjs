// Typed editors and pickers in place of hand-typed JSON and ids, against
// tool/fake_server.dart at 1440 and 390 px in both themes. Pairs, opens
// everything but the behavior town:guard-duty, then: an NPC module without
// a schema as key/value rows (JSON only behind Advanced); the behaviors
// picker with the server-only behavior in its own group; the shop picker;
// Edit route and Copy for this NPC (one undo step); a behavior's
// other document and trigger fields and a module capability's arguments as
// key/value rows; the question and answer pickers of dialogue_choice
// triggers; the Conversation view from the view switch; and a behavior that
// is not a conversation read only in Simple, with Edit in Advanced. No
// console errors and no sideways scrolling.
//
//   python3 tool/serve_site.py --port <web port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<web port> --data=test/fixtures/behavior_pickers
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<web port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/behavior_pickers_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openFromSidebar, sidebar, closeDrawer, topbarAction, showView, setMode} = require('./support/shell.cjs');
const {choose, chooseAndWait, optionValues, valueOf, triggerOf} = require('./support/select.cjs');
const {npcTab} = require('./support/npc_tabs.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;
const baker = 'town/baker';

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

async function shot(page, name) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 1, `${name}: ${overflow}px of sideways scrolling`);
  assert.equal(await page.locator('main select, main datalist').count(), 0, `${name}: a native select`);
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

/** Waits until the document's Code view satisfies [check]. */
async function waitForCode(page, check, what) {
  const deadline = Date.now() + 10000;
  for (;;) {
    const json = await codeOf(page);
    if (check(json)) return json;
    if (Date.now() > deadline) throw new Error(`${what}: ${JSON.stringify(json)}`);
    await page.waitForTimeout(100);
  }
}

/** On phones a control keeps one of the library's heights (36 or 32 px):
 * never squeezed below them. */
async function tall(locator, label, phone) {
  if (!phone) return;
  const box = await locator.boundingBox();
  assert.ok(box && box.height >= 31.5, `${label}: ${box && box.height}px tall`);
}

async function run(browser, viewport, label, theme) {
  const phone = viewport.width < 500;
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
  await page.locator('[data-testid="context-state"]', {hasText: 'Online'}).waitFor({timeout: 20000});
  await page.locator('[data-testid="home-count"]').first().waitFor();
  // Modules is an Advanced tab of the NPC.
  await setMode(page, 'advanced');
  // The workspace fills from the catalog by itself; open the first NPC by its address.
  await page.evaluate((id) => { location.hash = location.hash.replace(/^#\/server\/([^/]+).*$/, `#/workspace/server-$1/npc/${encodeURIComponent(id)}`); }, baker);
  await page.waitForFunction(() => location.hash.includes('/npc/'));

  // 1. A module without a schema: typed rows, JSON behind Advanced.
  await openFromSidebar(page, 'Baker');
  await page.locator('.animus-npc-tabs').waitFor();
  const modules = await npcTab(page, 'Modules');
  // A module no form knows is one closed row; open it (again after the code
  // view, which rebuilds the tab).
  const openGlow = async () => {
    const details = (await npcTab(page, 'Modules')).locator('[data-module="example:glow"] details').first();
    if (!(await details.evaluate(element => element.open))) await details.locator(':scope > summary').click();
  };
  await openGlow();
  const glow = modules.getByRole('group', {name: 'Module example:glow'});
  await glow.waitFor();
  assert.equal(await modules.locator('[data-module="example:glow"] textarea').count(), 0, 'no JSON until Advanced');
  assert.equal(await glow.getByRole('textbox', {name: 'Module example:glow key 1'}).inputValue(), 'color');
  await glow.getByRole('combobox', {name: 'color', exact: true}).fill('amber');
  await glow.getByRole('button', {name: 'Add field to Module example:glow'}).click();
  let json = await waitForCode(page, j => j.modules['example:glow'].color === 'amber' && j.modules['example:glow'].key === '', 'module rows');
  assert.equal(json.modules['example:glow'].strength, 2);
  await openGlow();
  const glowAgain = (await npcTab(page, 'Modules')).getByRole('group', {name: 'Module example:glow'});
  await glowAgain.getByRole('button', {name: 'Edit as JSON'}).click();
  await glowAgain.locator('textarea').waitFor();
  await glowAgain.getByRole('button', {name: 'Edit as JSON'}).click();
  await shot(page, `${label}-module`);

  // 3. Behaviors: one picker over the workspace and the server.
  const behaviour = await npcTab(page, 'Talk and trade');
  const add = triggerOf(behaviour.locator('#animus-npc-add-behavior'));
  await tall(add, 'add behavior', phone);
  assert.deepEqual(await optionValues(add), ['', 'harbor:ferryman', 'town:guard-duty']);
  // Every server behavior is in the workspace once the catalog is read in,
  // so none is listed apart as only on the server.
  await choose(add, 'town:guard-duty');
  json = await waitForCode(page, j => j.behaviors.join() === 'town:greeter,town:guard-duty', 'behavior added');
  assert.equal(await (await npcTab(page, 'Talk and trade')).getByRole('button', {name: 'Add by id'}).count(), 0);

  // 3. Shop: a picker, then another shop from it.
  const shopAdd = triggerOf((await npcTab(page, 'Talk and trade')).locator('#animus-npc-shop'));
  await chooseAndWait(shopAdd, 'town:market');
  const shop = triggerOf(page.locator('#animus-npc-shop'));
  assert.deepEqual(await optionValues(shop), ['', 'town:bakery', 'town:market']);
  await chooseAndWait(shop, 'town:bakery');
  json = await waitForCode(page, j => j.modules['animus:shop'] && j.modules['animus:shop'].shop === 'town:bakery', 'shop picked');
  await shot(page, `${label}-pickers`);

  // 4. A referenced route: Edit route opens it; a copy is one undo step.
  await (await npcTab(page, 'Movement')).getByRole('button', {name: 'Edit route town-patrol'}).click();
  await page.waitForFunction(() => location.hash.includes('/route/town-patrol'));
  await page.goBack();
  await page.locator('.animus-npc-tabs').waitFor();
  const movement = await npcTab(page, 'Movement');
  const copy = movement.getByRole('button', {name: 'Copy for this NPC'});
  await tall(copy, 'copy route', phone);
  await copy.click();
  json = await waitForCode(page, j => j.modules['animus:navigation'].route === 'baker-route', 'route copied');
  assert.equal(json.modules['animus:navigation'].enabled, true);
  await (await sidebar(page)).getByText('Baker route', {exact: true}).first().waitFor();
  await closeDrawer(page);
  await topbarAction(page, 'Undo');
  json = await waitForCode(page, j => j.modules['animus:navigation'].route === 'town-patrol', 'copy undone');
  assert.equal(await (await sidebar(page)).getByText('Baker route', {exact: true}).count(), 0, 'the copy is gone with the same undo');
  await closeDrawer(page);
  await (await npcTab(page, 'Movement')).getByRole('button', {name: 'Copy for this NPC'}).click();
  await waitForCode(page, j => j.modules['animus:navigation'].route === 'baker-route', 'route copied again');
  await openFromSidebar(page, 'baker-route');
  await page.locator('.animus-route-document').waitFor();
  const route = await codeOf(page);
  assert.equal(route.points.length, 3);
  assert.equal(route.order, 'random');
  await shot(page, `${label}-route-copy`);

  // 2. The behavior: other fields, unknown trigger fields and module
  // capability arguments as typed rows.
  await openFromSidebar(page, 'harbor:ferryman');
  await page.waitForFunction(() => location.hash.includes('view=conversation'));
  await showView(page, 'visual');
  const editor = page.getByRole('region', {name: 'Behavior editor'});
  await editor.waitFor();
  assert.equal(await editor.locator('.animus-dialogue').count(), 0, 'the old dialogue section is gone');
  const other = editor.getByRole('group', {name: 'Other fields', exact: true});
  await other.getByRole('combobox', {name: 'notes', exact: true}).fill('Harbor team');
  json = await waitForCode(page, j => j.notes === 'Harbor team', 'document other field');
  const triggerOther = editor.getByRole('group', {name: 'Other fields of triggers[0]'});
  await triggerOther.getByRole('spinbutton', {name: 'legacy'}).fill('5');
  json = await waitForCode(page, j => j.triggers[0].legacy === 5, 'trigger other field');
  const wave = editor.getByRole('group', {name: 'Arguments', exact: true});
  await wave.getByRole('combobox', {name: 'style', exact: true}).fill('small');
  json = await waitForCode(page, j => j.routines.decline.steps[1].arguments.style === 'small', 'module capability argument');
  await shot(page, `${label}-behavior-fields`);

  // 3. dialogue_choice triggers: prompts and options from this behavior.
  const pay = editor.locator('[data-node-path="$.triggers[3]"]');
  const prompt = triggerOf(pay.getByLabel('Question of trigger 4'));
  assert.deepEqual(await optionValues(prompt), ['ferry', 'fare']);
  await tall(prompt, 'prompt picker', phone);
  await chooseAndWait(prompt, 'ferry');
  json = await waitForCode(page, j => j.triggers[3].dialogue === 'ferry' && j.triggers[3].choice === undefined, 'prompt changed, choice dropped');
  const option = triggerOf(pay.getByLabel('Answer of trigger 4'));
  assert.deepEqual(await optionValues(option), ['', 'yes', 'no']);
  await chooseAndWait(option, 'no');
  json = await waitForCode(page, j => j.triggers[3].choice === 'no', 'option picked');
  assert.equal(await valueOf(prompt), 'ferry');
  await shot(page, `${label}-dialogue-pickers`);

  // 5. The second answer to ferry/no made above means the behavior no
  // longer fits a conversation: the view switch stops offering the
  // Conversation view, and its address says why, with the path in
  // Advanced only.
  // The header is one line: on a phone the view switch folds into a dropdown.
  const views = page.locator('.ui-page-header').getByRole('group', {name: 'View'});
  const viewSelect = page.locator('.ui-page-header').getByRole('combobox', {name: 'View'});
  await views.getByRole('button', {name: 'Steps', exact: true}).or(viewSelect).first().waitFor();
  if (await viewSelect.count()) {
    await viewSelect.click();
    const list = page.locator(`[id="${await viewSelect.getAttribute('aria-controls')}"]`);
    assert.equal(await list.getByRole('option', {name: 'Conversation', exact: true}).count(), 0, 'no Conversation view for a behavior that does not fit');
    await page.keyboard.press('Escape');
  } else {
    assert.equal(await views.getByRole('button', {name: 'Conversation', exact: true}).count(), 0, 'no Conversation view for a behavior that does not fit');
  }
  await showView(page, 'conversation');
  await page.waitForFunction(() => location.hash.includes('view=conversation'));
  const misfit = page.locator('.animus-conv').getByText(/A second answer to ferry\/no/);
  await misfit.waitFor();
  assert.match(await misfit.innerText(), /\(\$\.triggers\[3\]\) Edit it in Steps\./);
  await setMode(page, 'simple');
  await page.locator('[data-testid="behavior-read"]').waitFor();
  assert.equal(await page.locator('.animus-conv').count(), 0, 'Simple reads a behavior that no longer fits as sentences');

  // A behavior that is not a conversation reads as sentences in Simple,
  // changes nothing there, and Edit in Advanced opens its steps.
  await openFromSidebar(page, 'town:greeter');
  const read = page.locator('[data-testid="behavior-read"]');
  await read.waitFor();
  assert.equal(await page.locator('.animus-conv').count(), 0, 'not drawn as a conversation');
  assert.equal(await read.locator('input, textarea, button[role="combobox"]').count(), 0, 'nothing to edit in Simple');
  assert.doesNotMatch(await read.innerText(), /\$\.|routines|animus:/, 'no paths or engine ids in Simple');
  await shot(page, `${label}-read-only`);
  await page.locator('[data-testid="edit-in-advanced"]').click();
  await page.waitForFunction(() => window.localStorage.getItem('animus.mode') === 'advanced');
  await page.getByRole('region', {name: 'Behavior editor'}).waitFor();
  await shot(page, `${label}-no-conversation`);

  assert.deepEqual(errors, [], `${label}: console errors`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    for (const theme of ['dark', 'light']) {
      await run(browser, {width: 1440, height: 900}, `pickers-desktop-${theme}`, theme);
      await run(browser, {width: 390, height: 844}, `pickers-phone-${theme}`, theme);
    }
    console.log('behavior_pickers_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
