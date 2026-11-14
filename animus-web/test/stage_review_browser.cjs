// The NPC stage on a real server's shapes (the fake server's --scale=1200
// set), at 1600 dark, 900 with the canvas at the 240 px band height, 768
// dark and 390 light:
//   1. an armour stand in leather armour with a sword stays whole inside the
//      canvas (its helmet and base plate too);
//   2. an NPC with a 34-point route is framed first: tall and in the middle,
//      with at most a dozen point numbers, none overlapping another text;
//   3. a dragon's route points and numbers sit inside the canvas;
//   4. a name of colour codes alone draws no empty plate;
//   5. the header's Terrain, Route and Nearby toggles show a check while on,
//      the radius is a "..." menu row, and where the toggles do not fit they
//      are "..." rows too (no "Show" button);
//   6. light text on the stage reads at 4.5:1 or more against what is
//      behind it, in both themes.
// Then at 1600: during a server restart the stage says "Reconnecting", as
// the badge does; opening three previews in turn asks for terrain at most
// 17 times in any second, going back to them asks for none, and a reload
// asks again only for chunks that were not loaded.
//
//   dart run tool/fake_server.dart --port=<fake> --origin=http://127.0.0.1:<site> --scale=1200
//   python3 tool/serve_site.py --port <site>
//   PLAYWRIGHT_MODULE=... ANIMUS_WEB_URL=http://127.0.0.1:<site> ANIMUS_FAKE_URL=http://127.0.0.1:<fake> \
//     ANIMUS_SCREENSHOTS=<dir> node test/stage_review_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {showPreview, stageDrawn, stageLine, openStageMenu, subjectBox, textContrast} = require('./support/stage.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server started with --scale=1200'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;

const stand = 'world/citizens-4';
const longRoute = 'town/market/citizens-6';
const dragon = 'world/citizens-10';
const blank = 'world/citizens-1';
const talker = 'world/citizens-2';

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

/** Dresses the armour stand as an imported training stand is. */
async function dressStand() {
  const source = (await control(`document?kind=npc&id=${encodeURIComponent(stand)}`)).source;
  await control('edit', {kind: 'npc', id: stand, source: {
    ...source,
    revision: source.revision + 1,
    nameplate: {visible: false},
    armorStand: {small: false, arms: true, basePlate: true, invisible: false, head: [0, 0, 0], body: [0, 0, 0], leftArm: [-10, 0, -10], rightArm: [-15, 0, 10], leftLeg: [-1, 0, -1], rightLeg: [1, 0, 1]},
    equipment: {
      main_hand: {id: 'minecraft:wooden_sword', count: 1}, off_hand: {id: 'minecraft:shield', count: 1},
      head: {id: 'minecraft:leather_helmet', count: 1}, chest: {id: 'minecraft:leather_chestplate', count: 1},
      legs: {id: 'minecraft:leather_leggings', count: 1}, feet: {id: 'minecraft:leather_boots', count: 1},
    },
  }});
}

async function pair(context) {
  const {code} = await control('pair?paths=both', {});
  const page = await context.newPage();
  page.setDefaultTimeout(30000);
  await page.goto(`${site}/#/pair/${code}`);
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  const serverId = decodeURIComponent((await page.evaluate(() => location.hash)).split('/')[2]);
  await page.locator('[data-testid="context-state"]', {hasText: 'Online'}).waitFor();
  return {page, workspace: encodeURIComponent(`server-${serverId}`)};
}

/** Opens [id]'s preview and waits until it has drawn and its terrain settled. */
async function open(page, workspace, id, band) {
  await page.goto(`${site}/#/workspace/${workspace}/npc/${encodeURIComponent(id)}`);
  await showPreview(page);
  if (band) await page.addStyleTag({content: '.ui-stage-canvas { height: 240px !important; min-height: 0 !important; max-height: 240px !important; aspect-ratio: auto !important; }'});
  await stageDrawn(page);
  await page.waitForFunction(() => !/Loading terrain/.test(document.querySelector('.ui-stage-status')?.textContent || ''));
  await page.waitForTimeout(500);
}

