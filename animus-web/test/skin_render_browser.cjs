// Signed skins in the Preview stage. Writes a data folder of two player NPCs
// (classic and slim) whose synthetic skin documents have the on-disk shape
// (signature, skinName, textureRaw), with texture URLs on textures.minecraft.net
// that the browser context answers with generated PNGs. Then, at desktop and
// phone widths, in the dark theme:
//   1. server: pair with the fake server serving that folder, open each NPC
//      from the catalog, and wait for the stage to wear the fetched texture
//      (its colours, absent from the default skins, cover the body);
//   2. a failed texture: the status line says so, its tip says why, and the
//      header's Retry skin loads it;
//   3. offline: open a zip of the folder that keeps editor/skins/<hash>.png,
//      and the stage wears that PNG.
// No console errors other than the forced failure's own 503 line.
//
//   node test/skin_render_browser.cjs --write-fixture <folder>   # once, for the fake server
//   dart run tool/fake_server.dart --port=<p> --origin=<site> --data=<folder>
//   PLAYWRIGHT_MODULE=... ANIMUS_WEB_URL=<site> ANIMUS_FAKE_URL=http://127.0.0.1:<p> \
//     ANIMUS_SKIN_FIXTURE=<folder> ANIMUS_SCREENSHOTS=<dir> node test/skin_render_browser.cjs
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {execFileSync} = require('node:child_process');

const players = [
  {id: 'skins/classic', name: 'Classic Wearer', slim: false, top: [224, 16, 16], bottom: [224, 16, 224]},
  {id: 'skins/slim', name: 'Slim Wearer', slim: true, top: [16, 16, 224], bottom: [224, 16, 224]},
];
const hashOf = (player) => crypto.createHash('sha256').update(`synthetic ${player.id}`).digest('hex');
const textureOf = (player) => crypto.createHash('sha256').update(`texture ${player.id}`).digest('hex');

function writeFixture(folder) {
  for (const player of players) {
    const [group, id] = player.id.split('/');
    const textures = {timestamp: 0, profileName: 'Synthetic', textures: {SKIN: {url: `http://textures.minecraft.net/texture/${textureOf(player)}`, ...(player.slim ? {metadata: {model: 'slim'}} : {})}}};
    const skin = {signature: 'synthetic'.padEnd(684, '='), skinName: crypto.randomUUID(), textureRaw: Buffer.from(JSON.stringify(textures)).toString('base64')};
    fs.mkdirSync(path.join(folder, 'skins'), {recursive: true});
    fs.mkdirSync(path.join(folder, 'npcs', group), {recursive: true});
    fs.writeFileSync(path.join(folder, 'skins', `${hashOf(player)}.json`), JSON.stringify(skin, null, 2));
    fs.writeFileSync(path.join(folder, 'npcs', group, `${id}.json`), JSON.stringify({
      schemaVersion: 1, revision: 1, uuid: crypto.randomUUID(), id, group, name: player.name, type: 'minecraft:player', spawned: false,
      position: {world: 'world', x: 0.5, y: 64, z: 0.5, yaw: 0, pitch: 0}, skin: hashOf(player),
    }, null, 2));
  }
}

if (process.argv[2] === '--write-fixture') {
  writeFixture(path.resolve(process.argv[3]));
  process.exit(0);
}

const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {startAction, workspaceOpen} = require('./support/shell.cjs');
const {showPreview, stageLine, waitForStageTip} = require('./support/stage.cjs');
const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const fixture = process.env.ANIMUS_SKIN_FIXTURE || (() => { throw new Error('Set ANIMUS_SKIN_FIXTURE to the folder written by --write-fixture'); })();
const shots = process.env.ANIMUS_SCREENSHOTS;

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

/// A 64x64 skin PNG: [top] over the head and body rows, [bottom] below.
async function skinPng(page, top, bottom) {
  const base64 = await page.evaluate(([a, b]) => {
    const canvas = document.createElement('canvas');
    canvas.width = 64;
    canvas.height = 64;
    const context = canvas.getContext('2d');
    context.fillStyle = `rgb(${a.join(',')})`;
    context.fillRect(0, 0, 64, 32);
    context.fillStyle = `rgb(${b.join(',')})`;
    context.fillRect(0, 32, 64, 32);
    return canvas.toDataURL('image/png').split(',')[1];
  }, [top, bottom]);
  return Buffer.from(base64, 'base64');
}

