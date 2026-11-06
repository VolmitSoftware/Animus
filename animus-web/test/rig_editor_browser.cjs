// Opens test/fixtures/rig_editor as a data folder in the built site and runs
// the rig editor: the golem draws, the dangling idle name gets its fix, a
// name is renamed, the gizmo moves and nudges a bone, the bone tree answers
// the keyboard, the second track of a bone is keyed, a blend changes, an
// event is added with its marker, a shorter duration warns and trims, a bad
// bezier curve is explained at the field, the stage orbits from the
// keyboard, the server files and the workspace export as zips, and a
// Blockbench model imports. It also checks the pack loading, missing-pack and
// retry states, the phone pane switch, that a vertical swipe over the stage
// scrolls the page, both themes, no horizontal overflow at 390 px and no
// console errors. Needs the asset pack in the build.
//
//   python3 tool/serve_site.py --port <port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_SCREENSHOTS=<folder> node test/rig_editor_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {closeDrawer, sidebar, topbarAction} = require('./support/shell.cjs');

const folder = path.resolve(__dirname, 'fixtures/rig_editor');
const bbmodel = path.resolve(__dirname, 'fixtures/bbmodel/robot.bbmodel');
const shots = process.env.ANIMUS_SCREENSHOTS;
const site = process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })();
const steps = [];

function step(label, text) {
  steps.push(`${label}: ${text}`);
}

async function colours(page, png) {
  return page.evaluate(async base64 => {
    const image = new Image();
    image.src = `data:image/png;base64,${base64}`;
    await image.decode();
    const canvas = document.createElement('canvas');
    canvas.width = image.naturalWidth;
    canvas.height = image.naturalHeight;
    const context = canvas.getContext('2d', {willReadFrequently: true});
    context.drawImage(image, 0, 0);
    const data = context.getImageData(0, 0, canvas.width, canvas.height).data;
    const seen = new Set();
    for (let i = 0; i < data.length; i += 16) seen.add((data[i] << 16) | (data[i + 1] << 8) | data[i + 2]);
    return seen.size;
  }, png.toString('base64'));
}

async function drawn(page) {
  await page.waitForFunction(() => {
    const element = document.querySelector('.animus-mc-gl-host');
    return element && Number(element.getAttribute('data-frames')) > 0 && element.getAttribute('data-unbuilt') === '0';
  });
}

async function frames(page) {
  return Number(await page.locator('.animus-mc-gl-host').getAttribute('data-frames'));
}

async function drag(page, locator, dx, dy) {
  await locator.scrollIntoViewIfNeeded();
  const box = await locator.boundingBox();
  const x = box.x + box.width / 2, y = box.y + box.height / 2;
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x + dx, y + dy, {steps: 6});
  await page.mouse.up();
}

async function shot(page, label, name, locator) {
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  const png = locator ? await locator.screenshot() : await page.screenshot({fullPage: true});
  fs.writeFileSync(path.join(shots, `rig-${label}-${name}.png`), png);
}

async function noOverflow(page, label) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  assert.ok(overflow <= 0, `${label}: ${overflow}px of horizontal overflow`);
}

function watch(page, errors, allowed = []) {
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => {
    if (message.type() !== 'error') return;
    if (allowed.some(pattern => pattern.test(message.text()))) return;
    errors.push(message.text());
  });
}

