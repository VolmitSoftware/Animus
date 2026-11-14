// Opens ../schema/fixtures/export in the built site at desktop and phone
// widths, in the dark and light themes, in Advanced mode, and checks the NPC
// form (the Name tip and its Docs page, a name edit and a workspace behavior
// added on Talk and trade), the behavior Steps view (a trigger field, a step's type
// changed, steps added from the "Add step" menus, a list variable with
// items, a while with an expression, a foreach over the list, a set filled
// from an expression, a template placeholder through "Fill from", a new
// action step valid as added, a step folded by keyboard, a step's "..."
// menu, the server switch changing the "Add trigger" menu), the Test pane
// running the loop, the server behaviors list (created, an entry removed
// through its menu and added again), and the code view (key, value and
// expression completion, diagnostics mapped to a gutter line that jumps, a
// JSON syntax error placed by line), with no console errors and no
// sideways scrolling.
//
//   python3 tool/serve_site.py --port <port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/inspectors_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openFromSidebar, pickOption, showView} = require('./support/shell.cjs');
const {choose, chooseAndWait, dropdownShowing, dropdownWithOption} = require('./support/select.cjs');
const {npcTab} = require('./support/npc_tabs.cjs');

const exportFolder = path.resolve(__dirname, '../../schema/fixtures/export');
const shots = process.env.ANIMUS_SCREENSHOTS;

async function shot(page, name) {
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: true});
}

async function open(page, sidebarText) {
  await openFromSidebar(page, sidebarText);
  await page.locator('.ui-page-header').waitFor();
}

async function codeOf(page) {
  await showView(page, 'code');
  const text = await page.locator('.animus-code textarea').inputValue();
  await showView(page, 'visual');
  return JSON.parse(text);
}

/// A dropdown holding an option with [text]: the one showing it as its
/// placeholder, or else the first whose options include it.
async function selectWith(scope, text) {
  const shown = dropdownShowing(scope, text);
  if (await shown.count()) return shown;
  return dropdownWithOption(scope, text);
}

/// The tip text and Docs address of the (i) button beside the label or
/// heading [text] inside [scope]. The Docs link is drawn while the tip is
/// open: a click pins it (a tip never opens on programmatic focus) and
/// Escape closes it.
async function tipOf(scope, text) {
  const holder = scope.locator('[data-slot="field-label"], .animus-tip-label').filter({hasText: text}).first();
  const wrap = holder.locator('.animus-info').first();
  const button = wrap.locator('[data-animus-info]');
  const tip = {text: (await scope.page().locator(`[id="${await button.getAttribute('aria-describedby')}"]`).textContent()).trim()};
  await button.click();
  const docs = wrap.locator('.animus-info-tip a[href]');
  await docs.waitFor();
  tip.docs = await docs.getAttribute('href');
  await scope.page().keyboard.press('Escape');
  await docs.waitFor({state: 'detached'});
  return tip;
}

/// Waits until the page has not changed for a quarter second: an open menu
/// closes when an edit's rebuild lands on it.
async function settle(page) {
  await page.evaluate(() => new Promise(resolve => {
    let last = performance.now();
    const observer = new MutationObserver(() => { last = performance.now(); });
    observer.observe(document.body, {subtree: true, childList: true, attributes: true, characterData: true});
    const started = performance.now();
    const check = () => {
      if (performance.now() - last >= 250 || performance.now() - started > 5000) {
        observer.disconnect();
        resolve();
      } else {
        setTimeout(check, 50);
      }
    };
    setTimeout(check, 50);
  }));
}

/// The open menu holding [item].
function menuWith(page, item) {
  return page.getByRole('menu').filter({has: page.getByRole('menuitem', {name: item, exact: true})});
}

/// Opens the menu of [trigger] and picks [item].
async function pickMenu(page, trigger, item) {
  await settle(page);
  await trigger.click();
  const menu = menuWith(page, item);
  await menu.waitFor();
  await menu.getByRole('menuitem', {name: item, exact: true}).click();
  await menu.waitFor({state: 'hidden'});
}

/// The item names of [trigger]'s menu, closing it again with Escape once
/// focus is inside it.
async function menuItems(page, trigger) {
  await settle(page);
  await trigger.click();
  const menu = page.getByRole('menu').first();
  await menu.waitFor();
  const names = await menu.getByRole('menuitem').allInnerTexts();
  await page.waitForFunction(() => Boolean(document.activeElement && document.activeElement.closest('[role="menu"]')), null, {timeout: 3000});
  await page.keyboard.press('Escape');
  await menu.waitFor({state: 'hidden'});
  return names.map(name => name.split('\n')[0].trim());
}

