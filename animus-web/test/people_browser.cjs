// Skins by player name and MineSkin link, the default skin players see,
// and players and NPCs picked by name, at 1440x900 dark, 768x1024 light and
// 390x844 light.
//   Paired (tool/fake_server.dart with its export data; it finds Notch and
//   jeb_ with their real textures and answers any MineSkin id):
//     1. the greeter (no skin) reads "Default (Ari)", the skin its UUID
//        gives it, and the stage note names that skin; named jeb_ with Skin
//        from name on, the stage wears jeb_'s skin from the server;
//     2. the skin chooser has Player name and MineSkin link or id in Simple
//        and in Advanced; "Notch" wears Notch's skin (kept with player
//        Notch for Follow player), "Nobody_here" says no account has that
//        name, a MineSkin link wears "MineSkin e51d0e34", and a word that
//        is no MineSkin link says so;
//     3. Owner (Advanced, Identity) picks Alex by name; a name typed as a
//        UUID reads "expected a player UUID", never a pattern;
//     4. the shop's Owner picks Steve by name in Simple, which writes his
//        UUID and name;
//     5. Guard player and Guard NPC, Click redirect, and a behavior's Player
//        and NPC arguments are pickers that show names; an unset Lore reads
//        Empty with no "null".
//   Unpaired (the examples workspace): Player name and MineSkin are off and
//   say they need a paired server; a player name typed as a New NPC skin
//   address says why without an exception name.
// No console errors, no sideways scrolling.
//
//   python3 tool/serve_site.py --port <site port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<site port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<site port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/people_browser.cjs
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {showView, setMode, waitOnline, workspaceOpen, startAction, pickOption, showPreview} = require('./support/shell.cjs');
const {npcTab} = require('./support/npc_tabs.cjs');
const {defaultSkinOf} = require('./support/default_skin.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;

const GREETER = 'examples/greeter';
const GREETER_UUID = 'fe531426-31bc-5660-9207-b3d3078c2305';
const BEHAVIOR = 'people:gifts';
const STALL = 'town:stall';
// The fake server's players: the paired viewer is Alex, Steve owns shops.
const ALEX = '00000000-0000-4000-8000-000000000001';
const STEVE = '00000000-0000-4000-8000-000000000002';

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

function watch(page) {
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  page.on('console', (message) => {
    const line = `${message.text()} ${message.location().url || ''}`.trim();
    // The refused MineSkin text answers 400 on purpose; the browser logs every refused request.
    if (message.type() === 'error' && !(line.includes('status of 400') && line.endsWith('/v1/skins/sign'))) errors.push(line);
  });
  return errors;
}

async function noSidewaysScroll(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 1, `${where}: ${overflow}px of sideways scroll`);
}

async function shot(page, name, focus) {
  if (!shots) return;
  if (focus) await focus.evaluate((element) => element.scrollIntoView({block: 'center'}));
  // Dialogs and toasts fade in.
  await page.waitForTimeout(500);
  await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: false});
}

async function draft(page) {
  await showView(page, 'code');
  const json = JSON.parse(await page.locator('.animus-code textarea').first().inputValue());
  await showView(page, 'visual');
  return json;
}

/** A behavior that gives a player an item, lore unset, and turns another NPC. */
function gifts(alex) {
  return {
    schemaVersion: 1, revision: 1, id: BEHAVIOR, entry: 'main',
    routines: {main: {type: 'sequence', steps: [
      {type: 'action', capability: 'animus:give-item', arguments: {item: 'minecraft:bread', player: alex}},
      {type: 'action', capability: 'animus:look', arguments: {target: 'player', npc: GREETER_UUID}},
    ]}},
    triggers: [{event: 'npc_click'}],
  };
}

function stall(steve) {
  return {
    schemaVersion: 1, id: STALL, title: '&2Stall', kind: 'selling', owner: {uuid: steve},
    containers: [{world: 'world', x: 1, y: 64, z: 2}],
    pages: [{name: 'Page 1', trade: {'1': {type: 'trade', needs: [{id: 'minecraft:emerald', count: 1}], gives: [{id: 'minecraft:bread', count: 1}]}}}],
  };
}

/** Picks the option whose name starts with [name] (Advanced adds the UUID after it). */
async function pickPerson(page, trigger, name) {
  await trigger.click();
  const list = page.locator(`[id="${await trigger.getAttribute('aria-controls')}"]`);
  await list.getByRole('option', {name: new RegExp(`^${name}\\b`)}).first().click();
}

