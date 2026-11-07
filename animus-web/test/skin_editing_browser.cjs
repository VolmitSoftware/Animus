// Skin editing in the built site, at 1440x900 and 390x844 in the dark and
// light themes. Writes its own data folder (no fake server): two unsigned
// skins (classic, kept PNG under editor/skins/; slim, no PNG), two player
// NPCs wearing the classic one, a group giving the slim one and an NPC in
// that group. Then:
//   1. an NPC's skin dropdown shows the faces of loaded skins and "+ New skin"
//      and "Manage skins"; picking the slim skin and removing it write `skin`;
//   2. the Arms switch writes a new skin document (its id the hash of its
//      text) worn by that NPC alone, and one Undo reverts it;
//   3. the skin document's Arms switch moves both wearers to the new document,
//      the old one goes, the page follows to the new id, Undo brings it back;
//   4. Delete on a skin lists its wearers;
//   5. a body type of minecraft:llama on a skinned NPC asks first: Cancel
//      keeps the player body, Remove skin changes both in one step; for an
//      NPC whose skin comes from its group the group loses it too.
// No console errors, no sideways scrolling.
//
//   python3 tool/serve_site.py --port <WEB_PORT> --directory <site> &
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<WEB_PORT> ANIMUS_SCREENSHOTS=<scratch folder> \
//     node test/skin_editing_browser.cjs
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const zlib = require('node:zlib');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {topbarAction} = require('./support/shell.cjs');
const {choose, openDropdown, valueOf} = require('./support/select.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;

/** A skin document the way the editor writes one: its id is the SHA-256 of the text. */
function skinDocument(slim, profile) {
  const raw = Buffer.from(JSON.stringify({profileId: profile, profileName: 'animus-editor', textures: {SKIN: slim ? {metadata: {model: 'slim'}} : {}}})).toString('base64');
  const text = `${JSON.stringify({textureRaw: raw, signature: null}, null, 2)}\n`;
  return {id: crypto.createHash('sha256').update(text).digest('hex'), text};
}

/** A solid 64x64 RGBA PNG. */
function png(rgb) {
  const crc = (buffer) => {
    let c = ~0;
    for (const byte of buffer) {
      c ^= byte;
      for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
    }
    return ~c >>> 0;
  };
  const chunk = (type, data) => {
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type), data]);
    const sum = Buffer.alloc(4);
    sum.writeUInt32BE(crc(body));
    return Buffer.concat([length, body, sum]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(64, 0);
  header.writeUInt32BE(64, 4);
  header.set([8, 6, 0, 0, 0], 8);
  const rows = Buffer.alloc(64 * (1 + 64 * 4));
  for (let y = 0; y < 64; y++) {
    for (let x = 0; x < 64; x++) rows.set([...rgb, 255], y * (1 + 256) + 1 + x * 4);
  }
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), chunk('IDAT', zlib.deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]);
}

const classic = skinDocument(false, 'a'.repeat(32));
const slim = skinDocument(true, 'b'.repeat(32));

function npc(group, id, name, extra) {
  return {
    schemaVersion: 1, revision: 1, uuid: crypto.randomUUID(), id, group, name, type: 'minecraft:player', spawned: false,
    position: {world: 'world', x: 0.5, y: 64, z: 0.5, yaw: 0, pitch: 0}, ...extra,
  };
}

function writeFixture(folder) {
  const write = (file, value) => {
    fs.mkdirSync(path.dirname(path.join(folder, file)), {recursive: true});
    fs.writeFileSync(path.join(folder, file), typeof value === 'string' || Buffer.isBuffer(value) ? value : `${JSON.stringify(value, null, 2)}\n`);
  };
  write(`skins/${classic.id}.json`, classic.text);
  write(`skins/${slim.id}.json`, slim.text);
  write(`editor/skins/${classic.id}.png`, png([200, 40, 120]));
  write('npcs/town/anna.json', npc('town', 'anna', 'Anna', {skin: classic.id}));
  write('npcs/town/bert.json', npc('town', 'bert', 'Bert', {skin: classic.id}));
  write('npcs/town/cara.json', npc('town', 'cara', 'Cara', {groups: ['guards']}));
  write('groups/guards.json', {schemaVersion: 1, revision: 1, id: 'guards', values: {skin: slim.id}});
}

async function shot(page, name) {
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `skin-editing-${name}.png`), fullPage: true});
}

async function noSidewaysScroll(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  assert.ok(overflow <= 1, `${where}: the page scrolls sideways by ${overflow}px`);
}

async function codeOf(page) {
  await page.getByRole('tab', {name: 'Code'}).click();
  const text = await page.locator('.animus-code textarea').inputValue();
  await page.getByRole('tab', {name: 'Visual'}).click();
  return JSON.parse(text);
}

async function part(page, id) {
  const details = page.locator(`details[data-section="${id}"]`);
  if (!(await details.evaluate((element) => element.open))) await details.locator('summary').first().click();
  return details;
}

