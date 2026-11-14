// Opens test/fixtures/health_part (Training Dummy without combat or health,
// Fallen Knight dead after permadeath, Gate Sentry with every part of
// combat and health set) and checks the NPC's Combat tab at 1440 and 390 px
// in both themes:
// - Simple: Fights and Can be hurt start and stop the modules, Who it fights
//   writes its preset, Max health shows hearts; a rule set no preset holds
//   reads Custom, a preset over it asks first; "n advanced settings"
//   counts what Simple hides and Show opens it in this tab only; every key
//   Simple does not show survives its edits byte for byte.
// - Advanced: rows are labelled in words (no engine keys outside the tips),
//   damage, drops and respawn points edit by path, Stay dead and Delete NPC
//   hide the respawn fields and keep them, Remove health asks first.
// - Revive clears `dead`.
// Every edit is read back from the Code view. No console errors, no
// sideways scrolling.
//
// It opens the fixture as a data folder and needs no fake server.
//
//   python3 tool/serve_site.py --port <port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_SCREENSHOTS=<scratch folder> node test/health_part_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openFromSidebar, setMode, showView} = require('./support/shell.cjs');
const {choose, chooseText} = require('./support/select.cjs');
const {npcTab} = require('./support/npc_tabs.cjs');

const fixture = path.resolve(__dirname, 'fixtures/health_part');
const site = process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to your own served site, never the shared :8098'); })();
const shots = process.env.ANIMUS_SCREENSHOTS;
const sentryFile = JSON.parse(fs.readFileSync(path.join(fixture, 'npcs/town/sentry.json'), 'utf8'));

