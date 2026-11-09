// Opens ../schema/fixtures/export in the built site at desktop and phone
// widths, in the dark and light themes, and checks the NPC inspector (a
// field edit and a workspace behavior added), the behavior node editor (a
// trigger field, a node type change, a new step, a list variable with
// items, a while with an expression, a foreach over the list, a set from an
// expression, a template placeholder argument, the server switch changing
// the trigger list, keyboard moves in the tree), the server behaviors list
// (created, an entry added), a dry run of a loop, and the code view (key,
// value and expression completion, diagnostics mapped to a gutter line that
// jumps, a JSON syntax error placed by line), with no console errors and no
// sideways scrolling.
//
//   python3 tool/serve_site.py --port <port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/inspectors_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openFromSidebar} = require('./support/shell.cjs');
const {choose, chooseAndWait, dropdownShowing, dropdownWithOption, optionValues} = require('./support/select.cjs');

const exportFolder = path.resolve(__dirname, '../../schema/fixtures/export');
const shots = process.env.ANIMUS_SCREENSHOTS;

async function shot(page, name) {
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: true});
}

async function open(page, sidebarText) {
  await openFromSidebar(page, sidebarText);
  await page.locator('.animus-document-header').waitFor();
}

async function codeOf(page) {
  await page.getByRole('tab', {name: 'Code'}).click();
  const text = await page.locator('.animus-code textarea').inputValue();
  await page.getByRole('tab', {name: 'Visual'}).click();
  return JSON.parse(text);
}