/// Stage pixels close in hue to [rgb] (shading only scales them).
async function matching(page, rgb) {
  const png = await page.locator('.animus-mc-stage').screenshot();
  return page.evaluate(async ([base64, want]) => {
    const image = new Image();
    image.src = `data:image/png;base64,${base64}`;
    await image.decode();
    const canvas = document.createElement('canvas');
    canvas.width = image.naturalWidth;
    canvas.height = image.naturalHeight;
    const context = canvas.getContext('2d');
    context.drawImage(image, 0, 0);
    const data = context.getImageData(0, 0, canvas.width, canvas.height).data;
    const peak = Math.max(...want);
    let count = 0;
    for (let i = 0; i < data.length; i += 4) {
      const max = Math.max(data[i], data[i + 1], data[i + 2]);
      if (max < 60) continue;
      const close = [0, 1, 2].every((c) => Math.abs(data[i + c] / max - want[c] / peak) < 0.12);
      if (close) count++;
    }
    return count;
  }, [png.toString('base64'), rgb]);
}

async function drawn(page) {
  await page.waitForFunction(() => {
    const element = document.querySelector('.animus-mc-gl-host');
    return element && Number(element.getAttribute('data-frames')) > 0 && element.getAttribute('data-unbuilt') === '0';
  });
}

async function wears(page, player, label) {
  await drawn(page);
  // The stage redraws after the texture lands; poll its pixels.
  let top = 0, bottom = 0;
  for (let attempt = 0; attempt < 40; attempt++) {
    top = await matching(page, player.top);
    bottom = await matching(page, player.bottom);
    if (top > 300 && bottom > 300) break;
    await page.waitForTimeout(100);
  }
  assert.ok(top > 300 && bottom > 300, `${label}: the stage does not wear the skin (${top} top, ${bottom} bottom pixels)`);
  return {top, bottom};
}

async function noSidewaysScroll(page, where) {
  const wide = await page.evaluate(() => {
    const width = document.documentElement.clientWidth;
    if (document.documentElement.scrollWidth <= width + 1) return null;
    return [...document.querySelectorAll('body *')]
      .filter((element) => element.getBoundingClientRect().right > width + 1)
      .slice(0, 6)
      .map((element) => `${element.tagName.toLowerCase()}.${[...element.classList].join('.')} ${Math.round(element.getBoundingClientRect().right)}`);
  });
  assert.equal(wide, null, `${where} scrolls sideways: ${JSON.stringify(wide)}`);
}

async function shot(page, name) {
  await noSidewaysScroll(page, name);
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `skins-${name}.png`), fullPage: true});
}

