// Changing an NPC's body on its Basics tab, at desktop width in the dark
// theme and phone width in the light one. The body is picked by name. The
// hero (a skinned, fully equipped player, seated first) becomes a horse: the
// skin question removes the skin, the seated pose a horse cannot show is
// named with a way to remove it, the skin field gives way to a note,
// player-only slots warn on Look, the horse's own settings are typed
// dropdowns that reach the Code view and the stage, and the pose row is
// gone. Every other body then shows only its own settings and traits. Last,
// a server NPC turned into a horse is applied to tool/fake_server.dart
// through the save bar. No console errors, no sideways scroll.
//
//   python3 tool/serve_site.py --port <site port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<site port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<site port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/body_types_browser.cjs
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {topbarAction, showView, workspaceOpen, waitOnline, saveAction} = require('./support/shell.cjs');
const {choose, optionValues, waitForValue} = require('./support/select.cjs');
const {npcTab} = require('./support/npc_tabs.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const folder = path.resolve(__dirname, 'fixtures/npc_preview');

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

/** Picks [type] in the Body select; a skinned NPC asks first and Remove skin changes both. */
async function setType(page, type, {removeSkin = false} = {}) {
  await npcTab(page, 'Basics');
  const trigger = page.locator('button#animus-npc-body');
  await choose(trigger, type);
  if (removeSkin) await page.getByRole('alertdialog').getByRole('button', {name: 'Remove skin', exact: true}).click();
  await waitForValue(trigger, type);
}

async function draft(page) {
  await showView(page, 'code');
  const json = JSON.parse(await page.locator('.animus-code textarea').first().inputValue());
  await showView(page, 'visual');
  return json;
}

/**
 * Waits until the stage has drawn with every mesh built, reading it every
 * 250 ms. On a phone the stage is behind the header's Preview button.
 */
async function stageReady(page, timeout = 20000) {
  const deadline = Date.now() + timeout;
  if (!(await page.locator('.animus-mc-gl-host').count())) {
    const preview = page.locator('#animus-main').getByRole('button', {name: 'Preview', exact: true});
    if (await preview.count()) await preview.first().click();
  }
  for (;;) {
    const ready = await page.evaluate(() => {
      const host = document.querySelector('.animus-mc-gl-host');
      return Boolean(host && Number(host.getAttribute('data-frames')) > 0 && host.getAttribute('data-unbuilt') === '0');
    });
    if (ready) return;
    if (Date.now() > deadline) throw new Error(`the stage did not draw: ${await page.evaluate(() => [...document.querySelectorAll('.animus-mc-gl-host')].map((host) => `${host.getAttribute('data-frames')}/${host.getAttribute('data-unbuilt')}`).join(', ') + ' at ' + location.hash)}`);
    await page.waitForTimeout(250);
  }
}

async function noSidewaysScroll(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 1, `${where}: ${overflow}px of sideways scroll`);
}