/// Runs the Test in [pane] from [label]: the Starts dropdown picks it when
/// there is more than one start, then Run plays it from the start.
async function pickStart(page, pane, label) {
  const run = pane.getByRole('button', {name: 'Run', exact: true});
  await run.waitFor();
  const starts = pane.getByRole('combobox', {name: 'Starts'});
  if (await starts.count()) await pickOption(page, starts, label);
  await run.click();
}

/// Shows a behavior's Steps view (a conversation opens on its Conversation view).
async function steps(page) {
  await showView(page, 'visual');
  await page.evaluate(() => { location.hash = `${location.hash.split('?')[0]}?view=visual`; });
  await page.locator('.animus-behavior-editor').waitFor();
}

async function caretAt(page, needle, after) {
  const area = page.locator('.animus-code textarea');
  await area.evaluate((element, [needle, after]) => {
    const at = element.value.indexOf(needle) + after;
    element.focus();
    element.setSelectionRange(at, at);
  }, [needle, after]);
  return area;
}

async function noOverflow(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  if (overflow <= 1) return;
  const wide = await page.evaluate(() => {
    const limit = document.documentElement.clientWidth;
    return Array.from(document.querySelectorAll('body *'))
      .map(element => [element, element.getBoundingClientRect().left + Math.max(element.scrollWidth, element.getBoundingClientRect().width)])
      .filter(([, right]) => right > limit + 1)
      .sort((a, b) => b[1] - a[1]).slice(0, 6)
      .map(([element, right]) => `${element.tagName.toLowerCase()}.${element.className} [${element.getAttribute('aria-label') || ''}] in ${(element.parentElement.closest('[data-node-path],[data-variable],[data-routine],.animus-gui-row') || {className: '-'}).className} reaches ${Math.round(right)}`);
  });
  const chain = await page.evaluate(() => {
    const limit = document.documentElement.clientWidth;
    const input = Array.from(document.querySelectorAll('input, textarea, button[role="combobox"]')).find(element => element.getBoundingClientRect().right > limit + 1);
    const out = [];
    for (let node = input; node && out.length < 14; node = node.parentElement) {
      const rect = node.getBoundingClientRect();
      out.push(`${node.tagName.toLowerCase()}.${String(node.className).split(' ')[0]} l=${Math.round(rect.left)} w=${Math.round(rect.width)}`);
    }
    return out;
  });
  if (process.env.ANIMUS_DEBUG) await shot(page, 'overflow-debug');
  assert.fail(`${where}: ${overflow}px of sideways scrolling, from ${wide.join('; ')}${process.env.ANIMUS_DEBUG ? `\n${chain.join('\n')}` : ''}`);
}

