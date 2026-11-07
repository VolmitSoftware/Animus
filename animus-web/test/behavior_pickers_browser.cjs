// Typed editors and pickers in place of hand-typed JSON and ids, against
// tool/fake_server.dart at 1440 and 390 px in both themes. Pairs, opens
// everything but the behavior town:guard-duty, then: an NPC module without
// a schema as key/value rows (JSON only behind Advanced); the behaviors
// picker with the server-only behavior in its own group; the shop picker;
// Edit route and Make a copy for this NPC (one undo step); a behavior's
// other document and trigger fields and a module capability's arguments as
// key/value rows; the prompt and option pickers of dialogue_choice
// triggers; and the link to the Conversation tab where the old read-only
// dialogue section was. No console errors and no sideways scrolling.
//
//   python3 tool/serve_site.py --port <web port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<web port> --data=test/fixtures/behavior_pickers
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<web port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/behavior_pickers_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openFromSidebar, sidebar, closeDrawer, topbarAction} = require('./support/shell.cjs');
const {choose, chooseAndWait, optionValues, openDropdown, valueOf, triggerOf} = require('./support/select.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;
const opened = ['town/baker', 'town-patrol', 'town:market', 'town:bakery', 'harbor:ferryman', 'town:greeter'];

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
  await page.getByRole('tab', {name: 'Code'}).click();
  const text = await page.locator('.animus-code textarea').inputValue();
  await page.getByRole('tab', {name: 'Visual'}).click();
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

/** Opens an inspector part (closed by default on phones) and returns it. */
async function part(page, id) {
  const details = page.locator(`details[data-section="${id}"]`);
  if (!(await details.evaluate(element => element.open))) await details.locator('summary').click();
  return details;
}

/** A 44 px tap target on phones. */
async function tall(locator, label, phone) {
  if (!phone) return;
  const box = await locator.boundingBox();
  assert.ok(box && box.height >= 43.5, `${label}: ${box && box.height}px tall`);
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
  await page.locator('[data-testid="pair-fingerprint"]', {hasText: 'sha256:'}).waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await page.locator('.animus-live .animus-pill[data-phase="online"]').waitFor({timeout: 20000});
  await page.getByText(/documents on the server/).waitFor();
  for (const id of opened) await page.getByRole('checkbox', {name: id, exact: true}).check();
  await page.getByRole('button', {name: `Open ${opened.length}`}).click();
  await page.locator('.animus-live-log li', {hasText: `Opened ${opened.length} documents`}).first().waitFor({state: 'attached'});

  // 1. A module without a schema: typed rows, JSON behind Advanced.
  await openFromSidebar(page, 'Baker');
  await page.getByRole('region', {name: 'NPC inspector'}).waitFor();
  const modules = await part(page, 'modules');
  const glow = modules.getByRole('group', {name: 'Module example:glow'});
  await glow.waitFor();
  assert.equal(await modules.locator('[data-module="example:glow"] textarea').count(), 0, 'no JSON until Advanced');
  assert.equal(await glow.getByRole('textbox', {name: 'Module example:glow key 1'}).inputValue(), 'color');
  await glow.getByRole('combobox', {name: 'color', exact: true}).fill('amber');
  await glow.getByRole('button', {name: 'Add field to Module example:glow'}).click();
  let json = await waitForCode(page, j => j.modules['example:glow'].color === 'amber' && j.modules['example:glow'].key === '', 'module rows');
  assert.equal(json.modules['example:glow'].strength, 2);
  const glowAgain = (await part(page, 'modules')).getByRole('group', {name: 'Module example:glow'});
  await glowAgain.getByRole('button', {name: 'Advanced'}).click();
  await glowAgain.locator('textarea').waitFor();
  await glowAgain.getByRole('button', {name: 'Advanced'}).click();
  await shot(page, `${label}-module`);

  // 3. Behaviors: one picker over the workspace and the server.
  const behaviour = await part(page, 'behaviour');
  const add = triggerOf(behaviour.locator('#animus-npc-add-behavior'));
  await tall(add, 'add behavior', phone);
  assert.deepEqual(await optionValues(add), ['', 'harbor:ferryman', 'town:guard-duty']);
  const listbox = await openDropdown(add);
  await listbox.getByText('On the server').waitFor();
  await listbox.locator('[data-action]').first().waitFor();
  await page.keyboard.press('Escape');
  await choose(add, 'town:guard-duty');
  json = await waitForCode(page, j => j.behaviors.join() === 'town:greeter,town:guard-duty', 'behavior added');
  assert.equal(await (await part(page, 'behaviour')).getByRole('button', {name: 'Add by id'}).count(), 0);

  // 3. Shop: a picker, then another shop from it.
  const shopAdd = triggerOf((await part(page, 'modules')).locator('#animus-npc-shop'));
  await chooseAndWait(shopAdd, 'town:market');
  const shop = triggerOf(page.locator('#animus-npc-shop'));
  assert.deepEqual(await optionValues(shop), ['town:bakery', 'town:market']);
  await chooseAndWait(shop, 'town:bakery');
  json = await waitForCode(page, j => j.modules['animus:shop'] && j.modules['animus:shop'].shop === 'town:bakery', 'shop picked');
  await shot(page, `${label}-pickers`);

  // 4. A referenced route: Edit route opens it; a copy is one undo step.
  await (await part(page, 'behaviour')).getByRole('button', {name: 'Edit route town-patrol'}).click();
  await page.waitForFunction(() => location.hash.includes('/route/town-patrol'));
  await page.goBack();
  await page.getByRole('region', {name: 'NPC inspector'}).waitFor();
  const behaviourAgain = await part(page, 'behaviour');
  const copy = behaviourAgain.getByRole('button', {name: 'Make a copy for this NPC'});
  await tall(copy, 'copy route', phone);
  await copy.click();
  json = await waitForCode(page, j => j.modules['animus:navigation'].route === 'baker-route', 'route copied');
  assert.equal(json.modules['animus:navigation'].enabled, true);
  await (await sidebar(page)).getByText('baker-route', {exact: true}).first().waitFor();
  await closeDrawer(page);
  await topbarAction(page, 'Undo');
  json = await waitForCode(page, j => j.modules['animus:navigation'].route === 'town-patrol', 'copy undone');
  assert.equal(await (await sidebar(page)).getByText('baker-route', {exact: true}).count(), 0, 'the copy is gone with the same undo');
  await closeDrawer(page);
  await (await part(page, 'behaviour')).getByRole('button', {name: 'Make a copy for this NPC'}).click();
  await waitForCode(page, j => j.modules['animus:navigation'].route === 'baker-route', 'route copied again');
  await openFromSidebar(page, 'baker-route');
  await page.getByRole('region', {name: 'Route'}).waitFor();
  const route = await codeOf(page);
  assert.equal(route.points.length, 3);
  assert.equal(route.order, 'random');
  await shot(page, `${label}-route-copy`);

  // 2. The behavior: other fields, unknown trigger fields and module
  // capability arguments as typed rows.
  await openFromSidebar(page, 'harbor:ferryman');
  await page.waitForFunction(() => location.hash.includes('view=conversation'));
  await page.getByRole('tab', {name: 'Visual'}).click();
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
  const prompt = triggerOf(pay.getByLabel('Prompt of trigger 4'));
  assert.deepEqual(await optionValues(prompt), ['ferry', 'fare']);
  await tall(prompt, 'prompt picker', phone);
  await chooseAndWait(prompt, 'ferry');
  json = await waitForCode(page, j => j.triggers[3].dialogue === 'ferry' && j.triggers[3].choice === undefined, 'prompt changed, choice dropped');
  const option = triggerOf(pay.getByLabel('Option of trigger 4'));
  assert.deepEqual(await optionValues(option), ['', 'yes', 'no']);
  await chooseAndWait(option, 'no');
  json = await waitForCode(page, j => j.triggers[3].choice === 'no', 'option picked');
  assert.equal(await valueOf(prompt), 'ferry');
  await shot(page, `${label}-dialogue-pickers`);

  // 5. The link to the Conversation tab.
  const link = editor.getByRole('button', {name: 'Edit in the Conversation tab'});
  await tall(link, 'conversation link', phone);
  await link.click();
  await page.waitForFunction(() => location.hash.includes('view=conversation'));
  await page.locator('.animus-conv').waitFor();
  await openFromSidebar(page, 'town:greeter');
  await page.getByRole('tab', {name: 'Visual'}).click();
  await page.getByRole('region', {name: 'Behavior editor'}).waitFor();
  assert.equal(await page.getByRole('button', {name: 'Edit in the Conversation tab'}).count(), 0, 'no link without a conversation');
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