/** Texts on the stage: inside it, none empty, none over another. [visible count, route numbers]. */
async function texts(page, label) {
  const found = await page.evaluate(() => {
    const stage = document.querySelector('.animus-mc-stage').getBoundingClientRect();
    const all = [...document.querySelectorAll('.animus-mc-stage .animus-mc-text')];
    const shown = all.filter((text) => getComputedStyle(text).visibility !== 'hidden').map((text) => ({name: text.textContent.trim(), box: text.getBoundingClientRect()}));
    const out = [];
    if (all.some((text) => !text.textContent.trim())) out.push('an empty plate');
    for (const {name, box} of shown) {
      if (box.left < stage.left - 0.5 || box.right > stage.right + 0.5 || box.top < stage.top - 0.5 || box.bottom > stage.bottom + 0.5) out.push(`${name} leaves the stage`);
    }
    for (let a = 0; a < shown.length; a++) {
      for (let b = a + 1; b < shown.length; b++) {
        const one = shown[a].box, two = shown[b].box;
        if (one.left < two.right - 0.5 && two.left < one.right - 0.5 && one.top < two.bottom - 0.5 && two.top < one.bottom - 0.5) out.push(`${shown[a].name} covers ${shown[b].name}`);
      }
    }
    return {out, shown: shown.length, numbers: shown.filter((text) => /^\d+$/.test(text.name)).length};
  });
  assert.deepEqual(found.out, [], `${label}: texts on the stage`);
  return found;
}

async function shot(page, name) {
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.locator('.ui-stage').first().screenshot({path: path.join(shots, `${name}.png`)});
}

async function views(browser, viewport, theme, label, band = false) {
  const context = await browser.newContext({viewport, colorScheme: theme});
  await context.addInitScript((theme) => localStorage.setItem('animus.theme', theme), theme);
  const {page, workspace} = await pair(context);
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  page.on('console', (message) => { if (message.type() === 'error' && !/status of (404|429)/.test(message.text())) errors.push(message.text()); });

  // 1. The armour stand, whole.
  await open(page, workspace, stand, band);
  if (band) assert.equal(Math.round((await subjectBox(page)).height), 240, `${label}: the canvas follows the 240 px band`);
  const dressed = await subjectBox(page);
  for (const [side, value, limit] of [['top', dressed.top, dressed.height * 0.03], ['left', dressed.left, dressed.width * 0.03], ['bottom', dressed.height - dressed.bottom, dressed.height * 0.03], ['right', dressed.width - dressed.right, dressed.width * 0.03]]) {
    assert.ok(value >= limit, `${label}: the stand reaches the ${side} edge (${value.toFixed(0)} px in)`);
  }
  await texts(page, `${label} stand`);
  await shot(page, `${label}-stand`);

  // 5. The header: checks on the toggles, the radius in the "..." menu.
  const head = page.locator('.ui-stage-head').first();
  assert.equal(await head.getByText(/\d+ blocks?$/).count(), 0, `${label}: no radius picker in the header`);
  assert.equal(await head.getByRole('button', {name: 'Show', exact: true}).count(), 0, `${label}: no Show button`);
  const toggles = head.getByRole('group', {name: 'Show'}).getByRole('button');
  const fits = await toggles.first().isVisible().catch(() => false);
  if (fits) {
    for (const toggle of await toggles.all()) {
      const on = (await toggle.getAttribute('aria-pressed')) === 'true' || (await toggle.getAttribute('data-arcane-state')) === 'on';
      assert.equal(await toggle.locator('svg, i').count() > 0, on, `${label}: ${(await toggle.textContent()).trim()} shows a check only while on`);
    }
  }
  await openStageMenu(page);
  const rows = await page.getByRole('menuitem').filter({visible: true}).allTextContents();
  assert.ok(rows.some((row) => /16 block radius/.test(row)), `${label}: the radius is a menu row: ${rows}`);
  assert.equal(rows.some((row) => /Terrain/.test(row)), !fits, `${label}: the layers are menu rows only when the toggles do not fit`);
  await page.keyboard.press('Escape');
  await shot(page, `${label}-header`);

  // 2. A 34-point route: the NPC first.
  await open(page, workspace, longRoute, band);
  const walker = await subjectBox(page);
  assert.ok(walker.bottom - walker.top >= walker.height * 0.3, `${label}: the NPC with a long route is ${(walker.bottom - walker.top).toFixed(0)} of ${walker.height.toFixed(0)} px tall`);
  const middle = (walker.left + walker.right) / 2 / walker.width;
  assert.ok(middle > 0.3 && middle < 0.7, `${label}: the NPC with a long route stands at ${(middle * 100).toFixed(0)}% across`);
  assert.ok(walker.left >= 0 && walker.right <= walker.width && walker.top >= 0 && walker.bottom <= walker.height, `${label}: the NPC with a long route is inside the canvas`);
  const routeTexts = await texts(page, `${label} long route`);
  assert.ok(routeTexts.numbers <= 12, `${label}: ${routeTexts.numbers} point numbers`);
  await shot(page, `${label}-long-route`);

  // 3. The dragon's route.
  await open(page, workspace, dragon, band);
  const flying = await subjectBox(page);
  assert.ok(flying.left >= 0 && flying.right <= flying.width && flying.top >= 0 && flying.bottom <= flying.height, `${label}: the dragon is inside the canvas`);
  await texts(page, `${label} dragon`);
  await shot(page, `${label}-dragon`);

  // 4. A name of colour codes alone.
  await open(page, workspace, blank, band);
  await texts(page, `${label} blank name`);
  await shot(page, `${label}-blank-name`);

  // 6. Light text reads against what is behind it.
  for (const id of [talker, longRoute]) {
    await open(page, workspace, id, band);
    const measured = await textContrast(page);
    const low = measured.filter((run) => run.ratio < 4.5).map((run) => `${run.text} ${run.ratio.toFixed(2)}:1`);
    assert.deepEqual(low, [], `${label} ${theme}: text on ${id} below 4.5:1`);
  }
  await shot(page, `${label}-contrast`);

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 1, `${label}: ${overflow}px of sideways scrolling`);
  assert.deepEqual(errors, [], `${label}: console errors`);
  await context.close();
}