async function run(browser, viewport, label) {
  await control('reset', {});
  const context = await browser.newContext({viewport, colorScheme: 'dark'});
  const page = await context.newPage();
  page.setDefaultTimeout(20000);
  const errors = [];
  let failing = null;
  page.on('pageerror', (error) => errors.push(String(error)));
  page.on('console', (message) => {
    if (message.type() !== 'error') return;
    if (failing && /status of 503/.test(message.text())) return;
    errors.push(message.text());
  });
  await page.goto('about:blank');
  const pngs = new Map();
  for (const player of players) pngs.set(textureOf(player), await skinPng(page, player.top, player.bottom));
  const fetched = [];
  await context.route('https://textures.minecraft.net/texture/*', async (route) => {
    const id = route.request().url().split('/').pop();
    fetched.push(id);
    if (failing === id) return route.fulfill({status: 503, body: 'down', headers: {'Access-Control-Allow-Origin': '*'}});
    const png = pngs.get(id);
    if (!png) return route.fulfill({status: 404, body: 'none', headers: {'Access-Control-Allow-Origin': '*'}});
    return route.fulfill({status: 200, body: png, headers: {'Content-Type': 'image/png', 'Access-Control-Allow-Origin': '*'}});
  });
  await context.route('http://textures.minecraft.net/**', () => { throw new Error('the editor asked for an http texture URL'); });

  // 1. Server workspace.
  const {code} = await control('pair?paths=both', {});
  await page.goto(`${site}/#/pair/${code}`);
  await page.getByRole('button', {name: 'Pair'}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  const serverId = decodeURIComponent((await page.evaluate(() => location.hash)).split('/')[2]);
  await page.locator('[data-testid="context-state"]', {hasText: 'Online'}).waitFor();
  // The workspace lists the catalog at once; a document is read when opened.
  const workspace = `/workspace/${encodeURIComponent(`server-${serverId}`)}`;
  for (const player of players) {
    await page.goto(`${site}/#${workspace}/npc/${encodeURIComponent(player.id)}`);
    await showPreview(page);
    await waitForStageTip(page, new RegExp(`Skin: textures\\.minecraft\\.net, ${player.slim ? 'slim' : 'classic'} arms\\.`), 20000);
    const counts = await wears(page, player, `${label} server ${player.id}`);
    await shot(page, `${label}-server-${player.id.split('/')[1]}`);
    console.log(`${label} server ${player.id}: ${counts.top}/${counts.bottom} skin pixels`);
  }
  assert.deepEqual([...new Set(fetched)].sort(), players.map(textureOf).sort(), 'one texture per skin, over https');

  // 2. A failed texture says why and Retry loads it (fresh page session).
  const classic = players[0];
  failing = textureOf(classic);
  await page.goto(`${site}/?reload=1#${workspace}/npc/${encodeURIComponent(classic.id)}`);
  await showPreview(page);
  await page.waitForFunction(() => /Skin did not load/.test(document.querySelector('.ui-stage-status')?.textContent || ''), null, {timeout: 20000});
  await waitForStageTip(page, /Skin: did not load \(textures\.minecraft\.net answered HTTP 503\), so the preview shows the \w+ stand-in, classic arms\./);
  await shot(page, `${label}-server-failed`);
  failing = null;
  await page.locator('.ui-stage-head').getByRole('button', {name: 'Retry skin'}).focus();
  await page.keyboard.press('Enter');
  await waitForStageTip(page, /Skin: textures\.minecraft\.net, classic arms\./);
  assert.ok(!/Skin did not load/.test(await stageLine(page)), 'the line clears after Retry');
  await wears(page, classic, `${label} retry`);

  // 3. Offline zip keeping editor/skins/<hash>.png: that PNG wins.
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'animus-skins-'));
  const folder = path.join(temp, 'data');
  fs.cpSync(fixture, folder, {recursive: true});
  const kept = {...classic, top: [16, 224, 16], bottom: [224, 224, 16]};
  fs.mkdirSync(path.join(folder, 'editor', 'skins'), {recursive: true});
  fs.writeFileSync(path.join(folder, 'editor', 'skins', `${hashOf(classic)}.png`), await skinPng(page, kept.top, kept.bottom));
  const zip = path.join(temp, 'data.zip');
  execFileSync('zip', ['-qr', zip, '.'], {cwd: folder});
  fetched.length = 0;
  await page.goto(`${site}/#/`);
  await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await startAction(page, 'Open zip');
  await (await chooser).setFiles(zip);
  await workspaceOpen(page);
  const offline = page.url().split('#')[1].split('?')[0].split('/npc/')[0];
  await page.goto(`${site}/#${offline}/npc/${encodeURIComponent(classic.id)}`);
  await showPreview(page);
  await waitForStageTip(page, /Skin: the PNG kept in this workspace, classic arms\./);
  await wears(page, kept, `${label} offline zip`);
  // The stage wears the kept PNG without asking for the texture; the skin
  // picker's faces (lib/components/select/skin_faces.dart) still fetch it.
  await shot(page, `${label}-offline-zip`);
  fs.rmSync(temp, {recursive: true, force: true});

  assert.deepEqual(errors, []);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
  try {
    await run(browser, {width: 1440, height: 900}, 'desktop');
    await run(browser, {width: 390, height: 844}, 'phone');
    console.log('skin_render_browser: ok');
  } finally {
    await browser.close();
  }
})().catch((error) => { console.error(error); process.exit(1); });
