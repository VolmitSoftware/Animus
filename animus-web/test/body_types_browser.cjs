// Changing an NPC's body type in the inspector, at desktop width in the dark
// theme and phone width in the light one. The hero (a skinned, fully equipped
// player, seated first) becomes a horse: the skin question removes the skin,
// the seated pose a horse cannot show is named with a way to remove it, the
// skin picker gives way to a note, player-only slots warn, the horse's own settings are typed dropdowns that reach the Code view
// and the stage, and the pose row is gone. Every other body then shows only
// its own settings and traits. Last, a server NPC turned into a horse is
// applied to tool/fake_server.dart. No console errors, no sideways scroll.
//
//   python3 tool/serve_site.py --port <site port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<site port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<site port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/body_types_browser.cjs
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {closeDrawer, sidebar, topbarAction} = require('./support/shell.cjs');
const {choose, optionValues} = require('./support/select.cjs');

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

async function openSection(page, id) {
  const section = page.locator(`details[data-section="${id}"]`);
  if (!(await section.evaluate((details) => details.open))) await section.locator(':scope > summary').first().click();
  return section;
}

async function setType(page, type, {removeSkin = false} = {}) {
  const body = await openSection(page, 'body');
  const input = body.getByLabel('Body type', {exact: true});
  await input.fill(type);
  await input.press('Enter');
  await page.keyboard.press('Escape');
  // A skinned NPC asks first; removing the skin changes the type with it.
  if (removeSkin) await body.getByRole('button', {name: 'Remove skin', exact: true}).click();
  // The part has redrawn for the new body once its summary names it.
  await body.locator(':scope > summary', {hasText: type.replace('minecraft:', '').replace(/_/g, ' ')}).waitFor();
  return body;
}

async function draft(page) {
  await page.getByRole('tab', {name: 'Code'}).click();
  const json = JSON.parse(await page.locator('.animus-code textarea').first().inputValue());
  await page.getByRole('tab', {name: 'Visual'}).click();
  return json;
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
  await page.getByRole('heading', {name: 'From a data folder'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(folder);
  await (await sidebar(page)).getByText('Hero', {exact: true}).waitFor();
  await closeDrawer(page);
  if (await page.evaluate(() => document.documentElement.classList.contains('dark')) !== dark) await topbarAction(page, dark ? 'Use dark theme' : 'Use light theme');
  const workspace = page.url().split('#')[1].split('?')[0];
  await page.goto(`${site}/#${workspace}/npc/players%2Fhero?view=visual`);
  await page.locator('h1').getByText('Hero', {exact: true}).waitFor();

  // The seated player becomes a horse.
  await choose((await openSection(page, 'body')).locator('button#animus-npc-pose'), 'sitting');
  assert.equal((await draft(page)).pose, 'sitting', `${label}: seated first`);
  const body = await setType(page, 'minecraft:horse', {removeSkin: true});
  const left = page.getByRole('group', {name: 'Fields this body cannot show'});
  await left.getByText('The sitting pose shows only on minecraft:player bodies.').waitFor();
  assert.equal(await left.getByText('Skins show only', {exact: false}).count(), 0, `${label}: the question took the skin`);
  assert.equal(await body.locator('button#animus-npc-pose').count(), 0, `${label}: no pose row on a horse`);
  const appearance = await openSection(page, 'appearance');
  await appearance.getByText('Only player bodies wear skins.').waitFor();
  assert.equal(await page.getByRole('group', {name: 'Built-in skins'}).count(), 0, `${label}: no skin picker on a horse`);
  assert.equal(await appearance.locator('[data-slot-grid="animus-equipment"] .is-warning, [data-slot-grid="animus-equipment"] .is-error').count(), 6, `${label}: the six player slots warn`);
  await left.getByRole('button', {name: 'Remove pose'}).click();
  await left.waitFor({state: 'detached'});
  const converted = await draft(page);
  assert.deepEqual([converted.type, converted.skin, converted.pose], ['minecraft:horse', undefined, undefined], `${label}: skin and pose are gone`);

  // The horse's own settings are typed dropdowns.
  const horse = (await openSection(page, 'body')).getByRole('group', {name: 'Horse settings'});
  const color = horse.locator('button[role="combobox"]').first();
  assert.deepEqual((await optionValues(color)).filter(Boolean), ['white', 'creamy', 'chestnut', 'brown', 'black', 'gray', 'dark_brown']);
  await choose(color, 'chestnut');
  await choose(horse.locator('button[role="combobox"]').nth(1), 'white_dots');
  assert.deepEqual((await draft(page)).horse, {color: 'chestnut', markings: 'white_dots'}, `${label}: the settings reach the definition`);
  const traits = await openSection(page, 'body');
  assert.equal(await traits.getByRole('checkbox', {name: 'Listed', exact: true}).count(), 0, `${label}: no tab list trait on a horse`);
  assert.equal(await traits.getByRole('checkbox', {name: 'Using item', exact: true}).count(), 0, `${label}: no raised item trait on a horse`);
  await noSidewaysScroll(page, `${label} horse inspector`);

  await page.getByRole('tab', {name: 'Preview'}).click();
  await page.waitForFunction(() => {
    const host = document.querySelector('.animus-mc-gl-host');
    return host && Number(host.getAttribute('data-frames')) > 0 && host.getAttribute('data-unbuilt') === '0';
  });
  await noSidewaysScroll(page, `${label} horse preview`);
  await page.getByRole('tab', {name: 'Visual'}).click();

  // Every other body: its own settings and nothing left from the horse but the warning to remove it.
  for (const [type, settings, baby] of [['minecraft:llama', 'Llama', true], ['minecraft:frog', 'Frog', true], ['minecraft:armadillo', 'Armadillo', true],
    ['minecraft:ender_dragon', 'Ender dragon', false], ['minecraft:turtle', null, true], ['minecraft:armor_stand', null, false]]) {
    const section = await setType(page, type);
    await left.getByText('Only for minecraft:horse bodies.').waitFor();
    if (settings) await section.getByRole('group', {name: `${settings} settings`}).waitFor();
    assert.equal(await section.locator('[data-settings]').count(), settings ? 1 : 0, `${label} ${type}: only its own settings`);
    assert.equal(await section.getByRole('checkbox', {name: 'Baby', exact: true}).count() > 0, baby, `${label} ${type}: baby trait`);
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
  await page.locator('[data-testid="pair-fingerprint"]', {hasText: 'sha256:'}).waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await page.getByText('revision 1').first().waitFor();
  await page.getByRole('checkbox', {name: 'examples/greeter', exact: true}).check();
  await page.getByRole('button', {name: 'Open 1'}).click();
  await page.waitForFunction(() => decodeURIComponent(location.hash).includes('/npc/examples/greeter'));
  await setType(page, 'minecraft:horse');
  const horse = (await openSection(page, 'body')).getByRole('group', {name: 'Horse settings'});
  await choose(horse.locator('button[role="combobox"]').first(), 'black');
  await page.locator('[data-testid="draft-count"]', {hasText: '1 changed draft'}).waitFor();
  await page.getByRole('button', {name: 'Apply 1'}).click();
  await page.locator('[data-testid="draft-count"]', {hasText: 'No changed drafts'}).waitFor();
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