/** A restart: the stage names the connection as the badge does. */
async function restart(browser) {
  const context = await browser.newContext({viewport: {width: 1600, height: 900}});
  const {page, workspace} = await pair(context);
  await open(page, workspace, talker, false);
  await control('offline', {seconds: 4});
  await page.waitForFunction(() => (document.querySelector('[data-testid="context-state"]')?.textContent || '').trim() === 'Reconnecting', null, {timeout: 10000});
  await page.waitForFunction(() => /Reconnecting/.test(document.querySelector('.ui-stage-status')?.textContent || ''), null, {timeout: 5000});
  assert.doesNotMatch(await stageLine(page), /offline/i, 'the stage says what the badge says');
  await shot(page, 'restart-reconnecting');
  await page.waitForFunction(() => (document.querySelector('[data-testid="context-state"]')?.textContent || '').trim() === 'Online', null, {timeout: 20000});
  await context.close();
}

/** Terrain traffic while three previews open in turn, are opened again, and after a reload. */
async function traffic(browser) {
  const context = await browser.newContext({viewport: {width: 1600, height: 900}});
  const {page, workspace} = await pair(context);
  const asked = [];
  const answers = new Map();
  page.on('request', (request) => { if (/\/v1\/terrain\/blocks/.test(request.url())) asked.push({at: Date.now(), url: request.url()}); });
  page.on('response', async (response) => {
    if (!/\/v1\/terrain\/blocks/.test(response.url()) || response.status() !== 200) return;
    try { answers.set(response.url(), (await response.json()).status); } catch (_) { /* The page moved on. */ }
  });
  const three = [talker, longRoute, 'harbor/citizens-8'];
  for (const id of three) await open(page, workspace, id, false);
  const first = asked.length;
  assert.ok(first > 0, 'terrain was asked for');
  const peak = Math.max(...asked.map((one) => asked.filter((other) => other.at >= one.at && other.at < one.at + 1000).length));
  console.log(`terrain: ${first} block requests for three previews, at most ${peak} in one second`);
  assert.ok(peak <= 17, `${peak} block requests in one second`);
  for (const id of [...three, talker]) await open(page, workspace, id, false);
  assert.equal(asked.length, first, 'going back to a preview asks for nothing');
  const before = asked.length;
  await page.reload();
  await page.locator('[data-testid="context-state"]', {hasText: 'Online'}).waitFor();
  await open(page, workspace, talker, false);
  const again = asked.slice(before);
  const unloaded = [...answers.values()].filter((status) => status !== 'ok').length;
  console.log(`terrain: ${again.length} block requests after a reload (${unloaded} answers were not loaded chunks)`);
  if (process.env.DEBUG_TRAFFIC) console.log(again.map((one) => `${one.url.replace(/.*blocks\?/, '')} first ${asked.slice(0, before).filter((other) => other.url === one.url).length}`).join('\n'));
  assert.ok(again.length <= unloaded, `a reload asks again only for chunks that were not loaded: ${again.length}`);
  await context.close();
}

(async () => {
  await control('reset', {});
  await control('restore', {});
  await dressStand();
  const browser = await chromium.launch({headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
  try {
    await views(browser, {width: 1600, height: 900}, 'dark', 'desktop');
    await views(browser, {width: 900, height: 1000}, 'dark', 'band', true);
    await views(browser, {width: 768, height: 1024}, 'dark', 'tablet');
    await views(browser, {width: 390, height: 844}, 'light', 'phone');
    await restart(browser);
    await traffic(browser);
    console.log('stage_review_browser: ok');
  } finally {
    await browser.close();
    await control('restore', {});
  }
})().catch((error) => { console.error(error); process.exit(1); });
