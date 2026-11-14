// The other NPCs on an NPC's stage in a server workspace. Writes a data
// folder: town/subject at 0, 64, 0 with 40 spawned NPCs within 6 blocks and
// 30 between 10 and 15 blocks, plus one despawned beside it, one in another
// world and one 100 blocks away (none of those three is drawn). Then, at
// 1440 px dark and 390 px light:
//   1. the stage draws the 60 nearest of the 70 within 16 blocks and says
//      "Showing 60 of 70 nearby"; no two texts on it overlap and none leaves
//      it;
//   2. 8 blocks shows "40 nearby";
//   3. clicking a neighbour's body offers its name and Open, which opens it;
//      before that, orbiting the stage until a neighbour stands between the
//      eye and the subject leaves it and its name out (`data-culled` on the
//      canvas host);
//   4. Nearby off draws none and the line says nothing about them.
// No console errors other than the fake skins' 404 and the rate limit's 429.
//
//   node test/stage_neighbours_browser.cjs --write-fixture <folder>   # once
//   dart run tool/fake_server.dart --port=<p> --origin=<site> --data=<folder>
//   PLAYWRIGHT_MODULE=... ANIMUS_WEB_URL=<site> ANIMUS_FAKE_URL=http://127.0.0.1:<p> \
//     ANIMUS_SCREENSHOTS=<dir> node test/stage_neighbours_browser.cjs
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const names = ['Baker', 'Smith', 'Guard', 'Fisher', 'Miner', 'Farmer', 'Sailor', 'Herald', 'Priest', 'Ranger'];

function writeFixture(folder) {
  const npc = (id, name, world, x, z, spawned = true) => {
    const [group, short] = id.split('/');
    fs.mkdirSync(path.join(folder, 'npcs', group), {recursive: true});
    fs.writeFileSync(path.join(folder, 'npcs', group, `${short}.json`), JSON.stringify({
      schemaVersion: 1, revision: 1, uuid: crypto.randomUUID(), id: short, group, name, type: 'minecraft:player', spawned,
      position: {world, x, y: 64, z, yaw: 0, pitch: 0},
    }, null, 2));
  };
  npc('town/subject', 'Subject', 'world', 0.5, 0.5);
  for (let i = 0; i < 40; i++) {
    const angle = i * 2.4, distance = 2 + (i % 5);
    npc(`town/near-${i}`, `${names[i % names.length]} ${i}`, 'world', 0.5 + Math.round(Math.cos(angle) * distance), 0.5 + Math.round(Math.sin(angle) * distance));
  }
  for (let i = 0; i < 30; i++) {
    const angle = i * 0.21, distance = 10 + (i % 6);
    npc(`town/ring-${i}`, `${names[i % names.length]} ring ${i}`, 'world', 0.5 + Math.round(Math.cos(angle) * distance), 0.5 + Math.round(Math.sin(angle) * distance));
  }
  npc('town/sleeping', 'Sleeping', 'world', 1.5, 1.5, false);
  npc('nether/visitor', 'Visitor', 'world_nether', 0.5, 0.5);
  npc('town/far', 'Far away', 'world', 100.5, 0.5);
}

if (process.argv[2] === '--write-fixture') {
  writeFixture(path.resolve(process.argv[3]));
  process.exit(0);
}

const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {showPreview, stageDrawn, stageLine, pickRadius, setLayer} = require('./support/stage.cjs');
const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

const count = (name) => Number.parseInt(document.querySelector('[data-npc-stage]')?.dataset[name] ?? 'NaN', 10);

/** Texts on the stage: none overlaps another, none leaves the stage. */
async function textsClear(page, label) {
  const problems = await page.evaluate(() => {
    const stage = document.querySelector('.animus-mc-stage').getBoundingClientRect();
    const boxes = [...document.querySelectorAll('.animus-mc-text')]
      .filter((text) => text.style.visibility !== 'hidden')
      .map((text) => ({name: text.textContent.trim(), box: text.getBoundingClientRect()}));
    const out = [];
    for (const {name, box} of boxes) {
      if (box.left < stage.left - 0.5 || box.right > stage.right + 0.5 || box.top < stage.top - 0.5 || box.bottom > stage.bottom + 0.5) out.push(`${name} leaves the stage`);
    }
    for (let a = 0; a < boxes.length; a++) {
      for (let b = a + 1; b < boxes.length; b++) {
        const one = boxes[a].box, two = boxes[b].box;
        if (one.left < two.right - 0.5 && two.left < one.right - 0.5 && one.top < two.bottom - 0.5 && two.top < one.bottom - 0.5) out.push(`${boxes[a].name} covers ${boxes[b].name}`);
      }
    }
    return out;
  });
  assert.deepEqual(problems, [], `${label}: texts on the stage`);
}