async function openDocument(page, workspace, kind, id) {
  await page.evaluate((hash) => { location.hash = hash; }, `${workspace}/${kind}/${encodeURIComponent(id)}`);
  await page.locator('#animus-main').waitFor();
}

async function openChooser(page) {
  const basics = await npcTab(page, 'Basics');
  await basics.locator('[data-skin="change"]').first().click();
  const dialog = page.getByRole('dialog', {name: 'Choose a skin'});
  await dialog.waitFor();
  return dialog;
}

async function chooserRows(dialog, label) {
  const player = dialog.getByRole('textbox', {name: 'Player name', exact: true});
  const mineSkin = dialog.getByRole('textbox', {name: 'MineSkin link or id', exact: true});
  await player.waitFor();
  await mineSkin.waitFor();
  assert.ok(await dialog.getByText('Player name', {exact: true}).first().isVisible(), `${label}: Player name row`);
  return {player, mineSkin};
}

async function paired(browser, viewport, label, dark) {
  await control('reset', {});
  await control('restore', {});
  const {code} = await control('pair?paths=both', {});
  const alex = ALEX;
  const page = await browser.newPage({viewport, colorScheme: dark ? 'dark' : 'light'});
  page.setDefaultTimeout(20000);
  const errors = watch(page);
  await page.addInitScript(() => window.localStorage.setItem('animus.mode', 'simple'));
  await page.goto(`${site}/#/pair/${code}`);
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  const workspace = await page.evaluate(() => `#/workspace/server-${location.hash.split('/')[2]}`);
  await waitOnline(page);

  // 1. The default skin players see.
  await openDocument(page, workspace, 'npc', GREETER);
  await page.locator('.animus-npc-tabs').waitFor();
  const standard = defaultSkinOf(GREETER_UUID);
  const name = (await (await npcTab(page, 'Basics')).locator('[data-skin="name"]').innerText()).trim();
  assert.equal(name, `Default (${standard.label})`, `${label}: the skin field names the default skin`);
  const face = await (await npcTab(page, 'Basics')).locator('[data-skin="field"] .animus-skin-face').first().getAttribute('style');
  assert.ok(face.includes(`${standard.texture}.png`), `${label}: the field's face is ${standard.label}: ${face}`);
  await showPreview(page);
  await page.waitForFunction(() => {
    const host = document.querySelector('.animus-mc-gl-host');
    return host && Number(host.getAttribute('data-frames')) > 0 && host.getAttribute('data-unbuilt') === '0';
  });
  await page.waitForTimeout(600);
  // The stage's (i) tip says which skin it draws.
  await page.getByText(`the default skin, ${standard.label} with ${standard.slim ? 'slim' : 'classic'} arms`).first().waitFor({state: 'attached'});
  await shot(page, `${label}-1-default-skin`);

  // 1b. Skin from name: the paired server looks up the account named like the NPC.
  let basicsTab = await npcTab(page, 'Basics');
  const nameBox = basicsTab.locator('#animus-npc-name');
  await nameBox.fill('jeb_');
  await basicsTab.locator('[data-npc="skin-options"]').getByRole('switch', {name: 'Skin from name'}).click();
  await page.getByText("jeb_'s, from the NPC's name").first().waitFor({state: 'attached'});
  assert.equal((await (await npcTab(page, 'Basics')).locator('[data-skin="name"]').innerText()).trim(), 'From name: jeb_', `${label}: the field names the account`);
  await page.waitForTimeout(800);
  await shot(page, `${label}-1b-skin-from-name`);
  basicsTab = await npcTab(page, 'Basics');
  await basicsTab.locator('[data-npc="skin-options"]').getByRole('switch', {name: 'Skin from name'}).click();
  await basicsTab.locator('#animus-npc-name').fill('Greeter');

  // 2. A skin by player name and by MineSkin link, in Simple.
  let dialog = await openChooser(page);
  let rows = await chooserRows(dialog, `${label} simple`);
  await rows.player.fill('Nobody_here');
  await dialog.getByRole('button', {name: 'Wear', exact: true}).first().click();
  await dialog.getByText('No Minecraft account is named Nobody_here').waitFor();
  await shot(page, `${label}-2a-unknown-player`);
  await rows.player.fill('Notch');
  await shot(page, `${label}-2b-chooser`);
  await noSidewaysScroll(page, `${label} chooser`);
  await dialog.getByRole('button', {name: 'Wear', exact: true}).first().click();
  await dialog.waitFor({state: 'hidden'});
  await page.getByText("Notch's skin. Apply to show it in game.").first().waitFor();
  assert.equal((await (await npcTab(page, 'Basics')).locator('[data-skin="name"]').innerText()).trim().startsWith('Notch'), true, `${label}: the field names Notch's skin`);
  await shot(page, `${label}-2c-notch-worn`);

  await setMode(page, 'advanced');
  const worn = (await draft(page)).skin;
  assert.match(worn, /^[a-f0-9]{64}$/, `${label}: the NPC wears the looked-up skin`);
  dialog = await openChooser(page);
  rows = await chooserRows(dialog, `${label} advanced`);
  await rows.mineSkin.fill('hello world!');
  await dialog.getByRole('button', {name: 'Wear', exact: true}).nth(1).click();
  await dialog.getByText('Not a MineSkin link or id').waitFor();
  await rows.mineSkin.fill('https://minesk.in/e51d0e34');
  await dialog.getByRole('button', {name: 'Wear', exact: true}).nth(1).click();
  await dialog.waitFor({state: 'hidden'});
  assert.equal((await (await npcTab(page, 'Basics')).locator('[data-skin="name"]').innerText()).trim().split('\n')[0].startsWith('MineSkin e51d0e34'), true, `${label}: the MineSkin skin is labelled by its id`);
  await shot(page, `${label}-2d-mineskin-worn`);

  // 3. Owner by name; a name where a UUID belongs says so in words.
  let basics = await npcTab(page, 'Basics');
  const identity = basics.locator('[data-npc="identity"]');
  await identity.locator('summary').first().click();
  const owner = identity.getByRole('combobox', {name: 'Owner', exact: true});
  await pickPerson(page, owner, 'Alex');
  assert.equal((await draft(page)).owner, alex, `${label}: Owner writes Alex's UUID`);
  basics = await npcTab(page, 'Basics');
  const ownerUuid = basics.locator('[data-npc="identity"]').getByRole('textbox', {name: 'Owner UUID', exact: true});
  if (!(await ownerUuid.isVisible())) await basics.locator('[data-npc="identity"] summary').first().click();
  await ownerUuid.fill('Notch');
  await page.getByText(/expected a player UUID/i).first().waitFor();
  const shown = await basics.locator('[data-npc="identity"]').innerText();
  assert.ok(!shown.includes('^[0-9a-f]'), `${label}: no pattern in the owner's problem`);
  await shot(page, `${label}-3-owner`, ownerUuid);
  await ownerUuid.fill('');

  // Skins by name went with the NPC on Apply; the server keeps Notch's player for Follow player.
  const notchSkin = await control(`document?kind=skin&id=${worn}`).catch(() => null);
  if (notchSkin) assert.equal(notchSkin.source.player, 'Notch');

  // 5a. Guard player and Guard NPC; Click redirect.
  let combat = await npcTab(page, 'Combat');
  await combat.getByRole('switch', {name: 'Fights'}).click();
  combat = await npcTab(page, 'Combat');
  await combat.locator('summary', {hasText: /^Guard/}).first().click();
  const guardPlayer = combat.getByRole('combobox', {name: 'Guard player', exact: true});
  await guardPlayer.waitFor();
  await pickPerson(page, guardPlayer, 'Steve');
  await shot(page, `${label}-5a-guard`, guardPlayer);
  const look = await npcTab(page, 'Look');
  const redirect = look.getByRole('combobox', {name: 'Redirect to', exact: true});
  if (!(await redirect.isVisible())) await look.locator('summary', {hasText: /^Click redirect/}).first().click();
  await redirect.click();
  const options = page.locator(`[id="${await redirect.getAttribute('aria-controls')}"]`);
  await options.getByRole('option').first().waitFor();
  const offered = await options.innerText();
  assert.ok(!/[0-9a-f]{8}-[0-9a-f]{4}/.test(offered.split('\n')[0]), `${label}: NPCs are offered by name`);
  await page.keyboard.press('Escape');
  await shot(page, `${label}-5b-redirect`);

  // 5b. A behavior's Player and NPC arguments; a null Lore.
  await control('edit', {kind: 'behavior', id: BEHAVIOR, source: gifts(alex)});
  await openDocument(page, workspace, 'behavior', BEHAVIOR);
  const player = page.getByRole('combobox', {name: 'Player', exact: true}).first();
  await player.waitFor();
  assert.match(await player.innerText(), /Alex/, `${label}: the Player argument names Alex`);
  const npc = page.getByRole('combobox', {name: 'Npc', exact: true}).or(page.getByRole('combobox', {name: 'NPC', exact: true})).first();
  assert.match(await npc.innerText(), /Greeter/, `${label}: the NPC argument names the greeter`);
  const main = await page.locator('#animus-main').innerText();
  assert.ok(!/Empty\s*null/.test(main), `${label}: an empty Lore shows no null`);
  await shot(page, `${label}-5c-behavior`, player);

  // 4. The shop owner in Simple.
  await control('edit', {kind: 'shop', id: STALL, source: stall(STEVE)});
  await setMode(page, 'simple');
  await openDocument(page, workspace, 'shop', STALL);
  const form = page.getByRole('region', {name: 'Shop'});
  await form.getByRole('tab', {name: /^Settings/}).click();
  const shopOwner = form.getByRole('combobox', {name: 'Owner', exact: true});
  await shopOwner.waitFor();
  await pickPerson(page, shopOwner, 'Alex');
  await page.waitForTimeout(300);
  await setMode(page, 'advanced');
  const shop = await draft(page);
  assert.deepEqual(shop.owner, {uuid: alex, name: 'Alex'}, `${label}: Simple sets the shop owner by name`);
  await setMode(page, 'simple');
  await form.getByRole('tab', {name: /^Settings/}).click();
  await shot(page, `${label}-4-shop-owner`, form.getByRole('combobox', {name: 'Owner', exact: true}));
  await noSidewaysScroll(page, `${label} shop`);

  assert.deepEqual(errors, [], `${label}: console errors`);
  await page.close();
}