async function run(browser, viewport, tag, theme) {
  const context = await browser.newContext({viewport});
  await context.addInitScript(mode => {
    window.localStorage.setItem('animus.theme', mode);
    window.localStorage.setItem('animus.mode', 'advanced');
  }, theme);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })());
  await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(exportFolder);
  await page.getByRole('heading', {name: 'Needs attention (1)'}).waitFor();

  // NPC form: the Name field's tip, a name edit, a workspace behavior added.
  await open(page, 'Greeter');
  await showView(page, 'visual');
  const inspector = page.locator('[data-npc="form"]');
  await inspector.waitFor();
  const basics = await npcTab(page, 'Basics');
  // The Name label's (i) holds the tip, with a Docs link to the NPC page.
  const info = basics.locator('[data-slot="field"]', {has: page.locator('label[for="animus-npc-name"]')}).locator('[data-animus-info]');
  assert.equal(await page.locator(`[id="${await info.getAttribute('aria-describedby')}"]`).textContent(), 'Shown above the NPC. & codes such as &a set the colour.');
  await info.click();
  const docs = page.locator('.animus-info-tip a[href]:visible').first();
  await docs.waitFor();
  assert.equal(await docs.getAttribute('href'), 'https://docs.volmitsoftware.com/animus/04-npc-definitions#names-and-text-lines');
  await page.keyboard.press('Escape');
  const name = page.locator('#animus-npc-name');
  await name.fill('Greeter Edited');
  await settle(page);
  // Behaviors sit on the Talk and trade tab.
  const talk = await npcTab(page, 'Talk and trade');
  await choose(await selectWith(talk, 'Add another'), 'shop:keeper');
  await settle(page);
  await shot(page, `${tag}-npc-inspector`);
  let json = await codeOf(page);
  assert.equal(json.name, 'Greeter Edited');
  assert.deepEqual(json.behaviors, ['greeter:greeter', 'shop:keeper']);
  assert.equal(json.uuid, 'fe531426-31bc-5660-9207-b3d3078c2305', 'untouched fields survive');

  // Code view key completion on the NPC.
  await showView(page, 'code');
  let area = await caretAt(page, '{', 1);
  await area.pressSequentially('\n  "sk');
  const skin = page.getByRole('option').filter({hasText: 'skin'}).first();
  await skin.waitFor();
  await shot(page, `${tag}-code-key-completion`);
  await area.press('Enter');
  assert.match(await area.inputValue(), /"skin": /);
  await page.locator('[data-testid="code-view"]').getByRole('button', {name: 'Discard'}).click();

  // Behavior Steps view.
  await open(page, 'greeter:greeter');
  await steps(page);
  const editor = page.locator('.animus-behavior-editor');
  await noOverflow(page, `${tag} behavior editor as opened`);
  const trigger = editor.locator('[data-node-path="$.triggers[1]"]');
  await trigger.getByLabel('Radius', {exact: true}).fill('8');
  await settle(page);
  const step = editor.locator('[data-node-path="$.routines.wave.steps[1]"]');
  await chooseAndWait(step.getByRole('combobox', {name: 'Type of step 2'}), 'wait');
  await step.getByLabel('Ticks', {exact: true}).waitFor();
  const wave = editor.locator('[data-routine="wave"]');
  // The part's own "Add step" is its last: a loop's steps have their own.
  // Menu buttons with an icon carry the icon's glyph in their name.
  const addStep = () => wave.getByRole('button', {name: /Add step$/}).last();
  await pickMenu(page, addStep(), 'Run part');
  await editor.locator('[data-node-path="$.routines.wave.steps[2]"]').waitFor();
  // The say step names its capability in words and says it is not checked offline.
  const say = editor.locator('[data-node-path="$.routines.main.steps[1].then"]');
  await say.getByRole('combobox', {name: 'What this step does'}).filter({hasText: /^Say \(unavailable offline\)/}).waitFor();
  await settle(page);
  await shot(page, `${tag}-behavior-editor`);
  json = await codeOf(page);
  assert.equal(json.triggers[1].radius, 8);
  assert.deepEqual(json.routines.wave.steps[1], {type: 'wait', ticks: 1});
  assert.deepEqual(json.routines.wave.steps[2], {type: 'call', routine: 'main'});
  assert.equal(json.routines.main.steps[1].then.arguments.text, 'Good to see you again.');

  // Denizen v2 parts: a list variable with items, loops, expressions, a placeholder.
  await steps(page);
  const variables = editor.locator('.ui-section').filter({has: page.locator('.ui-section-title', {hasText: /^Variables/})}).first();
  const variablesTip = await tipOf(variables, 'Variables');
  assert.equal(variablesTip.docs, 'https://docs.volmitsoftware.com/animus/07-behaviors#variables');
  if (!(await variables.locator('details').first().evaluate(element => element.open))) await variables.locator('summary').first().click();
  await pickMenu(page, variables.getByRole('button', {name: /Add variable$/}), 'True or false');
  const flag = editor.locator('[data-variable="flag"]');
  await flag.waitFor();
  await flag.getByLabel('Variable 1 name').fill('names');
  await editor.locator('[data-variable="names"]').waitFor();
  const names = editor.locator('[data-variable="names"]');
  await chooseAndWait(names.getByRole('combobox', {name: 'Type of names'}), 'list');
  await names.getByRole('button', {name: /^Add (an )?item/}).click();
  await names.getByLabel('Starting value of names item 1', {exact: true}).fill('Alex');
  await settle(page);
  await pickMenu(page, addStep(), 'Repeat while');
  const loop = editor.locator('[data-node-path="$.routines.wave.steps[3]"]');
  const expression = loop.getByLabel('Expression of step 4');
  await expression.fill('len(na');
  await loop.getByRole('option').filter({hasText: 'names'}).first().waitFor();
  await expression.press('Enter');
  await expression.pressSequentially(') < 1');
  await settle(page);
  assert.equal(await loop.locator('[id^="animus-expr-"][id$="-problem"]').count(), 0, 'the while expression checks clean');
  await expression.fill('len(names) <');
  await loop.locator('[id^="animus-expr-"][id$="-problem"]').first().waitFor();
  await expression.fill('len(names) < 1');
  await pickMenu(page, addStep(), 'For each');
  const each = editor.locator('[data-node-path="$.routines.wave.steps[4]"]');
  await each.waitFor();
  await pickMenu(page, addStep(), 'Set variable');
  const set = editor.locator('[data-node-path="$.routines.wave.steps[5]"]');
  await chooseAndWait(set.getByRole('combobox', {name: 'Variable of step 6'}), 'item');
  // A value written as is offers "Fill from"; picking one shows its source.
  await pickMenu(page, set.locator('[data-fill-from="Value of step 6"]'), 'From an expression');
  await set.getByLabel('Value of step 6 expression').fill('len(names)');
  await settle(page);
  const greet = editor.locator('[data-node-path="$.routines.main.steps[1].else.steps[0]"]');
  await pickMenu(page, greet.locator('[data-argument="text"]').getByRole('button', {name: 'Fill from'}), 'From a template');
  await greet.getByLabel('Text template').fill('Hi {{ player.name }}');
  await settle(page);
  await shot(page, `${tag}-behavior-v2`);
  await noOverflow(page, `${tag} behavior editor`);
  json = await codeOf(page);
  assert.deepEqual(json.variables.names, {type: 'list', initial: ['Alex']});
  assert.deepEqual(json.routines.wave.steps[3], {type: 'while', expression: 'len(names) < 1', body: {type: 'sequence', steps: []}});
  assert.deepEqual(json.routines.wave.steps[4].list, {variable: 'names'});
  assert.equal(json.routines.wave.steps[4].as, 'item');
  assert.equal(json.variables.item.type, 'any', 'the foreach declared its item');
  assert.deepEqual(json.routines.wave.steps[5], {type: 'set', variable: 'item', value: {expression: 'len(names)'}});
  assert.equal(json.routines.main.steps[1].else.steps[0].arguments.text, null);
  assert.deepEqual(json.routines.main.steps[1].else.steps[0].templates, {'/text': 'Hi {{ player.name }}'});

  // A new action step is valid as added: it says a line, no empty required text.
  await steps(page);
  await pickMenu(page, addStep(), 'Do');
  await editor.locator('[data-node-path="$.routines.wave.steps[6]"]').waitFor();
  await settle(page);
  json = await codeOf(page);
  assert.deepEqual(json.routines.wave.steps[6], {type: 'action', capability: 'animus:say', arguments: {text: 'Hello, {player_name}.'}});
  await page.locator('.ui-page-header').getByText('Valid', {exact: true}).waitFor();

  // Keyboard: Enter on a step's header folds it and opens it again; its
  // "..." menu moves, duplicates and deletes it in words.
  await steps(page);
  const first = editor.locator('[data-node-path="$.routines.wave.steps[0]"] > details');
  await first.locator(':scope > summary').focus();
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => !document.querySelector('[data-node-path="$.routines.wave.steps[0]"] > details').open);
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => document.querySelector('[data-node-path="$.routines.wave.steps[0]"] > details').open);
  const actions = await menuItems(page, step.getByRole('toolbar', {name: 'Step 2'}).getByRole('button', {name: 'More actions'}));
  assert.deepEqual(actions, ['Move up', 'Move down', 'Duplicate', 'Delete']);

  // Test of the loop: one pass per item.
  const pane = page.locator('[data-testid="test-pane"]');
  const reveal = page.locator('.ui-page-header').getByRole('button', {name: 'Test', exact: true});
  if (!(await pane.isVisible().catch(() => false)) && await reveal.isVisible().catch(() => false)) await reveal.click();
  await pickStart(page, pane, 'Player nearby');
  const chat = pane.getByRole('list', {name: 'Chat'});
  await chat.getByText('Item 1 of 1: item is "Alex".').waitFor();
  await chat.getByText('The loop does not run: its expression is false.').waitFor();
  await chat.getByText('Sets item to 1.').waitFor();
  await shot(page, `${tag}-dry-run-loop`);
  await noOverflow(page, `${tag} dry run`);

  // The server switch swaps NPC events for server events.
  await steps(page);
  const addTrigger = () => editor.getByRole('button', {name: /Add trigger$/});
  let events = await menuItems(page, addTrigger());
  assert.ok(events.includes('On click'), `an NPC behavior offers On click: ${events}`);
  assert.ok(!events.includes('Player joins'), 'an NPC behavior hides server events');
  await editor.getByLabel('Run on the server').click();
  await settle(page);
  events = await menuItems(page, addTrigger());
  assert.ok(events.includes('Player joins'), `a server behavior offers Player joins: ${events}`);
  assert.ok(!events.includes('On click'), 'a server behavior hides NPC events');
  await pickMenu(page, addTrigger(), 'Player joins');
  await editor.locator('[data-event="player_join"]').waitFor();
  await editor.locator('[data-event="interaction"] .animus-node-problem[data-problem="error"]').first().waitFor();
  await shot(page, `${tag}-server-switch`);
  json = await codeOf(page);
  assert.equal(json.server, true);
  assert.equal(json.triggers.at(-1).event, 'player_join');

  // The server behaviors list: created with the workspace's server behaviors, then edited.
  const workspace = await page.evaluate(() => decodeURIComponent(location.hash.split('/')[2]));
  await page.goto(`${process.env.ANIMUS_WEB_URL}/#/workspace/${encodeURIComponent(workspace)}/new/server-behaviors`);
  await page.getByRole('heading', {name: 'New server behaviors list'}).waitFor();
  await page.getByText('Starts with 1 server behavior.').waitFor();
  await page.locator('#new-create').click();
  await page.waitForFunction(() => /\/server-behaviors\/server-behaviors$/.test(location.hash.split('?')[0]));
  await page.locator('#animus-main-heading').getByText('Server behaviors', {exact: true}).waitFor();
  await showView(page, 'visual');
  const list = page.locator('.animus-server-behaviors');
  await list.waitFor();
  const row = list.locator('[data-behavior="greeter:greeter"]');
  await row.waitFor();
  assert.equal((await tipOf(list, 'Server behaviors')).docs, 'https://docs.volmitsoftware.com/animus/07-behaviors#server-behaviors');
  await pickMenu(page, row.getByRole('button', {name: 'More actions'}), 'Remove from list');
  await list.getByText('Nothing listed yet.').waitFor();
  await choose(list.getByRole('combobox', {name: 'Add a server behavior'}), 'greeter:greeter');
  await row.waitFor();
  await shot(page, `${tag}-server-behaviors`);
  await noOverflow(page, `${tag} server behaviors`);
  json = await codeOf(page);
  assert.deepEqual(json, {schemaVersion: 1, behaviors: ['greeter:greeter']});
  await open(page, 'greeter:greeter');

  // Code view: expression completion and a JSON syntax error placed by line.
  await showView(page, 'code');
  const code = page.locator('[data-testid="code-view"]');
  let codeArea = await caretAt(page, '"expression": "len(names) < 1"', 19);
  await codeArea.pressSequentially(' && na');
  await page.getByRole('option').filter({hasText: 'names'}).first().waitFor();
  await shot(page, `${tag}-code-expression-completion`);
  await codeArea.press('Escape');
  const broken = (await codeArea.inputValue()).replace('"entry": "main",', '"entry": "main"');
  await codeArea.fill(broken);
  await code.locator('[data-testid="code-syntax"]').getByText(/expected a comma or \} after the value/).waitFor();
  await code.locator('[data-testid="code-syntax"]').getByRole('button', {name: /^Line \d+$/}).waitFor();
  assert.ok(await page.locator('.animus-code-line.has-mark.is-error').count() >= 1, 'the gutter marks the broken line');
  assert.equal(await code.locator('[data-testid="code-use"]').isDisabled(), true, 'broken code cannot be used');
  await shot(page, `${tag}-code-syntax`);
  await code.getByRole('button', {name: 'Discard'}).click();
  await steps(page);
  await editor.getByLabel('Run on the server').click();
  await settle(page);

  // Code view value completion and diagnostics by line.
  await showView(page, 'code');
  area = await caretAt(page, '"phase": "', 10);
  await area.press('Control+Space');
  const leave = page.getByRole('option').filter({hasText: 'leave'}).first();
  await leave.waitFor();
  await shot(page, `${tag}-code-value-completion`);
  await leave.click();
  let text = await area.inputValue();
  assert.match(text, /"phase": "leave"/);
  await area.fill(text.replace('"radius": 8', '"radius": 100'));
  await code.locator('[data-testid="code-use"]').click();
  const problem = code.locator('.animus-code-problem').filter({hasText: '$.triggers[1].radius'}).first();
  await problem.waitFor();
  assert.ok(await page.locator('.animus-code-line.has-mark.is-error').count() >= 1, 'the gutter marks the line');
  await problem.getByRole('button').first().click();
  text = await area.inputValue();
  const caret = await area.evaluate(element => element.selectionStart);
  const lineText = text.slice(caret, text.indexOf('\n', caret));
  assert.match(lineText, /"radius"/);
  await shot(page, `${tag}-code-diagnostics`);

  assert.deepEqual(errors, []);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    for (const theme of ['dark', 'light']) {
      await run(browser, {width: 1440, height: 900}, `desktop-${theme}`, theme);
      await run(browser, {width: 390, height: 844}, `phone-${theme}`, theme);
    }
    console.log('inspectors_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