async function offline(browser, viewport, label, dark) {
  const page = await browser.newPage({viewport});
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(site);
  await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(folder);
  await workspaceOpen(page);
  if (await page.evaluate(() => document.documentElement.classList.contains('dark')) !== dark) await topbarAction(page, dark ? 'Use dark theme' : 'Use light theme');
  const workspace = page.url().split('#')[1].split('?')[0];
  await page.goto(`${site}/#${workspace}/npc/players%2Fhero?view=visual`);
  await page.locator('h1').getByText('Hero', {exact: true}).waitFor();

  // The seated player becomes a horse.
  await choose((await npcTab(page, 'Look')).locator('button#animus-npc-pose'), 'sitting');
  assert.equal((await draft(page)).pose, 'sitting', `${label}: seated first`);
  await setType(page, 'minecraft:horse', {removeSkin: true});
  const basics = await npcTab(page, 'Basics');
  const left = page.getByRole('group', {name: 'Fields this body cannot show'});
  await left.getByText('The sitting pose shows only on player bodies.').waitFor();
  assert.equal(await left.getByText('Skins show only', {exact: false}).count(), 0, `${label}: the question took the skin`);
  await basics.getByText('Only player bodies wear skins.').waitFor();
  assert.equal(await basics.locator('[data-skin="field"]').count(), 0, `${label}: no skin field on a horse`);
  let look = await npcTab(page, 'Look');
  assert.equal(await look.locator('button#animus-npc-pose').count(), 0, `${label}: no pose row on a horse`);
  assert.equal(await look.locator('[data-slot-grid="animus-equipment"] .is-warning, [data-slot-grid="animus-equipment"] .is-error').count(), 6, `${label}: the six player slots warn`);
  await npcTab(page, 'Basics');
  await left.getByRole('button', {name: 'Remove pose'}).click();
  await left.waitFor({state: 'detached'});
  const converted = await draft(page);
  assert.deepEqual([converted.type, converted.skin, converted.pose], ['minecraft:horse', undefined, undefined], `${label}: skin and pose are gone`);

  // The horse's own settings are typed dropdowns on Look.
  look = await npcTab(page, 'Look');
  const horse = look.getByRole('group', {name: 'Horse settings'});
  const color = horse.locator('button[role="combobox"]').first();
  assert.deepEqual((await optionValues(color)).filter(Boolean), ['white', 'creamy', 'chestnut', 'brown', 'black', 'gray', 'dark_brown']);
  await choose(color, 'chestnut');
  await choose(horse.locator('button[role="combobox"]').nth(1), 'white_dots');
  assert.deepEqual((await draft(page)).horse, {color: 'chestnut', markings: 'white_dots'}, `${label}: the settings reach the definition`);
  look = await npcTab(page, 'Look');
  await look.locator('.animus-npc-tab-body > .ui-more > details > summary').click();
  await look.getByRole('switch', {name: 'Name shown'}).waitFor();
  assert.equal(await look.getByText('In tab list', {exact: true}).count(), 0, `${label}: no tab list trait on a horse`);
  assert.equal(await look.getByText('Using item', {exact: true}).count(), 0, `${label}: no raised item trait on a horse`);
  await noSidewaysScroll(page, `${label} horse form`);

  await stageReady(page);

  // Every other body: its own settings and nothing left from the horse but the warning to remove it.
  for (const [type, settings, baby] of [['minecraft:llama', 'Llama', true], ['minecraft:frog', 'Frog', true], ['minecraft:armadillo', 'Armadillo', true],
    ['minecraft:ender_dragon', 'Ender dragon', false], ['minecraft:turtle', null, true], ['minecraft:armor_stand', null, false]]) {
    await setType(page, type);
    await left.getByText('Only for horse bodies.').waitFor();
    const section = await npcTab(page, 'Look');
    if (settings) await section.getByRole('group', {name: `${settings} settings`}).waitFor();
    assert.equal(await section.locator('[data-settings]').count(), settings ? 1 : 0, `${label} ${type}: only its own settings`);
    const more = section.locator('.animus-npc-tab-body > .ui-more > details');
    if (!(await more.evaluate((details) => details.open))) await more.locator(':scope > summary').click();
    assert.equal(await section.getByText('Baby', {exact: true}).count() > 0, baby, `${label} ${type}: baby trait`);
    await noSidewaysScroll(page, `${label} ${type}`);
  }
  await setType(page, 'minecraft:llama');
  await left.getByRole('button', {name: 'Remove horse', exact: true}).click();
  await left.waitFor({state: 'detached'});
  const json = await draft(page);
  assert.equal(json.horse, undefined, `${label}: the horse settings are removed`);
  assert.deepEqual(errors, [], `${label}: console errors`);
  await page.close();
}

async function served(browser, viewport, label) {
  await control('reset', {});
  await control('restore', {});
  const {code} = await control('pair?paths=both', {});
  const page = await browser.newPage({viewport});
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(`${site}/#/pair/${code}`);
  await page.getByTestId('pair-submit').waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  const server = await page.evaluate(() => location.hash.split('/')[2]);
  await page.evaluate((hash) => { location.hash = hash; }, `#/workspace/server-${server}/npc/${encodeURIComponent('examples/greeter')}`);
  await waitOnline(page);
  await page.locator('.animus-npc-tabs').waitFor();
  await setType(page, 'minecraft:horse');
  const horse = (await npcTab(page, 'Look')).getByRole('group', {name: 'Horse settings'});
  await choose(horse.locator('button[role="combobox"]').first(), 'black');
  await page.waitForFunction(() => /^1 change not applied/.test(document.querySelector('.ui-save-bar .ui-save-message')?.textContent || ''));
  await saveAction(page, 'apply').click();
  await page.waitForFunction(() => /^(Applied 1 change|Up to date)/.test(document.querySelector('.ui-save-bar .ui-save-message')?.textContent || ''));
  const stored = (await control('state')).documents.find((document) => document.kind === 'npc' && document.id === 'examples/greeter');
  assert.ok(stored.revision > 1, `${label}: the server applied it`);
  assert.deepEqual(errors, [], `${label}: console errors`);
  await page.close();
}

(async () => {
  const browser = await chromium.launch({headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
  try {
    await offline(browser, {width: 1440, height: 900}, 'desktop-dark', true);
    await offline(browser, {width: 390, height: 844}, 'phone-light', false);
    await served(browser, {width: 1440, height: 900}, 'desktop');
    await served(browser, {width: 390, height: 844}, 'phone');
  } finally {
    await browser.close();
  }
  console.log('body_types_browser: ok');
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