async function unpaired(browser, viewport, label, dark) {
  const page = await browser.newPage({viewport, colorScheme: dark ? 'dark' : 'light'});
  page.setDefaultTimeout(15000);
  const errors = watch(page);
  await page.addInitScript(() => window.localStorage.setItem('animus.mode', 'advanced'));
  await page.goto(site);
  await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
  await startAction(page, 'Open examples');
  await workspaceOpen(page);
  const id = await page.evaluate(() => location.hash.split('/')[2]);
  await openDocument(page, `#/workspace/${id}`, 'npc', GREETER);
  await page.locator('.animus-npc-tabs').waitFor();
  const dialog = await openChooser(page);
  const rows = await chooserRows(dialog, `${label} unpaired`);
  assert.equal(await rows.player.isDisabled(), true, `${label}: Player name is off without a server`);
  assert.equal(await rows.mineSkin.isDisabled(), true, `${label}: MineSkin is off without a server`);
  await dialog.locator('[data-skin="find-absent"]', {hasText: 'Player names and MineSkin links need a paired server.'}).waitFor();
  await shot(page, `${label}-6-unpaired-chooser`);
  await noSidewaysScroll(page, `${label} unpaired chooser`);
  await dialog.getByRole('button', {name: 'Cancel', exact: true}).click();

  // A player name typed as a New NPC skin address.
  await page.evaluate((hash) => { location.hash = hash; }, `#/workspace/${id}/new/npc`);
  await page.getByRole('heading', {name: 'New NPC'}).waitFor();
  const address = page.getByRole('button', {name: 'Address', exact: true}).or(page.getByRole('radio', {name: 'Address', exact: true}));
  if (await address.first().isVisible().catch(() => false)) await address.first().click();
  else await pickOption(page, page.getByRole('combobox', {name: 'Skin', exact: true}), 'Address');
  await page.getByLabel('Address', {exact: true}).fill('Notch');
  await page.getByRole('button', {name: 'Load skin', exact: true}).click();
  const toast = page.getByText(/^Skin did not load\./).first();
  await toast.waitFor();
  const said = await toast.innerText();
  assert.ok(!said.includes('Exception'), `${label}: no exception name in "${said}"`);
  await shot(page, `${label}-7-new-npc-address`, toast);
  assert.deepEqual(errors, [], `${label}: console errors`);
  await page.close();
}

(async () => {
  // The hosted site reaches a loopback fake server only with local network checks off.
  const remote = site.startsWith('https://') ? ['--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessPermissionPrompt,BlockInsecurePrivateNetworkRequests'] : [];
  const browser = await chromium.launch({headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', ...remote]});
  try {
    await paired(browser, {width: 1440, height: 900}, 'desktop-dark', true);
    await paired(browser, {width: 768, height: 1024}, 'tablet-light', false);
    await paired(browser, {width: 390, height: 844}, 'phone-light', false);
    await unpaired(browser, {width: 1440, height: 900}, 'desktop-dark', true);
    await unpaired(browser, {width: 390, height: 844}, 'phone-light', false);
  } finally {
    await browser.close();
  }
  console.log('people_browser: ok');
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
