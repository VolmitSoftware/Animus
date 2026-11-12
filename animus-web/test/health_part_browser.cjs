// Opens test/fixtures/health_part (an NPC without a health module, and a
// dead permadeath combat NPC) and checks the Health and death part at 1440
// and 390 px in both themes: Make killable, the Killable switch off and on,
// max health, hit range and Safe from ignored, a drop picked by item search
// with its max count and chance, a respawn point at the NPC's block,
// permadeath and delete hiding the respawn fields, and Revive clearing
// `dead`. Every edit is read back from the Code view. No console errors, no
// sideways scrolling.
//
// It opens the fixture as a data folder and needs no fake server. To look at
// the same NPCs paired with one, start it with
// `dart run tool/fake_server.dart --port=<port> --origin=http://127.0.0.1:<web port> --data=test/fixtures/health_part`.
//
//   python3 tool/serve_site.py --port <port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_SCREENSHOTS=<scratch folder> node test/health_part_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openFromSidebar} = require('./support/shell.cjs');
const {choose} = require('./support/select.cjs');

const fixture = path.resolve(__dirname, 'fixtures/health_part');
const site = process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to your own served site, never the shared :8098'); })();
const shots = process.env.ANIMUS_SCREENSHOTS;

async function shot(page, locator, name) {
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await locator.screenshot({path: path.join(shots, `${name}.png`)});
}

async function settle(page) {
  await page.waitForTimeout(250);
}

async function noSideways(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 0, `${where} scrolls sideways by ${overflow}px`);
}

async function codeOf(page) {
  await page.getByRole('tab', {name: 'Code'}).click();
  const text = await page.locator('.animus-code textarea').inputValue();
  await page.getByRole('tab', {name: 'Visual'}).click();
  return JSON.parse(text);
}

/** The Health and death part, with the Modules part opened (phones close it). */
async function healthPart(page) {
  const inspector = page.getByRole('region', {name: 'NPC inspector'});
  await inspector.waitFor();
  const details = inspector.locator('details[data-section="modules"]');
  if (!(await details.evaluate((element) => element.open))) await details.locator('summary').first().click();
  const health = details.locator('[data-module="animus:health"]');
  await health.waitFor();
  await health.scrollIntoViewIfNeeded();
  return health;
}

async function open(page, name) {
  await openFromSidebar(page, name);
  await page.locator('.animus-document-header').getByText(name).first().waitFor();
  await settle(page);
}

async function fill(page, field, text) {
  await field.fill(text);
  await field.blur();
  await settle(page);
}

