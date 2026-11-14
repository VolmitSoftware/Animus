// Movement: the one page for routes and recordings, the route editor and
// the recording view. Opens test/fixtures/groups_recordings (a shared route
// Porter walks, Watchman's own route with junctions and ranged waits, a
// recording Stall Keeper plays) at 1440 px dark and 390 px light, then the
// same folder on the fake server.
//
// Local: the Routes tab lists the shared route with its user and Watchman's
// own route (which opens the NPC), the Recordings tab its recording and who
// plays it, each with the line that tells them apart; the old recordings
// address shows the Recordings tab; New route makes and opens a route; New
// recording shows the command to run in game. A route document names its
// user, draws that NPC, keeps every hidden key byte for byte through a
// Simple edit, shows waits in seconds (stored in ticks), numbers points
// from 1, adds a point from the toolbar and removes it from the keyboard.
// Watchman's own route edits a ranged wait and a next point. The recording
// view plays, scrubs (named, read as a time, stepped by arrows, Home and
// End, and moving the dot while dragged) and reads its time in seconds,
// keeps its jargon facts in Advanced, edits description, tags, playback
// and speed, trims from the scrub (the trim buttons are disabled exactly
// when they cannot act), refuses a Code draft that breaks a recording
// rule, makes a route, and renames from the page header only, carrying the
// NPC that plays it.
//
// Server: the Movement page lists both routes from the catalog, and the
// route document takes Porter's world, so the map draws terrain and the
// tool switch offers 3D. No console errors and no sideways scrolling.
//
//   python3 tool/serve_site.py --port <web>
//   dart run tool/fake_server.dart --port=<fake> --origin=http://127.0.0.1:<web> --data=test/fixtures/groups_recordings
//   ANIMUS_WEB_URL=http://127.0.0.1:<web> ANIMUS_FAKE_URL=http://127.0.0.1:<fake> node test/movement_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openJob, setMode, showView, startAction, waitOnline} = require('./support/shell.cjs');
const {chooseAndWait} = require('./support/select.cjs');
const {npcTab} = require('./support/npc_tabs.cjs');
const {dragSlider, setSlider, sliderThumb} = require('./support/slider.cjs');

const folder = path.resolve(__dirname, 'fixtures/groups_recordings');
const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;
const fixture = (relative) => JSON.parse(fs.readFileSync(path.join(folder, relative), 'utf8'));

