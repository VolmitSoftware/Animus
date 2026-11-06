// Opens ../schema/fixtures/export in the built site at desktop and phone
// widths and checks the NPC inspector (a field edit and a workspace
// behavior added), the behavior node editor (a trigger field, a node type
// change and a new step), and the code view (key and value completion,
// diagnostics mapped to a gutter line that jumps), with no console errors.
//
//   python3 -m http.server 8098 --bind 127.0.0.1 --directory build/jaspr
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/inspectors_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const exportFolder = path.resolve(__dirname, '../../schema/fixtures/export');
const shots = process.env.ANIMUS_SCREENSHOTS;

async function shot(page, name) {
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: true});
}

async function open(page, sidebarText) {
  await page.locator('.animus-sidebar').getByText(sidebarText, {exact: true}).first().click();
  await page.locator('.animus-document-header').waitFor();
}

async function codeOf(page) {
  await page.getByRole('tab', {name: 'Code'}).click();
  const text = await page.locator('.animus-code textarea').inputValue();
  await page.getByRole('tab', {name: 'Visual'}).click();
  return JSON.parse(text);
}

/// A native select holding an option with [text].
function selectWith(scope, text) {
  return scope.locator('select', {has: scope.page().locator('option', {hasText: text})}).first();
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

async function run(browser, viewport, tag) {
  const context = await browser.newContext({viewport});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })());
  await page.getByRole('heading', {name: 'Open an Animus data folder'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(exportFolder);
  await page.getByRole('heading', {name: '1 documents need repair'}).waitFor();

  // NPC inspector: help from the schema, a name edit, a workspace behavior added.
  await open(page, 'Greeter');
  await page.getByRole('tab', {name: 'Visual'}).click();
  const inspector = page.getByRole('region', {name: 'NPC inspector'}).or(page.locator('.animus-inspector')).first();
  await inspector.waitFor();
  await inspector.getByText('The displayed name.').waitFor();
  const name = inspector.getByLabel('name', {exact: true});
  await name.fill('Greeter Edited');
  await page.waitForTimeout(300);
  await selectWith(inspector, 'Add a workspace behavior').selectOption('shop:keeper');
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
  const trigger = editor.locator('[data-node-path="$.triggers[1]"]');
  await trigger.getByLabel('radius', {exact: true}).fill('8');
  await page.waitForTimeout(300);
  const step = editor.locator('[data-node-path="$.routines.wave.steps[1]"]');
  await step.locator('.animus-node-head select').first().selectOption('wait');
  await step.getByLabel('ticks', {exact: true}).waitFor();
  await page.waitForTimeout(300);
  const wave = editor.locator('[data-routine="wave"]');
  await selectWith(wave, 'Add step').selectOption('call');
  await editor.locator('[data-node-path="$.routines.wave.steps[2]"]').waitFor();
  const say = editor.locator('[data-node-path="$.routines.main.steps[1].then"]');
  await say.getByText('Unavailable here').first().waitFor();
  await page.waitForTimeout(300);
  await shot(page, `${tag}-behavior-editor`);
  json = await codeOf(page);
  assert.equal(json.triggers[1].radius, 8);
  assert.deepEqual(json.routines.wave.steps[1], {type: 'wait', ticks: 1});
  assert.deepEqual(json.routines.wave.steps[2], {type: 'call', routine: 'main'});
  assert.equal(json.routines.main.steps[1].then.arguments.text, 'Good to see you again.');

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
    await run(browser, {width: 1440, height: 1000}, 'desktop');
    await run(browser, {width: 390, height: 844}, 'phone');
    console.log('inspectors_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
