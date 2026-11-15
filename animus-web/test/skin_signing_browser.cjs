// Skin signing on an NPC's Basics tab, at 1440x900 dark and 390x844 light.
//   Paired (tool/fake_server.dart, which signs with a stand-in signature):
//     1. Upload PNG in the skin chooser sends the PNG to the server, the
//        chooser says it is signing, and the NPC wears the signed skin;
//     2. Apply sends that skin document and the NPC to the server;
//     3. with /__fake/skin-sign?fail=true the chooser shows MineSkin's
//        reason and nothing changes;
//     4. Skin from name shows while no skin is set and writes animus:skin.
//   Unpaired (the examples workspace): the chooser says a new skin is signed
//   when applied on a server, and the field says the worn upload is unsigned.
// No console errors, no sideways scrolling.
//
//   python3 tool/serve_site.py --port <site port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<site port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<site port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/skin_signing_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const zlib = require('node:zlib');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {showView, workspaceOpen, waitOnline, saveAction, startAction} = require('./support/shell.cjs');
const {npcTab} = require('./support/npc_tabs.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
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

const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'animus-skin-signing-'));
const upload = path.join(scratch, 'knight.png');
fs.writeFileSync(upload, png([40, 120, 200]));

async function draft(page) {
  await showView(page, 'code');
  const json = JSON.parse(await page.locator('.animus-code textarea').first().inputValue());
  await showView(page, 'visual');
  return json;
}

async function noSidewaysScroll(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 1, `${where}: ${overflow}px of sideways scroll`);
}

/** Opens the skin chooser on Basics and uploads [file]; returns the dialog. */
async function uploadInChooser(page, file) {
  const basics = await npcTab(page, 'Basics');
  await basics.locator('[data-skin="change"]').first().click();
  const dialog = page.getByRole('dialog', {name: 'Choose a skin'});
  await dialog.waitFor();
  const chooser = page.waitForEvent('filechooser');
  await dialog.getByRole('button', {name: 'Upload PNG', exact: true}).click();
  await (await chooser).setFiles(file);
  return dialog;
}

function watch(page) {
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  page.on('console', (message) => { if (message.type() === 'error') errors.push(`${message.text()} ${message.location().url || ''}`.trim()); });
  return errors;
}

