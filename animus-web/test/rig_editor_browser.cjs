// Opens test/fixtures/rig_editor as a data folder in the built site and runs
// the rig editor: the golem draws in its stage frame, the dangling idle name
// gets its fix and the new animation opens, a name is renamed, the bone tree
// answers the keyboard, the gizmo moves and nudges a bone, a bone is renamed
// by leaving its name field, deleting a bone asks first and says what goes
// with it, the second track of a bone is keyed, a blend changes, an event is
// added and edited, the playhead slider follows a drag and the keyboard, a
// keyframe moves by keyboard, a bad curve is explained at the field, a
// shorter length asks before trimming, the stage orbits from
// the keyboard, playback runs, New animation asks for a name, the server
// files and the workspace export as zips, and a Blockbench model imports. It
// also checks the stage frame (header toggle, 240 px canvas at least, status
// line), the Bones / Inspector / Timeline tabs in a narrow editor, the gizmo
// handles inside the canvas and apart, a vertical swipe over the stage
// scrolling the page, the 3D model loading, missing and retry states, both
// themes, no horizontal overflow and no console errors. Needs the asset pack
// in the build.
//
//   python3 tool/serve_site.py --port <port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_SCREENSHOTS=<folder> node test/rig_editor_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {topbarAction, workspaceOpen} = require('./support/shell.cjs');
const {chooseAndWait, valueOf, waitForValue} = require('./support/select.cjs');
const {dragSlider, setSlider, sliderThumb} = require('./support/slider.cjs');

const clipPicker = page => page.locator('button[role="combobox"]#rig-animation');

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

async function shot(page, label, name) {
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  fs.writeFileSync(path.join(shots, `rig-${label}-${name}.png`), await page.screenshot({fullPage: true}));
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
  await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(folder);
  await workspaceOpen(page);
  return {base: page.url().split('#')[0], workspace: page.url().split('#')[1].split('?')[0]};
}

async function theme(page, dark) {
  const isDark = await page.evaluate(() => document.documentElement.classList.contains('dark'));
  if (isDark !== dark) await topbarAction(page, dark ? 'Use dark theme' : 'Use light theme');
  await page.waitForFunction(want => document.documentElement.classList.contains(want ? 'dark' : 'light'), dark);
}

/** Shows [name]'s tab in a narrow editor; a no-op where every pane shows. */
async function pane(page, name) {
  const tabs = page.locator('.animus-rig-tabs');
  if (await tabs.isVisible()) {
    await tabs.getByRole('tab', {name, exact: true}).click();
    await page.waitForFunction(want => document.querySelector('.animus-rig-layout').getAttribute('data-pane') === want, name.toLowerCase());
  }
}

/** Runs a toolbar action: its button, or its entry in "..." once folded. */
async function toolbarAction(page, name) {
  const button = page.locator('[role="toolbar"][aria-label="Rig"]').getByRole('button', {name});
  if (await button.isVisible()) return button.click();
  await page.locator('#rig-toolbar-more-button').click();
  await page.getByRole('menuitem', {name}).click();
}

async function confirm(page, title, answer) {
  const dialog = page.getByRole('alertdialog', {name: title});
  await dialog.waitFor();
  const text = await dialog.innerText();
  await dialog.getByRole('button', {name: answer, exact: true}).click();
  await dialog.waitFor({state: 'hidden'});
  return text;
}