async function shot(page, name) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  assert.ok(overflow <= 1, `${name}: the page scrolls sideways by ${overflow}px`);
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`)});
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

async function go(page, hash) {
  await page.evaluate((next) => { location.hash = next; }, hash);
  await page.locator('#animus-main h1').waitFor();
  await page.waitForTimeout(200);
}

async function codeOf(page) {
  const mode = await page.evaluate(() => localStorage.getItem('animus.mode'));
  await showView(page, 'code');
  const text = await page.locator('.animus-code textarea').inputValue();
  await showView(page, 'visual');
  if (mode !== 'advanced') await setMode(page, 'simple');
  return JSON.parse(text);
}

/** Reads the Code view until [done] holds, since commits queue behind slower saves. */
async function codeWhen(page, done) {
  let json = await codeOf(page);
  for (let tries = 0; !done(json) && tries < 40; tries++) {
    await page.waitForTimeout(250);
    json = await codeOf(page);
  }
  return json;
}

/** The rows of the Movement list, by title. */
async function rowTitles(page) {
  await page.locator('#movement-list .ui-library-row').first().waitFor();
  return page.locator('#movement-list .ui-library-title').allTextContents();
}

/** Opens the parts around a route editor that a phone folds shut. */
async function routeEditor(page) {
  // On an NPC the route editor sits on its Movement tab.
  await npcTab(page, 'Movement');
  const editor = page.getByRole('group', {name: 'Route editor'}).first();
  await editor.waitFor({state: 'attached'});
  await editor.evaluate((element) => {
    for (let node = element.parentElement; node; node = node.parentElement) if (node.tagName === 'DETAILS') node.open = true;
    element.scrollIntoView({block: 'start'});
  });
  await page.waitForTimeout(200);
  return editor;
}

async function local(browser, viewport, theme, tag) {
  const context = await browser.newContext({viewport});
  await context.addInitScript((mode) => window.localStorage.setItem('animus.theme', mode), theme);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  page.on('console', (message) => { if (message.type() === 'error' && !/404|Failed to load resource/.test(message.text())) errors.push(message.text()); });

  await page.goto(`${site}/`);
  await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await startAction(page, 'Open folder');
  await (await chooser).setFiles(folder);
  await page.waitForFunction(() => /#\/workspace\//.test(location.hash));
  const workspace = /#\/workspace\/([^/?]+)/.exec(page.url())[1];

  // Movement: Routes first, with the contrast line, a shared route and an NPC's own.
  await openJob(page, 'Movement');
  await page.getByRole('heading', {name: 'Movement', level: 1}).waitFor();
  const tabs = page.getByRole('tablist', {name: 'Movement'});
  assert.equal(await tabs.getByRole('tab', {name: 'Routes'}).getAttribute('aria-selected'), 'true');
  await page.locator('.animus-movement-line', {hasText: 'Walks point to point.'}).waitFor();
  // A route with no name of its own reads its id in words.
  assert.deepEqual((await rowTitles(page)).sort(), ['Market round', 'Watchman\'s route']);
  await page.locator('#movement-list .ui-library-row', {hasText: 'Market round'}).getByText('3 points · Used by Porter').waitFor();
  assert.match(await page.locator('[data-testid="list-new"]').textContent(), /New route$/);
  await shot(page, `${tag}-01-routes`);
  await page.locator('#movement-list .ui-library-row', {hasText: 'Watchman\'s route'}).click();
  await page.waitForFunction(() => location.hash.includes('/npc/town%2Fwatchman'));
  // An NPC's own route opens on its Movement tab, where the route is.
  await page.getByRole('tab', {name: 'Movement', selected: true}).waitFor();

  // Recordings tab, and the old recordings address lands on it.
  await go(page, `#/workspace/${workspace}/recordings`);
  await page.locator('.animus-movement-line', {hasText: 'Replays what you did.'}).waitFor();
  assert.equal(await page.getByRole('tab', {name: 'Recordings'}).getAttribute('aria-selected'), 'true');
  assert.deepEqual(await rowTitles(page), ['Market loop']);
  await page.locator('#movement-list .ui-library-row').getByText('4 s · world · Used by Stall Keeper').waitFor();
  await page.locator('[data-testid="list-new"]').click();
  const recordingDialog = page.locator('#new-recording');
  await recordingDialog.getByLabel('Name').fill('Night walk');
  assert.equal(await recordingDialog.getByLabel('Command').inputValue(), '/animus record start night-walk');
  await shot(page, `${tag}-02-new-recording`);
  await recordingDialog.getByRole('button', {name: 'Cancel', exact: true}).click();
  await recordingDialog.waitFor({state: 'detached'});
  await shot(page, `${tag}-03-recordings`);

  // New route: a name, Create, and the new route opens.
  await page.getByRole('tab', {name: 'Routes'}).click();
  await page.locator('.animus-movement-line', {hasText: 'Walks point to point.'}).waitFor();
  await page.locator('[data-testid="list-new"]').click();
  await page.locator('#new-route').getByLabel('Name').fill('Gate loop');
  await page.locator('#new-route').getByRole('button', {name: 'Create'}).click();
  await page.waitForFunction(() => location.hash.endsWith('/route/gate-loop'));

  // A route document: its user, that NPC on the map, Simple fields only.
  await go(page, `#/workspace/${workspace}/route/market-round`);
  await page.getByTestId('route-used-by').getByText('Used by Porter', {exact: true}).waitFor();
  let editor = await routeEditor(page);
  assert.equal(await editor.locator('svg.animus-route-grid .animus-route-npc').count(), 1, 'Porter stands on the map');
  assert.equal(await editor.getByLabel('Order').count() > 0, true);
  assert.equal(await editor.locator('#' + (await editor.locator('svg.animus-route-grid').getAttribute('id')) + '-mode').isVisible(), false, 'Travel waits under More settings');
  assert.equal(await editor.locator('svg.animus-route-grid [data-node="points:0"]').getAttribute('aria-label'), 'Point 1 at -6, 64, 0', 'points count from 1');
  await shot(page, `${tag}-04-route`);
  const gridId = await editor.locator('svg.animus-route-grid').getAttribute('id');
  const before = fixture('routes/market-round.json');
  await page.locator(`#${gridId}-speed`).fill('3');
  await page.locator(`#${gridId}-speed`).blur();
  let route = await codeWhen(page, (json) => json.speed === 3);
  for (const key of Object.keys(before)) assert.equal(JSON.stringify(route[key]), JSON.stringify(before[key]), `${key} keeps its value through a Simple edit`);

  // Pick Point 2 from the list: its wait is in seconds, stored in ticks.
  editor = await routeEditor(page);
  // The points list is as tall as its points: no cap, no scroll of its own.
  assert.equal(await editor.getByRole('listbox', {name: 'Route points'}).evaluate(list => getComputedStyle(list).maxHeight), 'none', 'the points list has no height cap');
  await editor.getByRole('listbox', {name: 'Route points'}).getByRole('option', {name: /Point 2/}).click();
  const point = editor.getByRole('group', {name: 'Selected Point 2'});
  await point.waitFor();
  assert.equal(await point.getByLabel('Point 2 X', {exact: true}).inputValue(), '2');
  await point.getByLabel('Point 2 wait in seconds').fill('2');
  await point.getByLabel('Point 2 wait in seconds').blur();
  route = await codeWhen(page, (json) => json.points[1].waitTicks === 40);
  assert.deepEqual(route.points[1], {x: 2, y: 64, z: 8, waitTicks: 40});
  assert.deepEqual(route.points[0], before.points[0], 'the other points are untouched');

  // Add point from the toolbar, then Delete on the map removes it.
  editor = await routeEditor(page);
  const tools = editor.getByRole('toolbar', {name: 'Route tools'});
  if (await tools.getByRole('button', {name: 'Add at end'}).count()) {
    await tools.getByRole('button', {name: 'Add at end'}).click();
  } else {
    await tools.getByRole('button', {name: 'More actions'}).click();
    await page.getByRole('menuitem', {name: 'Add at end'}).click();
  }
  await editor.locator('[data-node="points:3"]').waitFor();
  route = await codeWhen(page, (json) => json.points.length === 4);
  assert.deepEqual(route.points[3], {x: 8, y: 64, z: -4}, 'a new point goes two blocks east of the last');
  editor = await routeEditor(page);
  await editor.locator('[data-node="points:3"]').focus();
  await page.keyboard.press('Delete');
  await page.waitForFunction(() => !document.querySelector('[data-node="points:3"]'));
  await shot(page, `${tag}-05-route-point`);

  // Advanced shows the rest under More settings, with plain labels.
  await setMode(page, 'advanced');
  editor = await routeEditor(page);
  await editor.locator('[data-route-settings] summary').first().click();
  for (const label of ['Travel', 'Style', 'Wait', 'Random picks', 'Start at', 'Spread', 'Link reach']) {
    await editor.locator('[data-route-settings]').getByText(label, {exact: true}).first().waitFor();
  }
  const advancedGrid = await editor.locator('svg.animus-route-grid').getAttribute('id');
  assert.equal(await page.locator(`#${advancedGrid}-wait`).inputValue(), '1', 'the route wait [20, 100] reads 1 to 5 seconds');
  assert.equal(await page.locator(`#${advancedGrid}-wait-max`).inputValue(), '5');
  await shot(page, `${tag}-06-route-advanced`);
  await setMode(page, 'simple');

  // Watchman's own route: a ranged wait and a next point.
  await go(page, `#/workspace/${workspace}/npc/town%2Fwatchman`);
  editor = await routeEditor(page);
  const own = await editor.locator('svg.animus-route-grid').getAttribute('id');
  await editor.locator(`[data-node="points:0"]`).focus();
  await page.keyboard.press('Enter');
  const first = editor.getByRole('group', {name: 'Selected Point 1'});
  await first.waitFor();
  await first.locator('summary').first().click();
  await page.locator(`#${own}-next-add`).click();
  let npc = await codeWhen(page, (json) => json.modules['animus:navigation'].points[0].next.length === 3);
  assert.deepEqual(npc.modules['animus:navigation'].points[0].next.slice(0, 2), [{to: 1, weight: 3}, {to: 2}], 'the listed next points stay');
  editor = await routeEditor(page);
  await editor.getByRole('listbox', {name: 'Route points'}).getByRole('option', {name: /Point 2/}).click();
  const second = editor.getByRole('group', {name: 'Selected Point 2'});
  assert.equal(await second.getByLabel('Point 2 shortest wait in seconds').inputValue(), '2');
  await second.getByLabel('Point 2 longest wait in seconds').fill('4.5');
  await second.getByLabel('Point 2 longest wait in seconds').blur();
  npc = await codeWhen(page, (json) => JSON.stringify(json.modules['animus:navigation'].points[1].waitTicks) === '[40,90]');
  assert.equal(npc.modules['animus:navigation'].seed, 'npc', 'hidden route keys stay');
  await shot(page, `${tag}-07-own-route`);

  // The recording view: scrub, time in seconds, Play.
  await go(page, `#/workspace/${workspace}/recording/market-loop`);
  await page.locator('#animus-recording-path').waitFor();
  assert.equal(await page.locator('[data-recording="facts"]').count(), 0, 'jargon facts wait for Advanced');
  await page.locator('.ui-stage-status').getByText(/^4 s · /).waitFor();
  const scrub = sliderThumb(page, 'animus-recording-scrub');
  assert.equal(await page.getByRole('slider', {name: 'Scrub', exact: true}).count(), 1, 'the scrub is named without a label row');
  await setSlider(scrub, 40);
  const readout = page.locator('[data-recording="readout"]');
  await readout.getByText('2.0 s of 4.0 s', {exact: true}).waitFor();
  assert.match(await scrub.getAttribute('aria-valuetext'), /^2\.0 s of 4\.0 s/, 'the scrub reads as a time');
  assert.equal(Number(await page.locator('#animus-recording-path').getAttribute('data-marker-x')), 103.7);
  // Arrows step one tick, End and Home jump to the ends.
  await scrub.press('ArrowRight');
  await page.waitForFunction(() => document.querySelector('[data-arcane-slider="animus-recording-scrub"] [role="slider"]').getAttribute('aria-valuenow') === '41');
  await scrub.press('End');
  await readout.getByText('4.0 s of 4.0 s', {exact: true}).waitFor();
  await scrub.press('Home');
  await readout.getByText('0.0 s of 4.0 s', {exact: true}).waitFor();
  // Dragging moves the dot before the pointer is let go.
  const letGo = await dragSlider(scrub, 0.1, 0.5);
  await readout.getByText('2.0 s of 4.0 s', {exact: true}).waitFor();
  assert.equal(Number(await page.locator('#animus-recording-path').getAttribute('data-marker-x')), 103.7, 'the dot follows the drag');
  await letGo();
  const play = page.locator('#animus-recording-play');
  await play.click();
  assert.equal(await play.getAttribute('aria-pressed'), 'true');
  await play.click();
  assert.equal(await play.getAttribute('aria-pressed'), 'false');
  // Playback keeps time on a slow page: a 40x slower CPU still plays about 20 ticks a second.
  await setSlider(scrub, 0);
  const cdp = await context.newCDPSession(page);
  await cdp.send('Emulation.setCPUThrottlingRate', {rate: 40});
  const played = await page.evaluate(() => new Promise((resolve) => {
    const start = performance.now();
    document.getElementById('animus-recording-play').click();
    setTimeout(() => resolve({ms: performance.now() - start, seconds: Number(/^([\d.]+) s/.exec(document.querySelector('[data-recording="readout"]').textContent)[1])}), 2000);
  }));
  await cdp.send('Emulation.setCPUThrottlingRate', {rate: 1});
  await cdp.detach();
  if (await play.getAttribute('aria-pressed') === 'true') await play.click();
  assert.ok(played.seconds >= (played.ms - 400) / 1000 - 0.1 && played.seconds <= played.ms / 1000 + 0.15, `${tag}: ${played.seconds} s after ${Math.round(played.ms)} ms`);
  const speed = page.getByRole('group', {name: 'Preview speed'});
  await speed.getByRole('button', {name: '2x'}).click();

  // Details: one rename (the header's), tags as a list, playback, speed.
  assert.equal(await page.locator('#animus-recording-id').count(), 0, 'no second Rename');
  await page.locator('#animus-recording-description').fill('Stall walk');
  await page.locator('#animus-recording-tags').fill('market, night');
  await page.getByRole('group', {name: 'Playback'}).getByRole('button', {name: 'Once'}).click();
  await page.locator('#animus-recording-speed').fill('5');
  await page.getByText('Use 0.25 to 4.').waitFor();
  await page.locator('#animus-recording-speed').fill('2');
  await page.locator('#animus-recording-speed').blur();
  let json = await codeWhen(page, (code) => code.speed === 2 && code.playback === 'once');
  assert.equal(json.description, 'Stall walk');
  assert.deepEqual(json.tags, ['market', 'night']);

  // Trim from the scrub, keyboard first; the buttons are disabled exactly when they cannot act.
  await setSlider(scrub, 0);
  assert.equal(await page.locator('#animus-recording-trim-apply').isDisabled(), true, 'nothing to trim yet');
  assert.equal(await page.locator('#animus-recording-trim-end').isDisabled(), true, 'the end cannot be at the start');
  await setSlider(scrub, 19);
  await page.locator('#animus-recording-trim-start').focus();
  await page.keyboard.press('Enter');
  await setSlider(scrub, 62);
  // Reset joins the row now; a narrow row folds Set end into its menu.
  await page.waitForTimeout(300);
  if (await page.locator('#animus-recording-trim-end').count()) {
    await page.locator('#animus-recording-trim-end').click();
  } else {
    await page.getByRole('toolbar', {name: 'Trim'}).getByRole('button', {name: 'More actions'}).click();
    await page.getByRole('menuitem', {name: 'Set end'}).click();
  }
  await page.locator('[data-recording="trim-range"]').getByText('Keep 1.0 s to 3.0 s').waitFor();
  assert.equal(await page.locator('.animus-recording-line.is-kept').count(), 1);
  await shot(page, `${tag}-08-recording-trim`);
  await page.locator('#animus-recording-trim-apply').click();
  json = await codeWhen(page, (code) => code.durationTicks === 40);
  assert.deepEqual(json.frames.map((frame) => frame[0]), [0, 20, 40]);
  assert.deepEqual(json.origin, {x: 102, y: 64, z: -20.25, yaw: 92.5, pitch: -3});

  // Advanced shows the facts with plain labels.
  await setMode(page, 'advanced');
  await page.locator('.animus-recording-form summary', {hasText: 'Facts'}).click();
  const facts = await page.locator('[data-recording="facts"]').textContent();
  for (const label of ['Frame gap', 'Starts at', 'Pose changes', 'Version']) assert.ok(facts.includes(label), `${label} in ${facts}`);
  for (const jargon of ['Sample ticks', 'Flag changes', 'Revision', 'Origin']) assert.ok(!facts.includes(jargon), `no ${jargon}`);
  await shot(page, `${tag}-09-recording-advanced`);

  // Code: a draft that breaks a rule is refused with the reason.
  await showView(page, 'code');
  const code = page.locator('.animus-code textarea');
  await code.fill(JSON.stringify({...json, frames: json.frames.map((frame, index) => index === 1 ? [0, ...frame.slice(1)] : frame)}, null, 2));
  await page.locator('[data-recording="code-refusal"]').getByText("$.frames[1][0]: expected a tick above the previous frame's 0", {exact: false}).waitFor();
  assert.equal(await page.getByTestId('code-use').isDisabled(), true, 'Use this code waits for a valid draft');
  await page.getByRole('button', {name: 'Discard'}).click();
  await showView(page, 'visual');
  await setMode(page, 'simple');

  // Make route: a route along the recording, each leg in its recorded style.
  await page.locator('#animus-recording-make-route').click();
  await page.locator('#animus-recording-route').fill('market-walk-route');
  await page.locator('#make-route').getByRole('button', {name: 'Make route'}).click();
  await page.waitForFunction(() => location.hash.endsWith('/route/market-walk-route'));
  const made = await codeOf(page);
  assert.equal(made.waitTicks, 0);
  assert.ok(made.points.some((each) => each.style === 'sprint'), `the sprinted stretch keeps its style: ${JSON.stringify(made.points)}`);

  // Rename from the page header carries the NPC that plays it.
  await go(page, `#/workspace/${workspace}/recording/market-loop`);
  await page.locator('#ui-page-actions-button').click();
  await page.getByRole('menuitem', {name: 'Rename', exact: true}).click();
  await page.locator('#ui-title-input').fill('market-walk');
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => location.hash.endsWith('/recording/market-walk'));
  await go(page, `#/workspace/${workspace}/npc/town%2Fstall-keeper`);
  assert.equal((await codeOf(page)).modules['animus:navigation'].recording, 'market-walk');

  assert.deepEqual(errors, [], `${tag}: console errors`);
  await context.close();
}