/// A dropdown holding an option with [text]: the one showing it as its
/// placeholder, or else the first whose options include it.
async function selectWith(scope, text) {
  const shown = dropdownShowing(scope, text);
  if (await shown.count()) return shown;
  return dropdownWithOption(scope, text);
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
  await context.addInitScript(mode => window.localStorage.setItem('animus.theme', mode), theme);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })());
  await page.getByRole('heading', {name: 'From a data folder'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(exportFolder);
  await page.getByRole('heading', {name: '1 document needs repair'}).waitFor();

  // NPC inspector: help from the schema, a name edit, a workspace behavior added.
  await open(page, 'Greeter');
  await page.getByRole('tab', {name: 'Visual'}).click();
  const inspector = page.getByRole('region', {name: 'NPC inspector'}).or(page.locator('.animus-inspector')).first();
  await inspector.waitFor();
  // The schema's help is the Name label's tooltip, linked to the NPC page.
  const docsOf = (scope, text) => scope.locator('.animus-tip').filter({has: page.getByRole('link', {name: text, exact: true})}).locator('.animus-tip-docs');
  const nameInfo = inspector.getByRole('link', {name: 'Name *', exact: true});
  assert.equal(await docsOf(inspector, 'Name *').getAttribute('href'), 'https://docs.volmitsoftware.com/animus/04-npc-definitions#definition-file');
  assert.equal(await page.locator(`[id="${await nameInfo.getAttribute('aria-describedby')}"]`).textContent(), 'The displayed name.');
  const name = inspector.getByLabel('Name', {exact: true});
  await name.fill('Greeter Edited');
  await page.waitForTimeout(300);
  // Behaviors sit in the inspector's Behavior part, closed until opened.
  const behaviour = inspector.locator('details[data-section="behaviour"]');
  if (!(await behaviour.evaluate((element) => element.open))) await behaviour.locator('summary').first().click();
  await choose(await selectWith(inspector, 'Add a behavior'), 'shop:keeper');
  await page.waitForTimeout(300);
  await shot(page, `${tag}-npc-inspector`);
  let json = await codeOf(page);
  assert.equal(json.name, 'Greeter Edited');
  assert.deepEqual(json.behaviors, ['greeter:greeter', 'shop:keeper']);
  assert.equal(json.uuid, 'fe531426-31bc-5660-9207-b3d3078c2305', 'untouched fields survive');

  // Code view key completion on the NPC.
  await page.getByRole('tab', {name: 'Code'}).click();
  let area = await caretAt(page, '{', 1);
  await area.pressSequentially('\n  "sk');
  const skin = page.getByRole('option').filter({hasText: 'skin'}).first();
  await skin.waitFor();
  await shot(page, `${tag}-code-key-completion`);
  await area.press('Enter');
  assert.match(await area.inputValue(), /"skin": /);
  await page.getByRole('button', {name: 'Discard'}).click();

  // Behavior node editor.
  await open(page, 'greeter:greeter');
  await page.getByRole('tab', {name: 'Visual'}).click();
  const editor = page.locator('.animus-behavior-editor');
  await editor.waitFor();
  await noOverflow(page, `${tag} behavior editor as opened`);
  const trigger = editor.locator('[data-node-path="$.triggers[1]"]');
  await trigger.getByLabel('Radius', {exact: true}).fill('8');
  await page.waitForTimeout(300);
  const step = editor.locator('[data-node-path="$.routines.wave.steps[1]"]');
  await chooseAndWait(step.locator('.animus-node-tools button[role="combobox"]').first(), 'wait');
  await step.getByLabel('Ticks', {exact: true}).waitFor();
  await page.waitForTimeout(300);
  const wave = editor.locator('[data-routine="wave"]');
  await choose(await selectWith(wave, 'Add step'), 'call');
  await editor.locator('[data-node-path="$.routines.wave.steps[2]"]').waitFor();
  const say = editor.locator('[data-node-path="$.routines.main.steps[1].then"]');
  await say.locator('.animus-tip [role="tooltip"]', {hasText: 'Unavailable here'}).first().waitFor({state: 'attached'});
  await page.waitForTimeout(300);
  await shot(page, `${tag}-behavior-editor`);
  json = await codeOf(page);
  assert.equal(json.triggers[1].radius, 8);
  assert.deepEqual(json.routines.wave.steps[1], {type: 'wait', ticks: 1});
  assert.deepEqual(json.routines.wave.steps[2], {type: 'call', routine: 'main'});
  assert.equal(json.routines.main.steps[1].then.arguments.text, 'Good to see you again.');

  // Denizen v2 parts: a list variable with items, loops, expressions, a placeholder.
  const variables = editor.locator('details.animus-gui-section', {has: page.locator('summary', {hasText: 'Variables'})});
  await variables.locator('summary').first().click();
  assert.equal(await docsOf(variables, 'Variables').getAttribute('href'), 'https://docs.volmitsoftware.com/animus/07-behaviors#variables');
  await variables.getByRole('button', {name: 'Add variable'}).click();
  const flag = editor.locator('[data-variable="flag"]');
  await flag.waitFor();
  await flag.getByLabel('Variable 1 name').fill('names');
  await editor.locator('[data-variable="names"]').waitFor();
  const names = editor.locator('[data-variable="names"]');
  await chooseAndWait(names.locator('button[role="combobox"]').first(), 'list');
  await names.getByRole('button', {name: 'Add item'}).click();
  await names.getByLabel('Starting value of names item 1', {exact: true}).fill('Alex');
  await page.waitForTimeout(300);
  await choose(await selectWith(wave, 'Add step'), 'while');
  const loop = editor.locator('[data-node-path="$.routines.wave.steps[3]"]');
  const expression = loop.getByLabel('Expression of step 4');
  await expression.fill('len(na');
  await loop.getByRole('option').filter({hasText: 'names'}).first().waitFor();
  await expression.press('Enter');
  await expression.pressSequentially(') < 1');
  await page.waitForTimeout(200);
  assert.equal(await loop.locator('.animus-gui-problem.is-error').count(), 0, 'the while expression checks clean');
  await expression.fill('len(names) <');
  await loop.locator('.animus-gui-problem.is-error').first().waitFor();
  await expression.fill('len(names) < 1');
  await choose(wave.locator('.animus-add-node button[role="combobox"]').last(), 'foreach');
  const each = editor.locator('[data-node-path="$.routines.wave.steps[4]"]');
  await each.waitFor();
  await choose(wave.locator('.animus-add-node button[role="combobox"]').last(), 'set');
  const set = editor.locator('[data-node-path="$.routines.wave.steps[5]"]');
  await chooseAndWait(await dropdownWithOption(set, /^item \(any\)$/), 'item');
  await page.waitForTimeout(200);
  await chooseAndWait(await dropdownWithOption(set, 'From an expression'), 'expression');
  await set.getByLabel('Value of step 6 expression').fill('len(names)');
  await page.waitForTimeout(200);
  const greet = editor.locator('[data-node-path="$.routines.main.steps[1].else.steps[0]"]');
  await chooseAndWait(greet.locator('[data-argument="text"] button[role="combobox"]').first(), 'template');
  await greet.getByLabel('Text template').fill('Hi {{ player.name }}');
  await page.waitForTimeout(300);
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
  await choose(wave.locator('.animus-add-node button[role="combobox"]').last(), 'action');
  await editor.locator('[data-node-path="$.routines.wave.steps[6]"]').waitFor();
  await page.waitForTimeout(300);
  json = await codeOf(page);
  assert.deepEqual(json.routines.wave.steps[6], {type: 'action', capability: 'animus:say', arguments: {text: 'Hello, {player_name}.'}});
  assert.equal(await page.locator('.animus-diagnostics li.is-error').count(), 0, 'a new action step leaves the document valid');

  // Keyboard: Down moves to the next row, Left closes it.
  const rows = editor.locator('[data-routine="wave"] .animus-node-summary');
  await rows.first().focus();
  await page.keyboard.press('ArrowDown');
  assert.equal(await page.evaluate(() => document.activeElement.closest('details').dataset.nodePath), '$.routines.wave.steps[0]');
  await page.keyboard.press('ArrowLeft');
  assert.equal(await editor.locator('details[data-node-path="$.routines.wave.steps[0]"]').getAttribute('open'), null);
  await page.keyboard.press('ArrowRight');

  // Dry run of the loop: one pass per item.
  await page.getByRole('tab', {name: 'Preview'}).click();
  await page.getByRole('heading', {name: 'Dry run'}).waitFor();
  await page.getByRole('button', {name: /^near: /}).click();
  await page.getByRole('button', {name: 'Show all'}).click();
  await page.locator('.animus-dryrun-step', {hasText: 'Item 1 of 1: item is "Alex".'}).waitFor();
  await page.locator('.animus-dryrun-step', {hasText: 'The loop does not run: its expression is false.'}).waitFor();
  await page.locator('.animus-dryrun-step', {hasText: 'Sets item to 1.'}).waitFor();
  await shot(page, `${tag}-dry-run-loop`);
  await noOverflow(page, `${tag} dry run`);

  // The server switch swaps NPC events for server events.
  await page.getByRole('tab', {name: 'Visual'}).click();
  const addTrigger = await selectWith(editor, 'Add a trigger');
  const events = () => optionValues(addTrigger);
  assert.ok((await events()).includes('interaction'), 'an NPC behavior offers interaction');
  assert.ok(!(await events()).includes('player_join'), 'an NPC behavior hides server events');
  await editor.getByLabel('Run on the server with no NPC').click();
  await page.waitForTimeout(300);
  assert.ok((await events()).includes('player_join'), 'a server behavior offers player_join');
  assert.ok(!(await events()).includes('interaction'), 'a server behavior hides NPC events');
  await choose(addTrigger, 'player_join');
  await editor.locator('[data-event="player_join"]').waitFor();
  await editor.locator('[data-event="interaction"] .animus-gui-problem.is-error').first().waitFor();
  await shot(page, `${tag}-server-switch`);
  json = await codeOf(page);
  assert.equal(json.server, true);
  assert.equal(json.triggers.at(-1).event, 'player_join');

  // The server behaviors list: created with the workspace's server behaviors, then edited.
  const workspace = await page.evaluate(() => decodeURIComponent(location.hash.split('/')[2]));
  await page.goto(`${process.env.ANIMUS_WEB_URL}/#/workspace/${encodeURIComponent(workspace)}/new/server-behaviors`);
  await page.getByRole('heading', {name: 'New server behaviors list'}).waitFor();
  await page.getByText('Creates server-behaviors.json with greeter:greeter.').waitFor();
  await page.getByRole('button', {name: 'Create server behaviors list'}).click();
  await page.locator('.animus-document-header').getByText('server-behaviors.json').waitFor();
  await page.getByRole('tab', {name: 'Visual'}).click();
  const list = page.locator('.animus-server-behaviors');
  await list.waitFor();
  await list.locator('[data-behavior="greeter:greeter"]').waitFor();
  assert.equal(await docsOf(list, 'Server behaviors').getAttribute('href'), 'https://docs.volmitsoftware.com/animus/07-behaviors#server-behaviors');
  await list.getByRole('button', {name: 'Remove greeter:greeter from the list'}).click();
  await list.getByText('Nothing listed. Add one below.').waitFor();
  await choose(await selectWith(list, 'Add a server behavior'), 'greeter:greeter');
  await list.locator('[data-behavior="greeter:greeter"]').waitFor();
  await shot(page, `${tag}-server-behaviors`);
  await noOverflow(page, `${tag} server behaviors`);
  json = await codeOf(page);
  assert.deepEqual(json, {schemaVersion: 1, behaviors: ['greeter:greeter']});
  await open(page, 'greeter:greeter');

  // Code view: expression completion and a JSON syntax error placed by line.
  await page.getByRole('tab', {name: 'Code'}).click();
  let codeArea = await caretAt(page, '"expression": "len(names) < 1"', 19);
  await codeArea.pressSequentially(' && na');
  await page.getByRole('option').filter({hasText: 'names'}).first().waitFor();
  await shot(page, `${tag}-code-expression-completion`);
  await codeArea.press('Escape');
  const broken = (await codeArea.inputValue()).replace('"entry": "main",', '"entry": "main"');
  await codeArea.fill(broken);
  await page.locator('.animus-code-syntax').getByText(/^Line \d+, column \d+: expected a comma or \} after the value/).waitFor();
  assert.ok(await page.locator('.animus-code-line.has-mark.is-error').count() >= 1, 'the gutter marks the broken line');
  await shot(page, `${tag}-code-syntax`);
  await page.getByRole('button', {name: 'Discard'}).click();
  await page.getByRole('tab', {name: 'Visual'}).click();
  await editor.getByLabel('Run on the server with no NPC').click();
  await page.waitForTimeout(300);

  // Code view value completion and diagnostics by line.
  await page.getByRole('tab', {name: 'Code'}).click();
  area = await caretAt(page, '"phase": "', 10);
  await area.press('Control+Space');
  const leave = page.getByRole('option').filter({hasText: 'leave'}).first();
  await leave.waitFor();
  await shot(page, `${tag}-code-value-completion`);
  await leave.click();
  let text = await area.inputValue();
  assert.match(text, /"phase": "leave"/);
  await area.fill(text.replace('"radius": 8', '"radius": 100'));
  await page.getByRole('button', {name: 'Apply to draft'}).click();
  const problem = page.locator('.animus-code-problems li').filter({hasText: '$.triggers[1].radius'}).first();
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
