// Server terrain in the NPC stage and the route editor, against
// tool/fake_server.dart's generated fixture world at 1440 and 390 px. Pairs,
// opens town/walker, then: the stage draws terrain meshes instead of the
// grass patch (the picture changes when Terrain is turned off) and offers a
// radius; the route grid draws surface tiles under the points; a point
// placed on a hill lands on its standable block; the point inside the hill
// and the one over the pond are flagged; the 3D route tool shows the route
// over the blocks. A reload uses the cached tiles. Then town/rambler, whose
// route runs 700 blocks from home: the stage, the grid and 3D load terrain
// along the whole route and the server answers none of it forbidden, and
// while the NPC is spawned the stage follows where it stands now, loading
// the ground around each new spot. A refused Fullscreen says so; a second
// tab's stage says live editing runs in the other tab until it takes over.
// No console errors and no sideways scrolling.
//
//   python3 tool/serve_site.py --port <web port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<web port> --data=test/fixtures/terrain_view
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<web port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/terrain_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {showPreview, stageLine, openStageMenu, setLayer} = require('./support/stage.cjs');
const {npcTab} = require('./support/npc_tabs.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;

/** The fixture world's surface, as tool/fake_server/terrain.dart builds it. */
function surfaceY(x, z) {
  if (Math.abs(x) <= 4 && Math.abs(z) <= 4) return 64;
  return 64 + Math.floor(2.5 * Math.sin(x / 7) + 2 * Math.cos(z / 9));
}

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

async function shot(page, name) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 1, `${name}: ${overflow}px of sideways scrolling`);
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: true});
}

/** The stage alone, beside the full page shot, when screenshots are kept. */
async function stageShot(stage, name) {
  if (!shots) return;
  await stage.screenshot({path: path.join(shots, `${name}.png`)});
}

/** The NPC tab that holds an old inspector part: routes are on Movement. */
async function part(page, id) {
  return npcTab(page, {behaviour: 'Movement'}[id]);
}

/** Clicks the route grid at world [x], [z]. */
async function clickGrid(page, x, z) {
  const grid = page.locator('.animus-route-grid').first();
  await grid.scrollIntoViewIfNeeded();
  const box = await grid.boundingBox();
  const minX = Number(await grid.getAttribute('data-min-x'));
  const minZ = Number(await grid.getAttribute('data-min-z'));
  const size = Number(await grid.getAttribute('data-size'));
  await page.mouse.click(box.x + (x - minX) / size * box.width, box.y + (z - minZ) / size * box.height);
}