async function openFolder(page) {
  await page.goto(site);
  await page.getByRole('heading', {name: 'From a data folder'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(folder);
  await (await sidebar(page)).getByText('town:golem', {exact: true}).waitFor();
  await closeDrawer(page);
  return {base: page.url().split('#')[0], workspace: page.url().split('#')[1].split('?')[0]};
}

async function theme(page, dark) {
  const isDark = await page.evaluate(() => document.documentElement.classList.contains('dark'));
  if (isDark !== dark) await topbarAction(page, dark ? 'Use dark theme' : 'Use light theme');
  await page.waitForFunction(want => document.documentElement.classList.contains(want ? 'dark' : 'light'), dark);
}

/// Shows [pane] on a phone; a no-op on desktop where every pane shows.
async function pane(page, name, phone) {
  if (phone) await page.locator('.animus-rig-panes').getByRole('button', {name, exact: true}).click();
}

async function run(browser, viewport, label, dark) {
  const phone = viewport.width < 600;
  const context = await browser.newContext({viewport, acceptDownloads: true, hasTouch: phone});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  watch(page, errors);
  const {base, workspace} = await openFolder(page);
  await theme(page, dark);

  await page.goto(`${base}#${workspace}/rig/${encodeURIComponent('town:golem')}`);
  await page.locator('.animus-rig-editor[data-rig="town:golem"]').waitFor();
  await drawn(page);
  assert.equal(await page.locator('.animus-mc-stage').getAttribute('data-webgl'), 'ready');
  assert.equal(await page.locator('.animus-mc-stage').getAttribute('tabindex'), '0');
  assert.equal(await page.locator('.animus-rig-bone-dot').count(), 5, 'one dot per bone');
  assert.ok((await page.locator('.animus-mc-text-line').allTextContents()).includes('Golem'), 'text bone');
  assert.ok(await colours(page, await page.locator('.animus-mc-stage').screenshot()) > 40, 'stage looks blank');
  await noOverflow(page, label);
  await shot(page, label, 'open');
  step(label, 'open rig -> stage draws 5 bones, text bone, WebGL ready, no overflow');

  // The dangling idle name is marked with a fix; Create makes it real.
  await pane(page, 'Timeline', phone);
  await page.locator('.animus-rig-names-toggle').click();
  const idle = page.locator('.animus-rig-name[data-name="idle"]');
  assert.ok((await idle.getAttribute('class')).includes('is-broken'));
  await idle.getByText('is not in this workspace').waitFor();
  await idle.getByRole('button', {name: 'Create town:golem/idle'}).click();
  await page.waitForFunction(() => !document.querySelector('.animus-rig-name[data-name="idle"]').classList.contains('is-broken'));
  assert.equal(await page.locator('.animus-rig-timeline select').first().inputValue(), 'town:golem/idle', 'the new clip opens');
  step(label, 'names: dangling idle marked, Create town:golem/idle -> fixed and opened');

  // Rename a name in place.
  const walkName = page.getByLabel('Name walk', {exact: true});
  await walkName.fill('stroll');
  await walkName.press('Enter');
  await page.locator('.animus-rig-name[data-name="stroll"]').waitFor();
  const strollName = page.getByLabel('Name stroll', {exact: true});
  await strollName.fill('walk');
  await strollName.press('Enter');
  await page.locator('.animus-rig-name[data-name="walk"]').waitFor();
  step(label, 'names: walk -> stroll -> walk renamed in place');
  await page.locator('.animus-rig-names-toggle').click();
  await page.locator('.animus-rig-timeline select').first().selectOption('');

  // The bone tree answers the keyboard.
  await pane(page, 'Bones', phone);
  await page.locator('.animus-rig-tree [data-bone="root"]').click();
  await page.locator('.animus-rig-tree [data-bone="root"]').press('ArrowDown');
  await page.waitForFunction(() => document.querySelector('.animus-rig-tree [data-bone="torso"]').getAttribute('aria-selected') === 'true');
  assert.equal(await page.evaluate(() => document.activeElement.getAttribute('data-bone')), 'torso');
  assert.equal(await page.locator('.animus-rig-tree [data-bone="head"]').getAttribute('aria-level'), '3');
  step(label, 'tree: ArrowDown moves focus and selection root -> torso, aria-level 3 on head');

  // Move the torso with the Y handle: its pivot rises, with the subtree.
  await page.getByRole('button', {name: 'Move', exact: true}).click();
  await pane(page, 'Inspector', phone);
  const pivotY = page.getByLabel('Pivot Y');
  assert.equal(await pivotY.inputValue(), '1.1');
  await drag(page, page.locator('[data-gizmo="handle"][data-axis="y"]'), 0, -40);
  await page.waitForFunction(() => document.querySelector('[aria-label="Pivot Y"]').value !== '1.1');
  assert.ok(Number(await pivotY.inputValue()) > 1.1, 'pivot rose');
  step(label, 'gizmo: drag Y handle -> torso pivot rises');
  await topbarAction(page, 'Undo');
  await page.waitForFunction(() => document.querySelector('[aria-label="Pivot Y"]').value === '1.1');
  step(label, 'Undo -> pivot back to 1.1');

  // Nudge with the keyboard: one press is a 16th of a block.
  await page.locator('[data-gizmo="handle"][data-axis="y"]').focus();
  await page.keyboard.press('ArrowUp');
  await page.waitForFunction(() => document.querySelector('[aria-label="Pivot Y"]').value === '1.1625');
  step(label, 'gizmo: focused Y handle + ArrowUp -> pivot 1.1625');
  await topbarAction(page, 'Undo');
  await page.waitForFunction(() => document.querySelector('[aria-label="Pivot Y"]').value === '1.1');

  // Rename a bone through the tree.
  await pane(page, 'Bones', phone);
  await page.locator('.animus-rig-tree [data-bone="arm"]').click();
  await page.getByLabel('Bone name').fill('right-arm');
  await page.locator('.animus-rig-tree').getByRole('button', {name: 'Rename'}).click();
  await page.locator('.animus-rig-tree [data-bone="right-arm"]').waitFor();
  step(label, 'tree: rename arm -> right-arm');

  // The walk animation: key the second rotation track of the arm.
  await pane(page, 'Timeline', phone);
  await page.locator('.animus-rig-timeline select').first().selectOption('town:golem/walk');
  await page.locator('.animus-rig-lane[data-track="3"]').waitFor();
  const keysOf = track => page.locator(`.animus-rig-lane[data-track="${track}"] .animus-rig-key`).count();
  const replaceKeys = await keysOf(2), addKeys = await keysOf(3);
  await page.locator('.animus-rig-lane[data-track="3"] .animus-rig-lane-label').click();
  await page.locator('.animus-rig-range').fill('7.5');
  await page.locator('.animus-rig-tick').getByText('tick 7.5 / 20').waitFor();
  await page.getByRole('button', {name: 'Add keyframe'}).click();
  await page.waitForFunction(n => document.querySelectorAll('.animus-rig-lane[data-track="3"] .animus-rig-key').length > n, addKeys);
  assert.equal(await keysOf(2), replaceKeys, 'the first rotation track is untouched');
  step(label, 'timeline: select track 4 (right-arm rotation add), Add keyframe -> keyed there, track 3 unchanged');

  // Blend: track 1 becomes multiply.
  await page.locator('.animus-rig-lane[data-track="0"] .animus-rig-lane-label').click();
  await page.locator('[aria-label="Track"] select').selectOption('multiply');
  await page.locator('.animus-rig-lane[data-track="0"] .animus-rig-blend').getByText('multiply').waitFor();
  step(label, 'track panel: blend replace -> multiply, lane shows multiply');

  // Events: one in the fixture; Add event puts a second marker on the lane and ruler.
  assert.equal(await page.locator('.animus-rig-event').count(), 1);
  await page.getByRole('button', {name: 'Add event'}).click();
  await page.waitForFunction(() => document.querySelectorAll('.animus-rig-event').length === 2);
  assert.equal(await page.locator('.animus-rig-ruler-event').count(), 2);
  await page.locator('.animus-rig-event-editor').waitFor();
  const sound = page.getByLabel(/^Event sound/);
  await sound.fill('minecraft:block.anvil.land');
  await sound.press('Enter');
  await page.waitForFunction(() => [...document.querySelectorAll('.animus-rig-event')].some(e => e.getAttribute('aria-label').includes('block.anvil.land')));
  step(label, 'events: Add event -> second marker on lane and ruler, sound edited');

  // Keyframe diamonds: focus one and move it with the arrow keys.
  const diamond = page.locator('.animus-rig-lane[data-track="1"] .animus-rig-key').nth(1);
  const before = await diamond.getAttribute('aria-label');
  await diamond.focus();
  await page.keyboard.press('ArrowRight');
  await page.waitForFunction(text => {
    const keys = [...document.querySelectorAll('.animus-rig-lane[data-track="1"] .animus-rig-key')];
    return keys.some(key => key.getAttribute('aria-label').includes('tick 6'));
  }, before);
  step(label, 'diamond: focus + ArrowRight -> head translation key moves 5 -> 6');

  // Bezier: a bad curve is explained at the field.
  await page.locator('.animus-rig-lane[data-track="0"] .animus-rig-key').first().click();
  const keyframe = page.locator('[aria-label="Keyframe"]');
  await keyframe.locator('select').first().selectOption('bezier');
  const curve = page.getByLabel('Bezier curve');
  await curve.waitFor();
  await curve.fill('bezier(.5,0,1,1)');
  await curve.press('Enter');
  await keyframe.locator('.animus-field-error').getByText('Use bezier(x1,y1,x2,y2) with numbers like 0.25').waitFor();
  await curve.fill('bezier(0.5,0,1,1)');
  await curve.press('Enter');
  await page.waitForFunction(() => !document.querySelector('[aria-label="Keyframe"] .animus-field-error'));
  step(label, 'bezier: bezier(.5,0,1,1) -> field error with the hint; bezier(0.5,0,1,1) -> accepted');

  // A shorter duration warns and offers to trim.
  const duration = page.getByLabel('Duration in ticks');
  await duration.fill('12');
  await duration.press('Enter');
  await page.locator('.animus-rig-trim').getByText(/Shortening to 12 ticks drops \d+ keyframes/).waitFor();
  await page.getByRole('button', {name: 'Trim and shorten'}).click();
  await page.locator('.animus-rig-tick').getByText('/ 12').waitFor();
  step(label, 'duration 20 -> 12: warning with counts, Trim and shorten -> 12 ticks');
  await shot(page, label, 'timeline');

  // The stage orbits from the keyboard.
  const orbit = await frames(page);
  await page.locator('.animus-mc-stage').focus();
  await page.keyboard.press('ArrowLeft');
  await page.keyboard.press('Shift+ArrowUp');
  await page.keyboard.press('+');
  await page.waitForFunction(n => Number(document.querySelector('.animus-mc-gl-host').getAttribute('data-frames')) > n, orbit);
  assert.ok(await page.locator('.animus-mc-keys').isVisible(), 'the key list is on screen');
  await page.keyboard.press('0');
  step(label, 'stage: focus + ArrowLeft, Shift+ArrowUp, + -> new frames; key list on screen; 0 resets');

  // Play and pause.
  const playFrames = await frames(page);
  await page.locator('.animus-rig-transport').getByRole('button', {name: 'Play'}).click();
  await page.waitForFunction(n => Number(document.querySelector('.animus-mc-gl-host').getAttribute('data-frames')) > n + 5, playFrames);
  await page.locator('.animus-rig-transport').getByRole('button', {name: 'Pause'}).click();
  step(label, 'Play -> frames advance, Pause -> stops');

  // Server files: the rig, its animations and pack sources as a zip.
  const pack = page.waitForEvent('download');
  await page.getByRole('button', {name: 'Download server files'}).click();
  const packFile = await (await pack).path();
  assert.equal((await pack).suggestedFilename(), 'town-golem-server-files.zip');
  const packBytes = fs.readFileSync(packFile);
  assert.ok(packBytes.includes('rigs/town/golem.json') && packBytes.includes('animations/town/golem/walk.json'));
  assert.ok(packBytes.includes('"right-arm"'), 'the rename is in the export');
  await page.getByText('/animus pack build').first().waitFor();
  step(label, 'Download server files -> zip with rig and animations, toast names /animus pack build');

  if (phone) {
    // A vertical swipe over the stage scrolls the page instead of orbiting.
    await page.evaluate(() => window.scrollTo(0, 0));
    const stage = page.locator('.animus-mc-stage');
    await stage.scrollIntoViewIfNeeded();
    const box = await stage.boundingBox();
    const start = await page.evaluate(() => window.scrollY);
    const cdp = await context.newCDPSession(page);
    const x = box.x + box.width / 2, y = box.y + box.height * 0.7;
    await cdp.send('Input.dispatchTouchEvent', {type: 'touchStart', touchPoints: [{x, y}]});
    for (let i = 1; i <= 8; i++) await cdp.send('Input.dispatchTouchEvent', {type: 'touchMove', touchPoints: [{x, y: y - i * 30}]});
    await cdp.send('Input.dispatchTouchEvent', {type: 'touchEnd', touchPoints: []});
    await page.waitForFunction(s => window.scrollY > s, start);
    step(label, 'touch: vertical swipe over the stage -> page scrolls');
    await noOverflow(page, label);
  }

  // Import a Blockbench model: a new rig drawn with its own texture.
  const importer = page.waitForEvent('filechooser');
  await topbarAction(page, 'Import a Blockbench model', {menu: 'import'});
  await (await importer).setFiles(bbmodel);
  await page.locator('.animus-rig-editor[data-rig="town:robot"]').waitFor();
  await drawn(page);
  assert.equal(await page.locator('.animus-rig-bone-dot').count(), 5);
  assert.ok(await colours(page, await page.locator('.animus-mc-stage').screenshot()) > 40, 'robot stage looks blank');
  step(label, 'Import .bbmodel -> town:robot drawn with its own texture');

  const exported = page.waitForEvent('download');
  await topbarAction(page, 'Export the data folder zip', {menu: 'export'});
  const zip = fs.readFileSync(await (await exported).path());
  for (const entry of ['rigs/town/robot.json', 'animations/town/robot/walk.json', 'pack/assets/animus/textures/item/robot/robot.png']) {
    assert.ok(zip.includes(entry), `export holds ${entry}`);
  }
  step(label, 'Export zip -> imported rig, animation and texture in the zip');
  await noOverflow(page, label);
  await shot(page, label, 'final');

  assert.deepEqual(errors, [], `${label}: console errors`);
  await context.close();
}

/// The other theme at the same width: open, draw, no overflow, no errors.
async function glance(browser, viewport, label, dark) {
  const context = await browser.newContext({viewport});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  watch(page, errors);
  const {base, workspace} = await openFolder(page);
  await theme(page, dark);
  await page.goto(`${base}#${workspace}/rig/${encodeURIComponent('town:golem')}`);
  await page.locator('.animus-rig-editor').waitFor();
  if (viewport.width < 600) await pane(page, 'Timeline', true);
  await page.locator('.animus-rig-timeline select').first().selectOption('town:golem/walk');
  await page.locator('.animus-rig-lane[data-track="0"]').waitFor();
  await drawn(page);
  await noOverflow(page, label);
  await shot(page, label, 'glance');
  assert.deepEqual(errors, [], `${label}: console errors`);
  step(label, 'open the walk animation -> stage draws, no overflow, no console errors');
  await context.close();
}

/// The pack's loading, missing and retry states, each in a fresh page.
async function packStates(browser) {
  const viewport = {width: 1440, height: 900};
  // Slow pack: the loading line shows.
  {
    const context = await browser.newContext({viewport});
    const page = await context.newPage();
    const errors = [];
    watch(page, errors);
    let release;
    const held = new Promise(resolve => { release = resolve; });
    await page.route('**/assets/mc/*/models.json', async route => { await held; await route.continue(); });
    const {base, workspace} = await openFolder(page);
    await page.goto(`${base}#${workspace}/rig/${encodeURIComponent('town:golem')}`);
    await page.locator('.animus-mc-status').getByText('Loading the Minecraft models (about 2 MB).').waitFor();
    assert.equal(await page.locator('.animus-mc-stage').getAttribute('data-pack'), 'loading');
    await shot(page, 'pack', 'loading');
    release();
    await drawn(page);
    assert.equal(await page.locator('.animus-mc-status').getByText('Loading the Minecraft models').count(), 0);
    assert.deepEqual(errors, []);
    step('pack', 'held models.json -> loading line and spinner, released -> drawn');
    await context.close();
  }
  // No pack on the site: the README cause, no retry.
  {
    const context = await browser.newContext({viewport});
    const page = await context.newPage();
    const errors = [];
    watch(page, errors, [/404/]);
    await page.route('**/assets/mc/*/pack.json', route => route.fulfill({status: 404, body: 'not found'}));
    const {base, workspace} = await openFolder(page);
    await page.goto(`${base}#${workspace}/rig/${encodeURIComponent('town:golem')}`);
    await page.locator('.animus-mc-problem').getByText('The model pack is not on this site. The preview needs the pack described in the README.').waitFor();
    assert.equal(await page.locator('.animus-mc-problem').getByRole('button', {name: 'Retry'}).count(), 0);
    await shot(page, 'pack', 'missing');
    assert.deepEqual(errors, []);
    step('pack', 'pack.json 404 -> "The model pack is not on this site..." without Retry');
    await context.close();
  }
  // Network failure: Retry loads the pack once the network is back.
  {
    const context = await browser.newContext({viewport});
    const page = await context.newPage();
    const errors = [];
    watch(page, errors, [/ERR_FAILED|Failed to load resource/]);
    await page.route('**/assets/mc/*/pack.json', route => route.abort('failed'));
    const {base, workspace} = await openFolder(page);
    await page.goto(`${base}#${workspace}/rig/${encodeURIComponent('town:golem')}`);
    await page.locator('.animus-mc-problem').getByText('The models could not be downloaded. Check the connection and retry.').waitFor();
    await shot(page, 'pack', 'network');
    await page.unroute('**/assets/mc/*/pack.json');
    await page.locator('.animus-mc-problem').getByRole('button', {name: 'Retry'}).click();
    await drawn(page);
    assert.equal(await page.locator('.animus-mc-problem').count(), 0);
    assert.deepEqual(errors, []);
    step('pack', 'pack.json aborted -> download copy with Retry; Retry -> drawn');
    await context.close();
  }
}

(async () => {
  const browser = await chromium.launch({headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
  try {
    await run(browser, {width: 1440, height: 900}, 'desktop-dark', true);
    await run(browser, {width: 390, height: 844}, 'phone-light', false);
    await glance(browser, {width: 1440, height: 900}, 'desktop-light', false);
    await glance(browser, {width: 390, height: 844}, 'phone-dark', true);
    await packStates(browser);
    for (const line of steps) console.log(line);
    console.log('rig editor browser check passed');
  } finally {
    await browser.close();
  }
})().catch(error => {
  for (const line of steps) console.log(line);
  console.error(error);
  process.exit(1);
});