async function paired(browser, viewport, label, dark) {
  await control('reset', {});
  await control('restore', {});
  const {code} = await control('pair?paths=both', {});
  const page = await browser.newPage({viewport, colorScheme: dark ? 'dark' : 'light'});
  page.setDefaultTimeout(15000);
  const errors = watch(page);
  await page.goto(`${site}/#/pair/${code}`);
  await page.getByTestId('pair-submit').waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  const server = await page.evaluate(() => location.hash.split('/')[2]);
  await page.evaluate((hash) => { location.hash = hash; }, `#/workspace/server-${server}/npc/${encodeURIComponent('examples/greeter')}`);
  await waitOnline(page);
  await page.locator('.animus-npc-tabs').waitFor();

  const before = await draft(page);
  const basics = await npcTab(page, 'Basics');
  if (before.skin === undefined) {
    const fromName = basics.locator('[data-npc="skin-options"]').getByRole('switch', {name: 'Skin from name'});
    await fromName.click();
    const turned = await draft(page);
    assert.equal(turned.modules['animus:skin'].fromName, true, `${label}: Skin from name writes animus:skin`);
    await (await npcTab(page, 'Basics')).locator('[data-npc="skin-options"]').getByRole('switch', {name: 'Skin from name'}).click();
    assert.equal(((await draft(page)).modules || {})['animus:skin'], undefined, `${label}: off removes the module`);
  }

  const dialog = await uploadInChooser(page, upload);
  await dialog.waitFor({state: 'hidden', timeout: 20000});
  await page.getByText('Skin signed. Apply to show it in game.').first().waitFor();
  const worn = (await draft(page)).skin;
  assert.match(worn, /^[a-f0-9]{64}$/, `${label}: the NPC wears the signed skin`);
  if (shots) await page.screenshot({path: path.join(shots, `skin-signed-${label}.png`), fullPage: false});
  await noSidewaysScroll(page, `${label} after signing`);
  await page.waitForFunction(() => /^2 changes not applied/.test(document.querySelector('.ui-save-bar .ui-save-message')?.textContent || ''));
  await saveAction(page, 'apply').click();
  await page.waitForFunction(() => /^(Applied 2 changes|Up to date)/.test(document.querySelector('.ui-save-bar .ui-save-message')?.textContent || ''));
  const skin = await control(`document?kind=skin&id=${worn}`);
  assert.equal(Buffer.from(skin.source.signature, 'base64').toString(), 'fake signature', `${label}: the server holds the signed document`);
  const npc = await control(`document?kind=npc&id=${encodeURIComponent('examples/greeter')}`);
  assert.equal(npc.source.skin, worn, `${label}: the server NPC wears it`);
  assert.equal(await (await npcTab(page, 'Basics')).locator('[data-skin="signature"]').count(), 0, `${label}: a signed skin needs no note`);

  await control('skin-sign?fail=true', {});
  const failing = await uploadInChooser(page, upload);
  await failing.getByText('MineSkin could not read the skin image: Invalid image dimensions').waitFor();
  if (shots) await page.screenshot({path: path.join(shots, `skin-refused-${label}.png`), fullPage: false});
  await failing.getByRole('button', {name: 'Cancel', exact: true}).click();
  assert.equal((await draft(page)).skin, worn, `${label}: a refusal changes nothing`);
  await control('skin-sign?fail=false', {});
  assert.deepEqual(errors, [], `${label}: console errors`);
  await page.close();
}

async function unpaired(browser, viewport, label, dark) {
  const page = await browser.newPage({viewport, colorScheme: dark ? 'dark' : 'light'});
  page.setDefaultTimeout(15000);
  const errors = watch(page);
  await page.goto(site);
  await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
  await startAction(page, 'Open examples');
  await workspaceOpen(page);
  const id = await page.evaluate(() => location.hash.split('/')[2]);
  await page.evaluate((hash) => { location.hash = hash; }, `#/workspace/${id}/npc/${encodeURIComponent('examples/greeter')}`);
  await page.locator('.animus-npc-tabs').waitFor();
  const basics = await npcTab(page, 'Basics');
  await basics.locator('[data-skin="change"]').first().click();
  const dialog = page.getByRole('dialog', {name: 'Choose a skin'});
  await dialog.locator('[data-skin="unpaired"]', {hasText: 'Not paired: a new skin is signed when applied on a server.'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await dialog.getByRole('button', {name: 'Upload PNG', exact: true}).click();
  await (await chooser).setFiles(upload);
  await dialog.waitFor({state: 'hidden'});
  const note = (await npcTab(page, 'Basics')).locator('[data-skin="signature"]');
  await note.waitFor();
  assert.equal(await note.textContent(), 'Unsigned. Signed when applied on a server.', `${label}: the field says when it is signed`);
  if (shots) await page.screenshot({path: path.join(shots, `skin-unpaired-${label}.png`), fullPage: false});
  await noSidewaysScroll(page, `${label} unpaired`);
  assert.deepEqual(errors, [], `${label}: console errors`);
  await page.close();
}

(async () => {
  const browser = await chromium.launch({headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
  try {
    await paired(browser, {width: 1440, height: 900}, 'desktop-dark', true);
    await paired(browser, {width: 390, height: 844}, 'phone-light', false);
    await unpaired(browser, {width: 1440, height: 900}, 'desktop-dark', true);
    await unpaired(browser, {width: 390, height: 844}, 'phone-light', false);
  } finally {
    await browser.close();
    fs.rmSync(scratch, {recursive: true, force: true});
  }
  console.log('skin_signing_browser: ok');
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