async function shot(locator, name) {
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

/** The document as JSON, read from the Code view; the mode is put back. */
async function codeOf(page) {
  const mode = await page.evaluate(() => window.localStorage.getItem('animus.mode'));
  await showView(page, 'code');
  const text = await page.locator('.animus-code textarea').inputValue();
  await showView(page, 'visual');
  if (mode !== 'advanced') await setMode(page, 'simple');
  return JSON.parse(text);
}

/**
 * The Combat tab: the NPC tab once the document has tabs, before that the
 * same widget mounted in the Modules part.
 */
async function combatTab(page) {
  const panel = await npcTab(page, 'Combat');
  if (!panel) {
    const details = page.locator('details[data-section="modules"]');
    if (!(await details.evaluate((element) => element.open))) await details.locator('summary').first().click();
  }
  const tab = page.locator('[data-npc-tab="combat"]');
  await tab.waitFor();
  return tab;
}

async function open(page, name) {
  await openFromSidebar(page, name);
  await page.locator('.ui-page-header').getByText(name).first().waitFor();
  await settle(page);
}

async function fill(page, field, text) {
  await field.fill(text);
  await field.blur();
  await settle(page);
}

function toggle(scope, name) {
  return scope.getByRole('switch', {name, exact: true});
}

function section(tab, part) {
  return tab.locator(`[data-combat-section="${part}"]`);
}

/** Opens a section of the Advanced part when it is closed. */
async function openSection(tab, part) {
  const details = section(tab, part).locator('details').first();
  if (!(await details.evaluate((element) => element.open))) await details.locator('summary').first().click();
  return section(tab, part);
}

/** Simple: the four rows of a new fighter, read back from the JSON. */
async function dummySimple(page, tag) {
  await open(page, 'Training Dummy');
  let tab = await combatTab(page);
  assert.equal(await tab.getByRole('combobox', {name: 'Who it fights'}).count(), 0, `${tag} no preset while it does not fight`);
  assert.equal(await tab.locator('[data-combat="in-use"]').count(), 0, `${tag} nothing hidden yet`);
  await toggle(tab, 'Fights').click();
  await settle(page);
  assert.deepEqual((await codeOf(page)).modules['animus:combat'], {enabled: true}, `${tag} fights`);

  tab = await combatTab(page);
  const who = tab.getByRole('combobox', {name: 'Who it fights'});
  await who.waitFor();
  assert.match(await who.innerText(), /Attackers only/, `${tag} no rules read as attackers only`);
  await choose(who, 'monstersAndPlayers');
  await settle(page);
  await toggle(tab, 'Can be hurt').click();
  await settle(page);
  const max = tab.getByLabel('Max health', {exact: true});
  await max.waitFor();
  await tab.getByText('10 hearts').waitFor();
  await fill(page, max, '30');
  await tab.getByText('15 hearts').waitFor();
  let json = await codeOf(page);
  assert.deepEqual(json.modules['animus:combat'], {enabled: true, targets: {categories: ['players', 'monsters']}}, `${tag} preset`);
  assert.deepEqual(json.modules['animus:health'], {killable: true, maximumHealth: 30}, `${tag} can be hurt, max health`);
  await shot(await combatTab(page), `combat-${tag}-dummy-simple`);

  // Fights off keeps the targets, which Simple then counts as hidden.
  tab = await combatTab(page);
  await toggle(tab, 'Fights').click();
  await settle(page);
  assert.equal(await tab.getByRole('combobox', {name: 'Who it fights'}).count(), 0, `${tag} preset hidden while off`);
  await tab.locator('[data-combat="in-use"]').getByText('1 advanced setting', {exact: true}).waitFor();
  json = await codeOf(page);
  assert.deepEqual(json.modules['animus:combat'], {enabled: false, targets: {categories: ['players', 'monsters']}}, `${tag} fights off keeps targets`);
  tab = await combatTab(page);
  await toggle(tab, 'Fights').click();
  await settle(page);
  assert.equal(await tab.locator('[data-combat="in-use"]').count(), 0, `${tag} nothing hidden while it fights`);
  await noSideways(page, `${tag} dummy simple`);
}

/** Advanced: labels in words, damage, drops, respawn, Remove health. */
async function dummyAdvanced(page, tag) {
  await setMode(page, 'advanced');
  let tab = await combatTab(page);
  await tab.getByRole('heading', {name: 'Health'}).waitFor();
  const text = await tab.innerText();
  for (const key of ['targetableByMobs', 'victimDrops', 'scanIntervalTicks', 'closeChase', 'maximumHealth', 'invulnerableTicks', 'pursuit.', 'small_fireball', 'permadeath']) {
    assert.ok(!text.includes(key), `${tag} no engine key "${key}" in the tab's text`);
  }
  assert.ok(!/\b[a-z]+[A-Z][a-zA-Z]*\b/.test(text), `${tag} no camelCase words: ${text.match(/\b[a-z]+[A-Z][a-zA-Z]*\b/)}`);

  let damage = await openSection(tab, 'damage');
  await fill(page, damage.getByLabel('Hit range', {exact: true}), '8');
  damage = await openSection(await combatTab(page), 'damage');
  await toggle(damage, 'Safe from ignored').click();
  await settle(page);
  damage = await openSection(await combatTab(page), 'damage');
  await damage.getByRole('group', {name: 'Also hurt by'}).getByRole('checkbox', {name: 'Lava'}).click();
  await settle(page);

  let death = await openSection(await combatTab(page), 'death');
  await death.getByRole('button', {name: 'Add drop'}).click();
  await settle(page);
  death = await openSection(await combatTab(page), 'death');
  const item = death.getByLabel('Drop 1 item', {exact: true});
  await item.fill('gold ingot');
  const option = page.locator('[role="option"][data-value="minecraft:gold_ingot"]').first();
  await option.waitFor();
  await option.click();
  await settle(page);
  await fill(page, death.getByLabel('Drop 1 max count', {exact: true}), '3');
  await fill(page, death.getByLabel('Drop 1 chance', {exact: true}), '0.5');

  let respawn = await openSection(await combatTab(page), 'respawn');
  await respawn.getByRole('button', {name: 'Add point'}).click();
  await settle(page);
  respawn = await openSection(await combatTab(page), 'respawn');
  await respawn.getByLabel('Point 1 world', {exact: true}).waitFor();
  await noSideways(page, `${tag} dummy advanced`);

  let module = (await codeOf(page)).modules['animus:health'];
  assert.equal(module.killable, true);
  assert.equal(module.maximumHealth, 30);
  assert.equal(module.protectFromRange, 8, `${tag} hit range`);
  assert.equal(module.protectedFromIgnored, true, `${tag} safe from ignored`);
  assert.deepEqual(module.sources, ['players', 'mobs', 'npcs', 'projectiles', 'lava'], `${tag} hurt by lava too`);
  assert.deepEqual(module.death.drops, [{item: {id: 'minecraft:gold_ingot'}, maxCount: 3, chance: 0.5}], `${tag} drop`);
  assert.deepEqual(module.respawn.points, [{world: 'world', x: 4, y: 64, z: -3}], `${tag} respawn point at the NPC`);

  // Stay dead and Delete NPC hide delay, order and points, and keep them.
  respawn = await openSection(await combatTab(page), 'respawn');
  const after = respawn.getByRole('combobox', {name: 'After death'});
  await chooseText(after, 'Stay dead');
  await settle(page);
  respawn = await openSection(await combatTab(page), 'respawn');
  assert.equal(await respawn.getByLabel('Delay', {exact: true}).count(), 0, `${tag} delay hidden on stay dead`);
  module = (await codeOf(page)).modules['animus:health'];
  assert.equal(module.respawn.afterDeath, 'permadeath');
  assert.equal(module.respawn.points.length, 1, `${tag} points kept`);
  respawn = await openSection(await combatTab(page), 'respawn');
  await chooseText(respawn.getByRole('combobox', {name: 'After death'}), 'Delete NPC');
  await settle(page);
  assert.equal((await codeOf(page)).modules['animus:health'].respawn.afterDeath, 'delete', `${tag} delete after death`);
  await shot(await combatTab(page), `combat-${tag}-dummy-advanced`);

  // Remove health asks first; Cancel keeps it.
  tab = await combatTab(page);
  await tab.getByRole('button', {name: 'Remove health'}).click();
  let dialog = page.getByRole('alertdialog');
  await dialog.waitFor();
  await dialog.getByRole('button', {name: 'Cancel'}).click();
  await dialog.waitFor({state: 'hidden'});
  assert.ok((await codeOf(page)).modules['animus:health'], `${tag} cancel keeps health`);
  tab = await combatTab(page);
  await tab.getByRole('button', {name: 'Remove health'}).click();
  dialog = page.getByRole('alertdialog');
  await dialog.getByRole('button', {name: 'Remove health'}).click();
  await dialog.waitFor({state: 'hidden'});
  await settle(page);
  assert.equal((await codeOf(page)).modules['animus:health'], undefined, `${tag} health removed`);
  await setMode(page, 'simple');
}

/** Simple on a fully set NPC: Custom, the in-use line, Show, and nothing lost. */
async function sentry(page, tag) {
  await open(page, 'Gate Sentry');
  let tab = await combatTab(page);
  const who = tab.getByRole('combobox', {name: 'Who it fights'});
  assert.match(await who.innerText(), /Custom/, `${tag} custom rules read Custom`);
  assert.equal(await tab.getByLabel('Max health', {exact: true}).getAttribute('placeholder'), '30', `${tag} max health falls back to the attribute`);
  const line = tab.locator('[data-combat="in-use"]');
  assert.match(await line.innerText(), /^29 advanced settings\s+Show$/, `${tag} in-use line: a count, defaults and import records left out`);
  assert.equal(await section(tab, 'melee').count(), 0, `${tag} Simple hides the sections`);
  await shot(tab, `combat-${tag}-sentry-simple`);

  // Show opens the advanced part in this tab only.
  await line.getByRole('button', {name: 'Show'}).click();
  await section(tab, 'melee').waitFor();
  assert.equal(await page.evaluate(() => window.localStorage.getItem('animus.mode')), 'simple', `${tag} Show keeps the mode`);
  await line.getByRole('button', {name: 'Hide'}).click();
  await section(tab, 'melee').waitFor({state: 'detached'});

  // A preset over custom rules asks first; Cancel keeps them.
  await choose(who, 'monsters');
  const dialog = page.getByRole('alertdialog');
  await dialog.waitFor();
  await dialog.getByRole('button', {name: 'Cancel'}).click();
  await dialog.waitFor({state: 'hidden'});

  // Simple edits keep every other key as written.
  tab = await combatTab(page);
  await fill(page, tab.getByLabel('Max health', {exact: true}), '50');
  await toggle(tab, 'Fights').click();
  await settle(page);
  await toggle(tab, 'Fights').click();
  await settle(page);
  await toggle(tab, 'Can be hurt').click();
  await settle(page);
  await toggle(tab, 'Can be hurt').click();
  await settle(page);
  const json = await codeOf(page);
  const before = sentryFile.modules;
  const combat = json.modules['animus:combat'];
  const health = json.modules['animus:health'];
  assert.equal(JSON.stringify(combat), JSON.stringify(before['animus:combat']), `${tag} combat byte for byte`);
  assert.equal(health.maximumHealth, 50, `${tag} max health written`);
  const {maximumHealth, ...rest} = health;
  assert.equal(JSON.stringify(rest), JSON.stringify(before['animus:health']), `${tag} health byte for byte`);
  assert.deepEqual(json.attributes, sentryFile.attributes, `${tag} attributes untouched`);
  await noSideways(page, `${tag} sentry`);

  // Replace confirms and writes the preset.
  tab = await combatTab(page);
  await choose(tab.getByRole('combobox', {name: 'Who it fights'}), 'monsters');
  await page.getByRole('alertdialog').getByRole('button', {name: 'Replace'}).click();
  await settle(page);
  assert.deepEqual((await codeOf(page)).modules['animus:combat'].targets, {categories: ['monsters']}, `${tag} preset replaced custom`);
}

async function knight(page, tag) {
  await open(page, 'Fallen Knight');
  const tab = await combatTab(page);
  await tab.getByText('Dead. It stays dead until revived.').waitFor();
  await noSideways(page, `${tag} knight`);
  await tab.getByRole('button', {name: 'Revive'}).click();
  await settle(page);
  const json = await codeOf(page);
  assert.equal('dead' in json.modules['animus:health'], false, `${tag} revive clears dead`);
  assert.equal(await (await combatTab(page)).getByRole('button', {name: 'Revive'}).count(), 0, `${tag} revive button gone`);
}

async function run(browser, viewport, theme) {
  const tag = `${viewport.width}-${theme}`;
  const context = await browser.newContext({viewport});
  await context.addInitScript((mode) => {
    if (!window.sessionStorage.getItem('animus.test.started')) {
      window.sessionStorage.setItem('animus.test.started', '1');
      window.localStorage.setItem('animus.theme', mode);
      window.localStorage.setItem('animus.mode', 'simple');
    }
  }, theme);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error' && !/404|Failed to load resource/.test(message.text())) errors.push(message.text());
  });
  await page.goto(site);
  await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(fixture);
  await page.waitForFunction(() => /#\/workspace\//.test(location.hash));
  await page.locator('.ui-save-bar').waitFor();
  assert.ok(await page.evaluate((mode) => document.documentElement.classList.contains(mode), theme), `${theme} theme is on`);
  try {
    await dummySimple(page, tag);
    await dummyAdvanced(page, tag);
    await sentry(page, tag);
    await knight(page, tag);
  } catch (error) {
    if (shots) await page.screenshot({path: path.join(shots, `combat-failure-${tag}.png`)});
    throw error;
  }
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