async function run(browser, viewport, label) {
  await control('reset', {});
  const {code} = await control('pair?paths=both', {});
  const context = await browser.newContext({viewport});
  const page = await context.newPage();
  page.setDefaultTimeout(20000);
  const errors = [];
  // A reload starts a fresh request pacer while the server's bucket for the
  // token is still drained: its rate-limit answers are retried after the
  // hint, so the browser's own 429 lines are not faults.
  page.on('console', message => { if (message.type() === 'error' && !/status of 429/.test(message.text())) errors.push(message.text()); });
  page.on('pageerror', error => errors.push(String(error)));
  const terrainRequests = [];
  page.on('request', request => { if (/\/v1\/terrain\/(surface|blocks)/.test(request.url())) terrainRequests.push(request); });
  // Every terrain answer with a body: blocks by chunk, surface tiles by tile.
  const answers = [];
  page.on('response', async response => {
    if (!/\/v1\/terrain\/(surface|blocks)/.test(response.url()) || response.status() !== 200) return;
    try { answers.push(await response.json()); } catch (_) { /* The page moved on. */ }
  });

  await page.goto(`${site}/#/pair/${code}`);
  await page.getByTestId('pair-submit').waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  const serverId = decodeURIComponent((await page.evaluate(() => location.hash)).split('/')[2]);
  await page.locator('[data-testid="context-state"]', {hasText: 'Online'}).waitFor({timeout: 20000});
  // The workspace lists the whole catalog; a document is read when opened.
  const workspace = `server-${serverId}`;
  const openNpc = async (id) => {
    await page.goto(`${site}/#/workspace/${encodeURIComponent(workspace)}/npc/${encodeURIComponent(id)}`);
    await showPreview(page);
    await page.locator('[data-npc-stage]').waitFor();
  };

  // 1. The stage: terrain meshes replace the grass patch.
  await openNpc('town/walker');
  const preview = page.locator('.ui-stage').first();
  await page.waitForFunction(() => Number(document.querySelector('[data-npc-stage]').dataset.terrainMeshes) > 0);
  const wide = await page.evaluate(() => !window.matchMedia('(max-width: 760px)').matches);
  await openStageMenu(page);
  assert.ok(await page.getByRole('menuitem', {name: /16 block radius/}).filter({visible: true}).count() > 0, 'radius rows in the stage menu');
  await page.keyboard.press('Escape');
  const stage = preview.locator('.animus-mc-stage');
  await stage.scrollIntoViewIfNeeded();
  await page.waitForTimeout(300);
  const withTerrain = await stage.screenshot();
  await setLayer(page, 'Terrain', false);
  await page.waitForFunction(() => document.querySelector('[data-npc-stage]').dataset.terrainMeshes === '0');
  await page.waitForTimeout(300);
  const flat = await stage.screenshot();
  assert.ok(!withTerrain.equals(flat), 'the stage looks different with terrain off');
  await setLayer(page, 'Terrain', true);
  await page.waitForFunction(() => Number(document.querySelector('[data-npc-stage]').dataset.terrainMeshes) > 0);
  await shot(page, `${label}-stage`);

  // A refused full screen request (a frame or browser that does not allow it) says so and raises no page error.
  const fullscreen = preview.getByRole('button', {name: 'Fullscreen', exact: true});
  // Wide pages show the button in the stage header; phones may fold it away.
  if (wide || await fullscreen.isVisible()) {
    await page.evaluate(() => {
      window.grantedFullscreen = Element.prototype.requestFullscreen;
      Element.prototype.requestFullscreen = () => Promise.reject(new TypeError('not granted'));
    });
    await fullscreen.click();
    const refused = page.getByText('Full screen is not available here.');
    await refused.waitFor();
    await page.evaluate(() => { Element.prototype.requestFullscreen = window.grantedFullscreen; });
    assert.equal(await page.evaluate(() => document.fullscreenElement), null);
    await refused.waitFor({state: 'detached', timeout: 15000});
  }

  // 2. The route grid: surface tiles under the points.
  const behaviour = await part(page, 'behaviour');
  await behaviour.locator('.animus-route-grid').waitFor();
  await page.waitForFunction(() => document.querySelectorAll('.animus-route-grid image.animus-route-terrain').length > 0);
  const tiles = await page.locator('.animus-route-grid image.animus-route-terrain').count();
  assert.ok(tiles >= 1, `${tiles} tiles`);

  // 3. Flags: point 1 is inside the hill, point 2 hangs over the pond.
  await page.locator('[data-node="points:1"][data-issue]').waitFor();
  assert.equal(await page.locator('[data-node="points:1"]').getAttribute('data-issue'), 'below');
  assert.equal(await page.locator('[data-node="points:2"]').getAttribute('data-issue'), 'floating');
  assert.equal(await page.locator('[data-node="points:0"][data-issue]').count(), 0);
  // Points count from 1 on the page: points:1 is Point 2.
  assert.ok(await page.getByRole('list', {name: 'Terrain problems'}).getByText('Point 2').count() > 0);

  // 4. Placing a point on the hill puts it on its standable block.
  const x = 6, z = -3;
  await clickGrid(page, x, z);
  const placed = page.locator('[data-node="points:3"]');
  await placed.waitFor();
  assert.equal(await placed.getAttribute('data-y'), String(surfaceY(x, z) + 1), 'placed on the surface');
  assert.equal(await page.locator('[data-node="points:3"][data-issue]').count(), 0);
  await shot(page, `${label}-route`);

  // 5. 3D (a route tool): the route over the blocks, in place of the map.
  await page.getByRole('group', {name: 'Route tool'}).getByRole('button', {name: '3D'}).click();
  const view = page.locator('[data-route-3d]');
  await view.waitFor();
  await page.waitForFunction(() => Number(document.querySelector('[data-route-3d]').dataset.terrainMeshes) > 0);
  await view.locator('.animus-mc-gl-host').scrollIntoViewIfNeeded();
  await page.waitForFunction(() => Number(document.querySelector('[data-route-3d] .animus-mc-gl-host').dataset.frames) > 0);
  await shot(page, `${label}-route-3d`);

  // 6. A reload answers from the browser cache: copies saved minutes ago
  // need no request, the rest (chunks nobody was near) go out with their hash.
  const before = terrainRequests.length;
  assert.ok(before > 0, 'terrain was requested');
  assert.ok(terrainRequests.every(request => !request.url().includes('height=65')), 'slices stay within the cap');
  const stageSlices = terrainRequests.filter(request => /\/blocks/.test(request.url())).length;
  await page.reload();
  await page.locator('[data-testid="context-state"]', {hasText: 'Online'}).waitFor({timeout: 20000});
  await openNpc('town/walker');
  await page.waitForFunction(() => Number(document.querySelector('[data-npc-stage]')?.dataset.terrainMeshes) > 0);
  const revalidated = terrainRequests.slice(before);
  assert.ok(revalidated.every(request => 'if-none-match' in request.headers()), 'cached answers go out with their hash');
  assert.ok(revalidated.length < stageSlices, `a reload asks again only for what was not loaded: ${revalidated.length} of ${stageSlices}`);

  // 7. A route far beyond the terrain radius of home: terrain loads along all of it.
  const blocksAt = (cx, cz) => answers.find(answer => answer.cx === cx && answer.cz === cz && 'status' in answer);
  const farFrom = answers.length;
  await openNpc('town/rambler');
  const far = page.locator('.ui-stage').first();
  // Terrain arrives at the client's pace, 16 requests a second, and shows in batches.
  await page.waitForFunction(() => Number(document.querySelector('[data-npc-stage]')?.dataset.terrainChunks) > 100, null, {timeout: 60000});
  await page.waitForFunction(() => Number(document.querySelector('[data-npc-stage]').dataset.terrainMeshes) > 100, null, {timeout: 60000});
  await page.waitForFunction(() => !/out of reach/.test(document.querySelector('.ui-stage-status')?.textContent || ''));
  for (const [cx, cz, what] of [[-32, 1, 'the first point, 480 blocks from home'], [-17, 1, 'halfway along the first leg'], [-32, -14, 'halfway along the second leg'], [-17, -29, 'the last point']]) {
    const deadline = Date.now() + 60000;
    while (!blocksAt(cx, cz) && Date.now() < deadline) await page.waitForTimeout(200);
    assert.equal(blocksAt(cx, cz)?.status, 'ok', `stage: ${what} (chunk ${cx}, ${cz})`);
  }
  await far.locator('.animus-mc-stage').scrollIntoViewIfNeeded();
  await shot(page, `${label}-far-stage`);
  await stageShot(far.locator('.animus-mc-stage'), `${label}-far-stage-only`);

  const farBehaviour = await part(page, 'behaviour');
  await farBehaviour.locator('.animus-route-grid').waitFor();
  // Tiles arrive in any order: wait for the corners of the route.
  await page.waitForFunction(() => ['-8,-8', '-1,0', '-8,0', '-5,-8'].every(tile => document.querySelector(`.animus-route-grid image.animus-route-terrain[data-tile="${tile}"]`)));
  const farTiles = await page.locator('.animus-route-grid image.animus-route-terrain').evaluateAll(images => images.map(image => image.dataset.tile));
  assert.ok(farTiles.includes('-1,0') && farTiles.includes('-8,0') && farTiles.includes('-5,-8'), `grid tiles along the route: ${farTiles}`);
  assert.ok(!farTiles.includes('-4,-4'), 'no tiles in the middle, away from the route');
  assert.match((await page.locator('[data-terrain-note]').allTextContents()).join(' '), /along the route/);
  await shot(page, `${label}-far-route`);

  await page.getByRole('group', {name: 'Route tool'}).getByRole('button', {name: '3D'}).click();
  await page.waitForFunction(() => Number(document.querySelector('[data-route-3d]')?.dataset.terrainMeshes) > 100, null, {timeout: 60000});
  await page.getByRole('listbox', {name: 'Route points'}).getByRole('option').nth(2).click();
  await page.locator('[data-route-3d] .animus-mc-gl-host').scrollIntoViewIfNeeded();
  await page.waitForTimeout(400);
  await shot(page, `${label}-far-route-3d`);
  await stageShot(page.locator('.animus-mc-stage[data-route-3d]'), `${label}-far-route-3d-only`);
  const farAnswers = answers.slice(farFrom);
  const forbidden = farAnswers.filter(answer => answer.status === 'forbidden' || (answer.chunks || []).includes('forbidden'));
  assert.deepEqual(forbidden.map(answer => 'cx' in answer ? `chunk ${answer.cx},${answer.cz}` : `tile ${answer.tx},${answer.tz}`), [], 'nothing along the route is forbidden');

  // 8. Live: the spawned NPC walks off its route; the stage loads the ground where it is now.
  await page.getByRole('group', {name: 'Route tool'}).getByRole('button', {name: 'Move'}).click();
  await far.locator('.animus-mc-stage').scrollIntoViewIfNeeded();
  for (const [x, z, cx, cz] of [[-700.5, -200.5, -44, -13], [-700.5, -380.5, -44, -24]]) {
    await control('live', {npc: 'town/rambler', world: 'world', x, y: 66, z});
    await page.waitForFunction(at => (document.querySelector('[data-npc-stage]').dataset.livePosition || '').startsWith(at), `world ${x} 66 ${z}`);
    const deadline = Date.now() + 15000;
    while (blocksAt(cx, cz)?.status !== 'ok' && Date.now() < deadline) await page.waitForTimeout(200);
    assert.equal(blocksAt(cx, cz)?.status, 'ok', `live at ${x}, ${z}: chunk ${cx}, ${cz}`);
  }
  await page.waitForTimeout(400);
  await shot(page, `${label}-far-live`);
  await stageShot(far.locator('.animus-mc-stage'), `${label}-far-live-only`);
  await control('live', {npc: 'town/rambler'});

  // 9. A second tab of this browser waits: its stage says live editing runs in the other tab, until it takes over.
  const second = await context.newPage();
  second.setDefaultTimeout(20000);
  second.on('console', message => { if (message.type() === 'error' && !/status of 429/.test(message.text())) errors.push(`second tab: ${message.text()}`); });
  second.on('pageerror', error => errors.push(`second tab: ${error}`));
  await second.goto(`${site}/#/workspace/${encodeURIComponent(workspace)}/npc/${encodeURIComponent('town/walker')}`);
  await showPreview(second);
  await second.locator('[data-npc-stage]').waitFor();
  await second.waitForFunction(() => /^Open in another tab/.test(document.querySelector('.ui-save-bar .ui-save-message')?.textContent || ''));
  await second.waitForFunction(() => /In another tab/.test(document.querySelector('.ui-stage-status')?.textContent || ''));
  assert.doesNotMatch(await stageLine(second), /offline/i, 'the waiting tab does not call the server offline');
  await shot(second, `${label}-second-tab`);
  await second.locator('.ui-save-bar [data-save-action="takeOver"]').click();
  await second.waitForFunction(() => Number(document.querySelector('[data-npc-stage]')?.dataset.terrainMeshes) > 0);
  assert.doesNotMatch(await stageLine(second), /another tab|offline/i, 'the tab that took over shows its terrain');
  await page.waitForFunction(() => /In another tab/.test(document.querySelector('.ui-stage-status')?.textContent || ''));
  await second.close();

  assert.deepEqual(errors.filter(error => !error.includes('favicon')), [], `${label}: console errors`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
  try {
    await run(browser, {width: 1440, height: 1000}, 'desktop');
    await run(browser, {width: 390, height: 844}, 'phone');
  } finally {
    await browser.close();
  }
  console.log('terrain_browser: ok');
})().catch(error => {
  console.error(error);
  process.exit(1);
});