async function openDocument(page, workspace, kind, id) {
  await page.evaluate((hash) => { location.hash = hash; }, `#/workspace/${workspace}/${kind}/${encodeURIComponent(id)}`);
  await page.locator('#animus-main h1').waitFor();
}

const settle = (page) => page.waitForTimeout(300);

async function run(browser, folder, viewport, theme, tag) {
  const context = await browser.newContext({viewport});
  await context.addInitScript((mode) => window.localStorage.setItem('animus.theme', mode), theme);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  page.on('console', (message) => { if (message.type() === 'error' && !/404|Failed to load resource/.test(message.text())) errors.push(message.text()); });

  await page.goto(`${site}/`);
  await page.getByRole('heading', {name: 'From a data folder'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(folder);
  await page.waitForFunction(() => /#\/workspace\/[^/?]+/.test(location.hash));
  const workspace = /#\/workspace\/([^/?]+)/.exec(page.url())[1];

  // 1. Pick with faces, remove.
  await openDocument(page, workspace, 'npc', 'town/anna');
  let look = await part(page, 'appearance');
  const pick = look.locator('#animus-skin-pick');
  await pick.scrollIntoViewIfNeeded();
  assert.equal(await valueOf(pick), classic.id);
  assert.equal(await pick.locator('.animus-select-thumb').count(), 1, 'the trigger shows the chosen face');
  const listbox = await openDropdown(pick);
  await listbox.locator(`[role="option"][data-value="${classic.id}"] .animus-select-thumb`).waitFor();
  assert.equal(await listbox.locator(`[role="option"][data-value="${slim.id}"] .animus-select-thumb`).count(), 0, 'no face without a PNG');
  await listbox.getByText('+ New skin').waitFor();
  await listbox.getByText('Manage skins').waitFor();
  await shot(page, `${tag}-01-dropdown`);
  await page.keyboard.press('Escape');
  await choose(pick, slim.id);
  await settle(page);
  assert.equal((await codeOf(page)).skin, slim.id);
  look = await part(page, 'appearance');
  await look.getByRole('button', {name: 'Remove skin'}).click();
  await settle(page);
  assert.equal((await codeOf(page)).skin, undefined, 'Remove skin drops the field');
  // Successive edits of one field share an undo step.
  await topbarAction(page, 'Undo');
  await settle(page);
  assert.equal((await codeOf(page)).skin, classic.id);

  // 2. Arms from the NPC: a new document for Anna only.
  look = await part(page, 'appearance');
  const arms = look.getByRole('checkbox', {name: 'Slim arms (Alex)'});
  await arms.scrollIntoViewIfNeeded();
  await arms.click();
  await settle(page);
  const own = (await codeOf(page)).skin;
  assert.match(own, /^[a-f0-9]{64}$/);
  assert.notEqual(own, classic.id);
  assert.notEqual(own, slim.id);
  await openDocument(page, workspace, 'skin', own);
  const ownText = await (async () => {
    await page.getByRole('tab', {name: 'Code'}).click();
    const text = await page.locator('.animus-code textarea').inputValue();
    await page.getByRole('tab', {name: 'Visual'}).click();
    return text;
  })();
  assert.equal(crypto.createHash('sha256').update(ownText).digest('hex'), own, 'the new id is the hash of its text');
  await page.getByRole('region', {name: 'Skin'}).getByText('Slim arms (Alex)').first().waitFor();
  await page.getByRole('region', {name: 'Skin'}).locator('.animus-skin-face.is-large[role="img"]').waitFor();
  await openDocument(page, workspace, 'npc', 'town/bert');
  assert.equal((await codeOf(page)).skin, classic.id, 'Bert keeps the classic skin');
  await topbarAction(page, 'Undo');
  await settle(page);
  await openDocument(page, workspace, 'npc', 'town/anna');
  assert.equal((await codeOf(page)).skin, classic.id, 'one Undo reverts the model change');

  // 3. Arms from the skin document: every wearer moves.
  await openDocument(page, workspace, 'skin', classic.id);
  const summary = page.getByRole('region', {name: 'Skin'});
  await summary.getByRole('button', {name: /Anna \(town\/anna\)/}).waitFor();
  await summary.getByRole('button', {name: /Bert \(town\/bert\)/}).waitFor();
  await summary.getByRole('checkbox', {name: 'Slim arms (Alex)'}).click();
  await page.waitForFunction((old) => !decodeURIComponent(location.hash).includes(old), classic.id);
  const moved = decodeURIComponent(page.url()).split('/skin/')[1].split('?')[0];
  assert.match(moved, /^[a-f0-9]{64}$/);
  await page.getByRole('region', {name: 'Skin'}).getByRole('button', {name: /Bert \(town\/bert\)/}).waitFor();
  await shot(page, `${tag}-02-moved`);
  await noSidewaysScroll(page, `${tag} skin document`);
  await openDocument(page, workspace, 'npc', 'town/bert');
  assert.equal((await codeOf(page)).skin, moved);
  await topbarAction(page, 'Undo');
  await settle(page);
  assert.equal((await codeOf(page)).skin, classic.id, 'Undo moves the wearers back');

  // 4. Delete lists the wearers; Escape keeps it.
  await openDocument(page, workspace, 'skin', classic.id);
  await page.locator('#animus-doc-delete').click();
  await page.getByText('Worn by Anna (town/anna) and Bert (town/bert); they would name a missing skin.', {exact: false}).waitFor();
  await shot(page, `${tag}-03-delete`);
  // The question takes focus once it is drawn; Escape counts only there.
  await page.waitForFunction(() => document.activeElement && document.activeElement.closest('.animus-confirm'));
  await page.keyboard.press('Escape');
  await page.locator('#animus-doc-delete').waitFor();

  // 5. The llama fix-it, own skin.
  await openDocument(page, workspace, 'npc', 'town/anna');
  let body = await part(page, 'body');
  let type = body.getByRole('combobox', {name: 'Body type'});
  await type.scrollIntoViewIfNeeded();
  await type.fill('minecraft:llama');
  const ask = body.getByRole('alertdialog');
  await ask.getByText('A skin needs a player body. Remove the skin and use minecraft:llama?').waitFor();
  await shot(page, `${tag}-04-llama-ask`);
  await noSidewaysScroll(page, `${tag} llama ask`);
  await ask.getByRole('button', {name: 'Cancel'}).click();
  await body.getByText('Kept minecraft:player: minecraft:llama cannot wear a skin.').waitFor();
  let json = await codeOf(page);
  assert.equal(json.type, 'minecraft:player');
  assert.equal(json.skin, classic.id);
  body = await part(page, 'body');
  type = body.getByRole('combobox', {name: 'Body type'});
  await type.fill('minecraft:llama');
  await body.getByRole('alertdialog').getByRole('button', {name: 'Remove skin'}).click();
  await settle(page);
  json = await codeOf(page);
  assert.equal(json.type, 'minecraft:llama');
  assert.equal(json.skin, undefined);
  await topbarAction(page, 'Undo');
  await settle(page);
  json = await codeOf(page);
  assert.equal(json.type, 'minecraft:player', 'one Undo restores the type');
  assert.equal(json.skin, classic.id, 'and the skin');

  // Group-given skin.
  await openDocument(page, workspace, 'npc', 'town/cara');
  body = await part(page, 'body');
  type = body.getByRole('combobox', {name: 'Body type'});
  await type.fill('minecraft:llama');
  const groupAsk = body.getByRole('alertdialog');
  await groupAsk.getByText('Also removes it from group guards.', {exact: false}).waitFor();
  await groupAsk.getByRole('button', {name: 'Remove skin'}).click();
  await settle(page);
  assert.equal((await codeOf(page)).type, 'minecraft:llama');
  await openDocument(page, workspace, 'group', 'guards');
  assert.equal((await codeOf(page)).values.skin, undefined, 'the group lost its skin in the same step');
  await topbarAction(page, 'Undo');
  await settle(page);
  assert.equal((await codeOf(page)).values.skin, slim.id);

  // A llama typed in Code shows the server's refusal on the NPC.
  await openDocument(page, workspace, 'npc', 'town/cara');
  await page.getByRole('tab', {name: 'Code'}).click();
  const area = page.locator('.animus-code textarea');
  const text = (await area.inputValue()).replace('"minecraft:player"', '"minecraft:llama"');
  await area.fill(text);
  await page.getByRole('button', {name: 'Apply to draft'}).click();
  await settle(page);
  await page.getByRole('tab', {name: 'Visual'}).click();
  body = await part(page, 'appearance');
  await body.getByText('textures require a player body (the skin comes from group guards)', {exact: false}).first().waitFor();
  await shot(page, `${tag}-05-group-skin-error`);
  await noSidewaysScroll(page, `${tag} group skin error`);

  assert.deepEqual(errors, [], `${tag}: console errors`);
  await context.close();
}

(async () => {
  const folder = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'animus-skin-editing-')), 'data');
  writeFixture(folder);
  const browser = await chromium.launch({headless: true});
  try {
    await run(browser, folder, {width: 1440, height: 900}, 'dark', 'desktop-dark');
    await run(browser, folder, {width: 1440, height: 900}, 'light', 'desktop-light');
    await run(browser, folder, {width: 390, height: 844}, 'dark', 'phone-dark');
    await run(browser, folder, {width: 390, height: 844}, 'light', 'phone-light');
    console.log('skin_editing_browser: ok');
  } finally {
    await browser.close();
    fs.rmSync(path.dirname(folder), {recursive: true, force: true});
  }
})().catch((error) => { console.error(error); process.exit(1); });