async function run(browser, viewport, theme, label) {
  await control('reset', {});
  const {code} = await control('pair?paths=both', {});
  const context = await browser.newContext({viewport, colorScheme: theme});
  const page = await context.newPage();
  page.setDefaultTimeout(30000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  page.on('console', (message) => { if (message.type() === 'error' && !/status of (404|429)/.test(message.text())) errors.push(message.text()); });

  await page.goto(`${site}/#/pair/${code}`);
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  const serverId = decodeURIComponent((await page.evaluate(() => location.hash)).split('/')[2]);
  await page.locator('[data-testid="context-state"]', {hasText: 'Online'}).waitFor();
  const workspace = encodeURIComponent(`server-${serverId}`);
  await page.goto(`${site}/#/workspace/${workspace}/npc/${encodeURIComponent('town/subject')}`);
  await showPreview(page);
  await stageDrawn(page);

  // 1. The 60 nearest of the 70 within 16 blocks, counted on the line.
  await page.waitForFunction(() => document.querySelector('[data-npc-stage]')?.dataset.neighboursTotal === '70');
  assert.equal(await page.evaluate(count, 'neighbours'), 60);
  await page.waitForFunction(() => /Showing 60 of 70 nearby/.test(document.querySelector('.ui-stage-status')?.textContent || ''));
  await stageDrawn(page);
  await page.waitForTimeout(400);
  await textsClear(page, `${label} 16 blocks`);
  if (shots) {
    fs.mkdirSync(shots, {recursive: true});
    await page.locator('.ui-stage').first().screenshot({path: path.join(shots, `neighbours-${label}.png`)});
  }

  // Orbited until neighbours stand between the eye and the subject, they
  // and their names are left out; Reset view brings the home camera back.
  const canvas = await page.locator('.animus-mc-stage').first().boundingBox();
  const culled = () => page.evaluate(() => Number(document.querySelector('.animus-mc-gl-host')?.getAttribute('data-culled') || 0));
  const named = () => page.evaluate(() => document.querySelectorAll('.animus-mc-text[data-neighbour]').length);
  const allNamed = await named();
  for (let turn = 0; turn < 8 && (await culled()) === 0; turn++) {
    await page.mouse.move(canvas.x + canvas.width / 2, canvas.y + canvas.height / 2);
    await page.mouse.down();
    await page.mouse.move(canvas.x + canvas.width / 2 + 60, canvas.y + canvas.height / 2, {steps: 4});
    await page.mouse.up();
    await page.waitForTimeout(250);
  }
  assert.ok((await culled()) > 0, `${label}: no neighbour in front of the subject was left out`);
  assert.ok((await named()) <= allNamed, `${label}: a left-out neighbour keeps no name`);
  await textsClear(page, `${label} orbited`);
  if (shots) await page.locator('.ui-stage').first().screenshot({path: path.join(shots, `neighbours-${label}-culled.png`)});
  await page.locator('.ui-stage').first().getByRole('button', {name: 'Reset view', exact: true}).click();
  await page.waitForTimeout(250);

  // 2. A smaller radius.
  await pickRadius(page, 8);
  await page.waitForFunction(() => document.querySelector('[data-npc-stage]')?.dataset.neighboursTotal === '40');
  assert.match(await stageLine(page), /^40 nearby/);
  await page.waitForTimeout(400);
  await textsClear(page, `${label} 8 blocks`);

  // 3. A click on a neighbour's body offers to open it.
  const plate = page.locator('.animus-mc-text[data-neighbour]').filter({visible: true}).first();
  const name = (await plate.textContent()).trim();
  const box = await plate.boundingBox();
  await page.mouse.click(box.x + box.width / 2, box.y + box.height + 4);
  const card = page.getByRole('dialog', {name: 'Nearby NPC'});
  await card.waitFor();
  // Plates move apart (or hide) when they would overlap, so the body under
  // a plate can be another neighbour's: the card names that neighbour, and
  // Open below goes to the NPC it names.
  const picked = (await card.innerText()).split('\n')[0].trim();
  assert.ok(picked !== '' && picked !== 'Subject', `${label}: the card names a neighbour, not the subject: ${picked}`);
  if (shots) await page.locator('.ui-stage').first().screenshot({path: path.join(shots, `neighbours-${label}-picked.png`)});
  await card.getByRole('button', {name: 'Open', exact: true}).click();
  await page.waitForFunction(() => !decodeURIComponent(location.hash).endsWith('/npc/town/subject'));
  await page.locator('h1').getByText(picked, {exact: true}).waitFor();

  // 4. Nearby off.
  await page.goto(`${site}/#/workspace/${workspace}/npc/${encodeURIComponent('town/subject')}`);
  await showPreview(page);
  await page.waitForFunction(() => Number(document.querySelector('[data-npc-stage]')?.dataset.neighbours) > 0);
  await setLayer(page, 'Nearby', false);
  await page.waitForFunction(() => document.querySelector('[data-npc-stage]')?.dataset.neighbours === '0');
  assert.ok(!/nearby/.test(await stageLine(page)), 'no neighbour count with Nearby off');

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 1, `${label}: ${overflow}px of sideways scrolling`);
  assert.deepEqual(errors, [], `${label}: console errors`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
  try {
    await run(browser, {width: 1440, height: 900}, 'dark', 'desktop');
    await run(browser, {width: 390, height: 844}, 'light', 'phone');
    console.log('stage_neighbours_browser: ok');
  } finally {
    await browser.close();
  }
})().catch((error) => { console.error(error); process.exit(1); });