/** The stage frame: header with the gizmo modes, a canvas of 240 px or more, one status line, handles inside the canvas and apart. */
async function frameChecks(page, label) {
  const frame = page.locator('.animus-rig-stage .ui-stage');
  await frame.locator('.ui-stage-head').getByRole('group', {name: 'Gizmo'}).waitFor();
  for (const name of ['Reset view', 'Fullscreen']) assert.equal(await frame.getByRole('button', {name, exact: true}).count(), 1, `${label}: ${name}`);
  const canvas = await frame.locator('.ui-stage-canvas').boundingBox();
  assert.ok(canvas.height >= 239.5, `${label}: the canvas is ${canvas.height}px tall`);
  assert.equal(await frame.locator('.ui-stage-status').count(), 1);
  const head = await frame.locator('.ui-stage-head').boundingBox();
  const handles = await page.locator('[data-gizmo="handle"]').evaluateAll(nodes => nodes.map(node => {
    const box = node.getBoundingClientRect();
    return {x: box.x + box.width / 2, y: box.y + box.height / 2, r: box.width / 2};
  }));
  assert.ok(handles.length > 0, `${label}: no handles`);
  for (const [index, handle] of handles.entries()) {
    assert.ok(handle.y - handle.r >= canvas.y - 0.5 && handle.y + handle.r <= canvas.y + canvas.height + 0.5, `${label}: handle ${index} leaves the canvas`);
    assert.ok(handle.x - handle.r >= canvas.x - 0.5 && handle.x + handle.r <= canvas.x + canvas.width + 0.5, `${label}: handle ${index} leaves the canvas`);
    assert.ok(handle.y - handle.r >= head.y + head.height - 0.5, `${label}: handle ${index} covers the header`);
    for (const other of handles.slice(index + 1)) {
      assert.ok(Math.hypot(handle.x - other.x, handle.y - other.y) >= handle.r + other.r - 0.5, `${label}: handles overlap`);
    }
  }
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
  await frameChecks(page, label);
  assert.equal(await page.locator('.animus-rig-tabs').isVisible(), viewport.width < 1100, 'tabs only in a narrow editor');
  if (viewport.width < 1100) assert.equal(await page.locator('.animus-rig-layout').getAttribute('data-pane'), 'bones', 'a rig opens on its bones');
  await noOverflow(page, label);
  await shot(page, label, 'open');
  step(label, 'open rig -> stage frame draws 5 bones and the text bone, canvas >= 240 px, handles inside and apart, no overflow');

  // Rest pose: the timeline says to pick an animation.
  await pane(page, 'Timeline');
  await page.locator('.animus-rig-timeline').getByText('Pick an animation to key bones.').waitFor();

  // The dangling idle name is marked with a fix; Create makes it real and opens it.
  const names = page.locator('[data-testid="rig-names"]');
  const idle = names.locator('.animus-rig-name[data-name="idle"]');
  await idle.waitFor();
  assert.equal(await idle.getAttribute('data-broken'), '');
  await idle.getByText('is not in this workspace').waitFor();
  await idle.getByRole('button', {name: 'Create town:golem/idle'}).click();
  await page.waitForFunction(() => !document.querySelector('.animus-rig-name[data-name="idle"]').hasAttribute('data-broken'));
  await waitForValue(clipPicker(page), 'town:golem/idle').catch(() => {});
  assert.equal(await valueOf(clipPicker(page)), 'town:golem/idle', 'the new clip opens');
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
  await chooseAndWait(clipPicker(page), '');
  await page.locator('.animus-rig-timeline').getByText('Pick an animation to key bones.').waitFor();

  // New animation asks for a name; Escape closes it.
  await page.locator('[role="toolbar"][aria-label="Animation"]').getByRole('button', {name: 'New animation'}).click();
  await page.getByRole('dialog', {name: 'New animation'}).waitFor();
  await page.keyboard.press('Escape');
  await page.getByRole('dialog', {name: 'New animation'}).waitFor({state: 'hidden'});

  // The bone tree answers the keyboard.
  await pane(page, 'Bones');
  await page.locator('.animus-rig-tree [data-bone="root"]').click();
  await page.locator('.animus-rig-tree [data-bone="root"]').press('ArrowDown');
  await page.waitForFunction(() => document.querySelector('.animus-rig-tree [data-bone="torso"]').getAttribute('aria-selected') === 'true');
  assert.equal(await page.evaluate(() => document.activeElement.getAttribute('data-bone')), 'torso');
  assert.equal(await page.locator('.animus-rig-tree [data-bone="head"]').getAttribute('aria-level'), '3');
  step(label, 'tree: ArrowDown moves focus and selection root -> torso, aria-level 3 on head');
  // The inspector shows every row of a bone with a visual, down to Scale: it never cuts rows off behind a scroll of its own.
  await pane(page, 'Inspector');
  const inspector = await page.evaluate(() => {
    const side = document.querySelector('.animus-rig-pane.is-inspector');
    const scale = [...side.querySelectorAll('*')].filter((e) => e.childElementCount === 0 && e.textContent.trim() === 'Scale').pop();
    const box = side.getBoundingClientRect();
    return {inner: side.scrollHeight - side.clientHeight, scale: !!scale, below: scale ? scale.getBoundingClientRect().bottom - box.bottom : null};
  });
  assert.ok(inspector.scale, `${label}: the torso's Scale row is in the inspector`);
  assert.ok(inspector.inner <= 1 && inspector.below <= 1, `${label}: the inspector scrolls inside or cuts rows: ${JSON.stringify(inspector)}`);

  // Move the torso with the Y handle: its pivot rises, with the subtree.
  await page.locator('.ui-stage-head').getByRole('radio', {name: 'Move'}).or(page.locator('.ui-stage-head').getByRole('button', {name: 'Move', exact: true})).first().click();
  await pane(page, 'Inspector');
  const pivotY = page.getByLabel('Pivot Y', {exact: true});
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

  // Rename a bone by leaving its name field: no Rename button.
  await pane(page, 'Bones');
  await page.locator('.animus-rig-tree [data-bone="arm"]').click();
  assert.equal(await page.locator('.animus-rig-tree').getByRole('button', {name: 'Rename'}).count(), 0, 'no Rename button');
  const boneName = page.locator('#rig-bone-name');
  await boneName.fill('right-arm');
  await boneName.press('Tab');
  await page.locator('.animus-rig-tree [data-bone="right-arm"]').waitFor();
  await boneName.fill('Bad Name');
  await boneName.press('Enter');
  await page.locator('.animus-rig-tree :is([data-problem], [data-slot="field-error"])').first().waitFor();
  await boneName.fill('right-arm');
  await boneName.press('Enter');
  step(label, 'tree: name field arm -> right-arm on leaving it; a bad name is explained at the field');

  // Delete asks first and names the tracks it takes; Cancel keeps the bone.
  await page.locator('.animus-rig-tree [data-bone="right-arm"]').click();
  await page.locator('#rig-bone-more-button').click();
  await page.getByRole('menuitem', {name: 'Delete bone'}).click();
  const asked = await confirm(page, 'Delete bone right-arm?', 'Cancel');
  assert.match(asked, /Its 2 tracks in 1 animation go too\. Undo brings it back\./);
  assert.equal(await page.locator('.animus-rig-tree [data-bone="right-arm"]').count(), 1, 'Cancel keeps the bone');
  step(label, 'delete bone -> asks, says its 2 tracks in 1 animation go too; Cancel keeps it');

  // The walk animation: key the second rotation track of the arm.
  await pane(page, 'Timeline');
  await chooseAndWait(clipPicker(page), 'town:golem/walk');
  await page.locator('.animus-rig-lane[data-track="3"]').waitFor();
  const keysOf = track => page.locator(`.animus-rig-lane[data-track="${track}"] .animus-rig-key`).count();
  const replaceKeys = await keysOf(2), addKeys = await keysOf(3);
  await page.locator('.animus-rig-lane[data-track="3"] [data-lane]').click();
  // The playhead slider: a drag moves the playhead before the pointer is let go, Home jumps to
  // tick 0 and an arrow steps a quarter tick.
  const scrub = sliderThumb(page, 'rig-scrub');
  assert.equal(await page.getByRole('slider', {name: 'Playhead tick', exact: true}).count(), 1, 'the playhead slider is named');
  const letGo = await dragSlider(scrub, 0.1, 0.5);
  await page.locator('[data-tick]').getByText('10 / 20 ticks').waitFor();
  assert.equal(await page.locator('.animus-rig-ruler .animus-rig-playhead').evaluate(el => parseFloat(el.style.left)), 50, 'the playhead follows the drag');
  await letGo();
  await scrub.press('Home');
  await page.locator('[data-tick]').getByText(/^0 \/ 20 ticks/).waitFor();
  await scrub.press('ArrowRight');
  await page.locator('[data-tick]').getByText('0.25 / 20 ticks').waitFor();
  await setSlider(scrub, 7.5);
  await page.locator('[data-tick]').getByText('7.5 / 20 ticks').waitFor();
  await toolbarButton(page, 'Keys', 'Add keyframe');
  await page.waitForFunction(n => document.querySelectorAll('.animus-rig-lane[data-track="3"] .animus-rig-key').length > n, addKeys);
  assert.equal(await keysOf(2), replaceKeys, 'the first rotation track is untouched');
  step(label, 'timeline: select track 4 (right-arm rotation add), Add keyframe -> keyed there, track 3 unchanged');

  // Blend: track 1 becomes multiply.
  await page.locator('.animus-rig-lane[data-track="0"] [data-lane]').click();
  await chooseAndWait(page.locator('[aria-label="Track"] button[role="combobox"]'), 'multiply');
  await page.locator('.animus-rig-lane[data-track="0"] [data-lane]').getByText('multiply').waitFor();
  step(label, 'track panel: blend replace -> multiply, lane shows multiply');

  // Events: one in the fixture; Add event puts a second marker on the lane.
  assert.equal(await page.locator('.animus-rig-event').count(), 1);
  await toolbarButton(page, 'Keys', 'Add event');
  await page.waitForFunction(() => document.querySelectorAll('.animus-rig-event').length === 2);
  await page.locator('.animus-rig-event-editor').waitFor();
  const sound = page.locator('#rig-event-sound');
  await sound.fill('minecraft:block.anvil.land');
  await sound.press('Enter');
  await page.waitForFunction(() => [...document.querySelectorAll('.animus-rig-event')].some(e => e.getAttribute('aria-label').includes('block.anvil.land')));
  step(label, 'events: Add event -> second marker on the lane, sound edited');

  // Keyframe diamonds: focus one and move it with the arrow keys.
  const diamond = page.locator('.animus-rig-lane[data-track="1"] .animus-rig-key').nth(1);
  await diamond.focus();
  await page.keyboard.press('ArrowRight');
  await page.waitForFunction(() => [...document.querySelectorAll('.animus-rig-lane[data-track="1"] .animus-rig-key')].some(key => key.getAttribute('aria-label').includes('tick 6')));
  step(label, 'diamond: focus + ArrowRight -> head position key moves 5 -> 6');

  // Curve: a bad curve is explained at the field.
  await page.locator('.animus-rig-lane[data-track="0"] .animus-rig-key').first().click();
  const keyframe = page.locator('[aria-label="Keyframe"]');
  await chooseAndWait(keyframe.locator('button[role="combobox"]#rig-key-easing'), 'bezier');
  const curve = page.locator('#rig-key-curve');
  await curve.waitFor();
  await curve.fill('bezier(.5,0,1,1)');
  await curve.press('Enter');
  await keyframe.locator(':is([data-problem], [data-slot="field-error"])').getByText('Use bezier(x1,y1,x2,y2) with numbers like 0.25').waitFor();
  await curve.fill('bezier(0.5,0,1,1)');
  await curve.press('Enter');
  await page.waitForFunction(() => !document.querySelector('[aria-label="Keyframe"] :is([data-problem], [data-slot="field-error"])'));
  step(label, 'curve: bezier(.5,0,1,1) -> field problem with the hint; bezier(0.5,0,1,1) -> accepted');

  // A shorter length asks before dropping keys.
  const length = page.getByLabel('Length in ticks');
  await length.fill('12');
  await length.press('Enter');
  const trim = await confirm(page, 'Shorten to 12 ticks?', 'Trim and shorten');
  assert.match(trim, /\d+ keyframes? .*after tick 12 are dropped/);
  await page.locator('[data-tick]').getByText('/ 12 ticks').waitFor();
  step(label, 'length 20 -> 12: asks with the counts, Trim and shorten -> 12 ticks');
  await shot(page, label, 'timeline');

  // The stage orbits from the keyboard.
  const orbit = await frames(page);
  await page.locator('.animus-mc-stage').focus();
  await page.keyboard.press('ArrowLeft');
  await page.keyboard.press('Shift+ArrowUp');
  await page.keyboard.press('+');
  await page.waitForFunction(n => Number(document.querySelector('.animus-mc-gl-host').getAttribute('data-frames')) > n, orbit);
  await page.keyboard.press('0');
  step(label, 'stage: focus + ArrowLeft, Shift+ArrowUp, + -> new frames; 0 resets');

  // Play and pause.
  const playFrames = await frames(page);
  const playback = page.locator('[role="toolbar"][aria-label="Playback"]');
  await playback.getByRole('button', {name: 'Play'}).click();
  await page.waitForFunction(n => Number(document.querySelector('.animus-mc-gl-host').getAttribute('data-frames')) > n + 5, playFrames);
  await playback.getByRole('button', {name: 'Pause'}).click();
  step(label, 'Play -> frames advance, Pause -> stops');

  // Server files: the rig, its animations and pack sources as a zip.
  const pack = page.waitForEvent('download');
  await toolbarAction(page, 'Download server files');
  const packFile = await (await pack).path();
  assert.equal((await pack).suggestedFilename(), 'town-golem-server-files.zip');
  const packBytes = fs.readFileSync(packFile);
  assert.ok(packBytes.includes('rigs/town/golem.json') && packBytes.includes('animations/town/golem/walk.json'));
  assert.ok(packBytes.includes('"right-arm"'), 'the rename is in the export');
  await page.getByText('/animus pack build').first().waitFor();
  step(label, 'Download server files -> zip with rig and animations, toast names /animus pack build');

  if (phone) {
    // A vertical swipe over the stage scrolls the page instead of orbiting.
    await page.evaluate(() => document.querySelector('.ui-page').scrollTo(0, 0));
    const stage = page.locator('.animus-mc-stage');
    await stage.scrollIntoViewIfNeeded();
    const box = await stage.boundingBox();
    const start = await page.evaluate(() => document.querySelector('.ui-page').scrollTop);
    const cdp = await context.newCDPSession(page);
    const x = box.x + box.width / 2, y = box.y + box.height * 0.7;
    await cdp.send('Input.dispatchTouchEvent', {type: 'touchStart', touchPoints: [{x, y}]});
    for (let i = 1; i <= 8; i++) await cdp.send('Input.dispatchTouchEvent', {type: 'touchMove', touchPoints: [{x, y: y - i * 30}]});
    await cdp.send('Input.dispatchTouchEvent', {type: 'touchEnd', touchPoints: []});
    await page.waitForFunction(s => document.querySelector('.ui-page').scrollTop > s, start);
    step(label, 'touch: vertical swipe over the stage -> page scrolls');
    await noOverflow(page, label);
  }

  // Import a Blockbench model: a new rig drawn with its own texture.
  const importer = page.waitForEvent('filechooser');
  await toolbarAction(page, 'Import .bbmodel');
  await (await importer).setFiles(bbmodel);
  await page.locator('.animus-rig-editor[data-rig="town:robot"]').waitFor();
  await drawn(page);
  assert.equal(await page.locator('.animus-rig-bone-dot').count(), 5);
  assert.ok(await colours(page, await page.locator('.animus-mc-stage').screenshot()) > 40, 'robot stage looks blank');
  step(label, 'Import .bbmodel -> town:robot drawn with its own texture');

  const exported = page.waitForEvent('download');
  await topbarAction(page, 'Export the data folder zip');
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

/** Clicks [name] in the toolbar [row], through its "..." once folded. */
async function toolbarButton(page, row, name) {
  const toolbar = page.locator(`[role="toolbar"][aria-label="${row}"]`);
  const button = toolbar.getByRole('button', {name});
  if (await button.isVisible()) return button.click();
  await toolbar.locator('[id$="-more-button"]').click();
  await page.getByRole('menuitem', {name}).click();
}

/** The other theme at the same width: an animation document opens on the timeline, draws, no overflow, no errors. */
async function glance(browser, viewport, label, dark) {
  const context = await browser.newContext({viewport, hasTouch: viewport.width < 600});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  watch(page, errors);
  const {base, workspace} = await openFolder(page);
  await theme(page, dark);
  await page.goto(`${base}#${workspace}/animation/${encodeURIComponent('town:golem/walk')}`);
  await page.locator('.animus-rig-editor').waitFor();
  if (viewport.width < 1100) assert.equal(await page.locator('.animus-rig-layout').getAttribute('data-pane'), 'timeline', 'an animation opens on the timeline');
  await page.locator('.animus-rig-lane[data-track="0"]').waitFor();
  assert.equal(await valueOf(clipPicker(page)), 'town:golem/walk');
  await drawn(page);
  await frameChecks(page, label);
  await noOverflow(page, label);
  await shot(page, label, 'glance');
  assert.deepEqual(errors, [], `${label}: console errors`);
  step(label, 'open the walk animation -> timeline tab, stage draws, frame checks, no overflow, no console errors');
  await context.close();
}

/** The model pack's loading, missing and retry states, each in a fresh page. */
async function packStates(browser) {
  const viewport = {width: 1440, height: 900};
  // Slow pack: a skeleton fills the stage.
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
    await page.locator('.animus-mc-stage[data-pack="loading"] .animus-mc-state').waitFor();
    await shot(page, 'pack', 'loading');
    release();
    await drawn(page);
    assert.equal(await page.locator('.animus-mc-state').count(), 0);
    assert.deepEqual(errors, []);
    step('pack', 'held models.json -> skeleton in the stage, released -> drawn');
    await context.close();
  }
  // No pack on the site: an alert says so.
  {
    const context = await browser.newContext({viewport});
    const page = await context.newPage();
    const errors = [];
    watch(page, errors, [/404/]);
    await page.route('**/assets/mc/*/pack.json', route => route.fulfill({status: 404, body: 'not found'}));
    const {base, workspace} = await openFolder(page);
    await page.goto(`${base}#${workspace}/rig/${encodeURIComponent('town:golem')}`);
    await page.locator('.animus-mc-state.is-problem').getByText('The 3D models are missing from this site.').waitFor();
    await shot(page, 'pack', 'missing');
    assert.deepEqual(errors, []);
    step('pack', 'pack.json 404 -> "The 3D models are missing from this site."');
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
    const problem = page.locator('.animus-mc-state.is-problem');
    await problem.getByText('The 3D models did not download.').waitFor();
    await shot(page, 'pack', 'network');
    await page.unroute('**/assets/mc/*/pack.json');
    await problem.getByRole('button', {name: 'Retry'}).click();
    await drawn(page);
    assert.equal(await page.locator('.animus-mc-state.is-problem').count(), 0);
    assert.deepEqual(errors, []);
    step('pack', 'pack.json aborted -> "did not download" with Retry; Retry -> drawn');
    await context.close();
  }
}

(async () => {
  const browser = await chromium.launch({headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
  try {
    await run(browser, {width: 1440, height: 900}, 'desktop-dark', true);
    await run(browser, {width: 390, height: 844}, 'phone-light', false);
    await glance(browser, {width: 1600, height: 1000}, 'wide-light', false);
    await glance(browser, {width: 768, height: 1024}, 'tablet-dark', true);
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