async function dummy(page, tag) {
  await open(page, 'Training Dummy');
  let health = await healthPart(page);
  await health.getByText('Off: cannot be hurt.').waitFor();
  await health.getByRole('button', {name: 'Make killable'}).click();
  await settle(page);
  assert.deepEqual((await codeOf(page)).modules['animus:health'], {killable: true}, `${tag} make killable`);

  // Killable off hides the settings, on brings them back.
  health = await healthPart(page);
  await health.getByRole('checkbox', {name: /^Killable/}).click();
  await health.getByText('Cannot be hurt. Settings below are kept.').waitFor();
  assert.equal(await health.getByLabel('health.maximumHealth', {exact: true}).count(), 0, `${tag} settings hidden while not killable`);
  assert.equal((await codeOf(page)).modules['animus:health'].killable, false, `${tag} killable off`);
  health = await healthPart(page);
  await health.getByRole('checkbox', {name: /^Killable/}).click();
  await settle(page);

  // Max health, hit range and Safe from ignored, then a drop picked by item search.
  await fill(page, health.getByLabel('health.maximumHealth', {exact: true}), '30');
  await fill(page, health.getByLabel('health.protectFromRange', {exact: true}), '8');
  await health.getByRole('checkbox', {name: /^Safe from ignored/}).click();
  await settle(page);
  await health.getByRole('button', {name: 'Add drop'}).click();
  await settle(page);
  const item = health.getByLabel('Drop 1 item', {exact: true});
  await item.fill('gold ingot');
  const option = page.getByRole('option').filter({hasText: 'minecraft:gold_ingot'}).first();
  await option.waitFor();
  await option.click();
  await settle(page);
  await fill(page, health.getByLabel('Drop 1 max count', {exact: true}), '3');
  await fill(page, health.getByLabel('Drop 1 chance', {exact: true}), '0.5');

  // A respawn point starts at the NPC's block.
  await health.getByRole('button', {name: 'Add point', exact: true}).click();
  await settle(page);
  await health.getByLabel('Point 1 world', {exact: true}).waitFor();
  await shot(page, health, `health-${tag}-respawn`);
  await noSideways(page, `${tag} health`);

  let module = (await codeOf(page)).modules['animus:health'];
  assert.equal(module.killable, true);
  assert.equal(module.maximumHealth, 30);
  assert.equal(module.protectFromRange, 8, `${tag} hit range`);
  assert.equal(module.protectedFromIgnored, true, `${tag} safe from ignored`);
  assert.deepEqual(module.death.drops, [{item: {id: 'minecraft:gold_ingot'}, maxCount: 3, chance: 0.5}], `${tag} drop`);
  assert.deepEqual(module.respawn.points, [{world: 'world', x: 4, y: 64, z: -3}], `${tag} respawn point`);

  // Permadeath hides the delay, order and points, and keeps the points.
  // The list opens on a click and stays open while the page scrolls under it.
  health = await healthPart(page);
  const after = health.getByLabel('health.respawn.afterDeath', {exact: true}).and(page.locator('button[role="combobox"]'));
  await after.scrollIntoViewIfNeeded();
  await after.click();
  await page.locator('.animus-select-popup [role="option"][data-value="permadeath"]').waitFor();
  await page.evaluate(() => window.scrollBy({top: 30, behavior: 'instant'}));
  await settle(page);
  assert.equal(await after.getAttribute('aria-expanded'), 'true', `${tag} After death stays open through a page scroll`);
  await page.keyboard.press('Escape');
  await page.locator('.animus-select-popup').waitFor({state: 'detached'});
  health = await healthPart(page);
  await choose(health.getByLabel('health.respawn.afterDeath', {exact: true}), 'permadeath');
  await settle(page);
  assert.equal(await health.getByLabel('health.respawn.delayTicks', {exact: true}).count(), 0, `${tag} delay hidden on permadeath`);
  module = (await codeOf(page)).modules['animus:health'];
  assert.equal(module.respawn.afterDeath, 'permadeath');
  assert.equal(module.respawn.points.length, 1, `${tag} points kept`);

  // Delete also hides them.
  health = await healthPart(page);
  await choose(health.getByLabel('health.respawn.afterDeath', {exact: true}), 'delete');
  await settle(page);
  assert.equal(await health.getByLabel('health.respawn.delayTicks', {exact: true}).count(), 0, `${tag} delay hidden on delete`);
  assert.equal((await codeOf(page)).modules['animus:health'].respawn.afterDeath, 'delete', `${tag} delete after death`);
}

async function knight(page, tag) {
  await open(page, 'Fallen Knight');
  let health = await healthPart(page);
  await health.getByText('Dead. Stays gone until revived.').waitFor();
  await shot(page, health, `health-${tag}-knight`);
  await noSideways(page, `${tag} knight`);

  await health.getByRole('button', {name: 'Revive'}).click();
  await settle(page);
  const json = await codeOf(page);
  assert.equal('dead' in json.modules['animus:health'], false, `${tag} revive clears dead`);
  health = await healthPart(page);
  assert.equal(await health.getByRole('button', {name: 'Revive'}).count(), 0, `${tag} revive button gone`);
}

async function run(browser, viewport, theme) {
  const tag = `${viewport.width}-${theme}`;
  const context = await browser.newContext({viewport});
  await context.addInitScript((mode) => window.localStorage.setItem('animus.theme', mode), theme);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error' && !/404|Failed to load resource/.test(message.text())) errors.push(message.text());
  });
  await page.goto(site);
  await page.getByRole('heading', {name: 'From a data folder'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(fixture);
  await page.getByRole('heading', {name: 'health_part'}).waitFor();
  assert.ok(await page.evaluate((mode) => document.documentElement.classList.contains(mode), theme), `${theme} theme is on`);
  await dummy(page, tag);
  await knight(page, tag);
  assert.deepEqual(errors, [], `${tag} console errors`);
  await context.close();
  console.log(`ok ${tag}`);
}

(async () => {
  const browser = await chromium.launch({headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
  try {
    for (const viewport of [{width: 1440, height: 1000}, {width: 390, height: 844}]) {
      for (const theme of ['dark', 'light']) await run(browser, viewport, theme);
    }
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