async function server(browser) {
  await control('reset', {});
  const {code} = await control('pair?paths=both', {});
  const context = await browser.newContext({viewport: {width: 1440, height: 900}});
  const page = await context.newPage();
  page.setDefaultTimeout(20000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  page.on('console', (message) => { if (message.type() === 'error' && !/status of 429|404|Failed to load resource/.test(message.text())) errors.push(message.text()); });
  await page.goto(`${site}/#/pair/${code}`);
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await waitOnline(page);
  const workspace = `server-${decodeURIComponent((await page.evaluate(() => location.hash)).split('/')[2])}`;
  await go(page, `#/workspace/${encodeURIComponent(workspace)}/list/route`);
  await page.waitForFunction(() => document.querySelectorAll('#movement-list .ui-library-row').length === 2, null, {timeout: 20000});
  assert.equal(await page.locator('[data-testid="movement-unread"]').count(), 0, 'every NPC is read once the workspace fills');
  await page.locator('#movement-list .ui-library-row', {hasText: 'Market round'}).getByText('Used by Porter', {exact: false}).waitFor();
  await page.locator('#movement-list .ui-library-row', {hasText: 'Market round'}).click();
  await page.getByTestId('route-used-by').waitFor();
  await page.waitForFunction(() => document.querySelectorAll('svg.animus-route-grid image.animus-route-terrain').length > 0, null, {timeout: 20000});
  await page.getByRole('group', {name: 'Route tool'}).getByRole('button', {name: '3D'}).click();
  await page.locator('[data-route-3d]').waitFor();
  await shot(page, 'server-route-3d');
  await page.getByRole('group', {name: 'Route tool'}).getByRole('button', {name: 'Move'}).click();
  await page.locator('svg.animus-route-grid').waitFor();
  assert.deepEqual(errors, [], 'server: console errors');
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    await local(browser, {width: 1440, height: 900}, 'dark', 'desktop-dark');
    await local(browser, {width: 390, height: 844}, 'light', 'phone-light');
    await server(browser);
  } finally {
    await browser.close();
  }
  console.log('movement_browser: ok');
})().catch((error) => { console.error(error.stack || error.message || error); process.exit(1); });
