// Opens test/fixtures/rig_editor as a data folder in the built site and runs
// the rig editor at desktop and phone widths: the golem rig draws, a bone is
// moved with the gizmo, an animation is keyed, scrubbed and played, undo
// works, the server files and the workspace export as zips, and a Blockbench
// model imports and draws with its own texture. No console errors. Needs the
// asset pack (tool/extract_minecraft_world_assets.dart) in the build.
//
//   python3 -m http.server 8098 --bind 127.0.0.1 --directory build/jaspr
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_SCREENSHOTS=/tmp/shots node test/rig_editor_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const folder = path.resolve(__dirname, 'fixtures/rig_editor');
const bbmodel = path.resolve(__dirname, 'fixtures/bbmodel/robot.bbmodel');
const shots = process.env.ANIMUS_SCREENSHOTS;

async function colours(page, png) {
  return page.evaluate(async base64 => {
    const image = new Image();
    image.src = `data:image/png;base64,${base64}`;
    await image.decode();
    const canvas = document.createElement('canvas');
    canvas.width = image.naturalWidth;
    canvas.height = image.naturalHeight;
    const context = canvas.getContext('2d');
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
  fs.writeFileSync(path.join(shots, `${label}-${name}.png`), png);
}

async function run(browser, viewport, label) {
  const context = await browser.newContext({viewport, acceptDownloads: true});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })());
  await page.getByRole('heading', {name: 'Open an Animus data folder'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(folder);
  await page.locator('.animus-sidebar').getByText('town:golem', {exact: true}).waitFor();
  const base = page.url().split('#')[0];
  const workspace = page.url().split('#')[1].split('?')[0];

  // Open the fixture rig: the stage draws its item, block and text bones.
  await page.goto(`${base}#${workspace}/rig/${encodeURIComponent('town:golem')}`);
  await page.locator('.animus-rig-editor[data-rig="town:golem"]').waitFor();
  await drawn(page);
  assert.equal(await page.locator('.animus-mc-stage').getAttribute('data-webgl'), 'ready');
  assert.equal(await page.locator('.animus-rig-bone-dot').count(), 5, 'one dot per bone');
  assert.ok((await page.locator('.animus-mc-text-line').allTextContents()).includes('Golem'), 'text bone');
  const stagePng = await page.locator('.animus-mc-stage').screenshot();
  assert.ok(await colours(page, stagePng) > 40, 'stage looks blank');
  await shot(page, label, 'rig-open', page.locator('.animus-mc-stage'));

  // Move the torso with the Y handle: its pivot rises, with the subtree.
  await page.locator('.animus-rig-bone[data-bone="torso"]').click();
  await page.getByRole('button', {name: 'Move', exact: true}).click();
  const pivotY = page.getByLabel('Pivot Y');
  assert.equal(await pivotY.inputValue(), '1.1');
  await drag(page, page.locator('[data-gizmo="handle"][data-axis="y"]'), 0, -40);
  await page.waitForFunction(() => document.querySelector('[aria-label="Pivot Y"]').value !== '1.1');
  const moved = Number(await pivotY.inputValue());
  assert.ok(moved > 1.1, `pivot rose: ${moved}`);
  await page.locator('.animus-rig-bone[data-bone="head"]').click();
  assert.ok(Number(await page.getByLabel('Pivot Y').inputValue()) > 1.6, 'the head moved with the torso');
  await shot(page, label, 'bone-moved');

  // Undo puts the pivot back; redo the move by dragging again.
  await page.getByRole('button', {name: 'Undo'}).click();
  await page.locator('.animus-rig-bone[data-bone="torso"]').click();
  await page.waitForFunction(() => document.querySelector('[aria-label="Pivot Y"]').value === '1.1');
  await drag(page, page.locator('[data-gizmo="handle"][data-axis="y"]'), 0, -40);
  await page.waitForFunction(() => document.querySelector('[aria-label="Pivot Y"]').value !== '1.1');

  // Rename a bone through the tree.
  await page.locator('.animus-rig-bone[data-bone="arm"]').click();
  await page.getByLabel('Bone name').fill('right-arm');
  await page.locator('.animus-rig-tree').getByRole('button', {name: 'Rename'}).click();
  await page.locator('.animus-rig-bone[data-bone="right-arm"]').waitFor();

  // Pick the walk animation, scrub, add a keyframe, key with the gizmo.
  await page.locator('.animus-rig-timeline select').first().selectOption('town:golem/walk');
  await page.locator('.animus-rig-lane').first().waitFor();
  const keys = await page.locator('.animus-rig-key').count();
  await page.locator('.animus-rig-scrubber input[type="range"]').fill('7.5');
  await page.locator('.animus-rig-tick').getByText('tick 7.5 / 20').waitFor();
  await page.locator('.animus-rig-bone[data-bone="head"]').click();
  await page.getByRole('button', {name: 'Add keyframe'}).click();
  await page.waitForFunction(n => document.querySelectorAll('.animus-rig-key').length > n, keys);
  const keyed = await page.locator('.animus-rig-key').count();
  await page.getByRole('button', {name: 'Rotate', exact: true}).click();
  await page.locator('.animus-rig-scrubber input[type="range"]').fill('15');
  await drag(page, page.locator('[data-gizmo="handle"][data-axis="x"]'), 30, 30);
  await page.waitForFunction(n => document.querySelectorAll('.animus-rig-key').length > n, keyed);
  // Select a keyframe and give it an easing.
  await page.locator('.animus-rig-key').last().click();
  await page.locator('.animus-rig-keyframe select').first().selectOption('back_out');
  await page.locator('.animus-rig-keyframe select').first().evaluate(select => select.value === 'back_out');
  await shot(page, label, 'keyframes');

  // Play: the playhead moves and the stage draws new frames.
  const before = await frames(page);
  await page.getByRole('button', {name: 'Play'}).click();
  await page.waitForFunction(n => Number(document.querySelector('.animus-mc-gl-host').getAttribute('data-frames')) > n + 5, before);
  await page.getByRole('button', {name: 'Pause'}).click();
  await shot(page, label, 'playing', page.locator('.animus-mc-stage'));

  // Server files: the rig, its animations and pack sources as a zip.
  const pack = page.waitForEvent('download');
  await page.getByRole('button', {name: 'Build resource pack'}).click();
  const packFile = await (await pack).path();
  assert.equal((await pack).suggestedFilename(), 'town-golem-server-files.zip');
  const packBytes = fs.readFileSync(packFile);
  assert.ok(packBytes.includes('rigs/town/golem.json') && packBytes.includes('animations/town/golem/walk.json'));
  assert.ok(packBytes.includes('"right-arm"'), 'the rename is in the export');

  // Import a Blockbench model: a new rig drawn with its own texture.
  const importer = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Import .bbmodel'}).first().click();
  await (await importer).setFiles(bbmodel);
  await page.locator('.animus-rig-editor[data-rig="town:robot"]').waitFor();
  await drawn(page);
  assert.equal(await page.locator('.animus-rig-bone-dot').count(), 5);
  const robotPng = await page.locator('.animus-mc-stage').screenshot();
  assert.ok(await colours(page, robotPng) > 40, 'robot stage looks blank');
  await shot(page, label, 'bbmodel-import', page.locator('.animus-mc-stage'));
  await page.locator('.animus-rig-timeline select').first().selectOption('town:robot/walk');
  await page.locator('.animus-rig-scrubber input[type="range"]').fill('10');
  await drawn(page);
  await shot(page, label, 'bbmodel-walk');

  // The workspace as data-folder files, with the imported pack sources.
  const exported = page.waitForEvent('download');
  await page.getByRole('button', {name: 'Export zip'}).click();
  const zip = fs.readFileSync(await (await exported).path());
  for (const entry of ['rigs/town/robot.json', 'animations/town/robot/walk.json', 'pack/assets/animus/models/item/robot/head.json', 'pack/assets/animus/textures/item/robot/robot.png']) {
    assert.ok(zip.includes(entry), `export holds ${entry}`);
  }
  await shot(page, label, 'final');

  assert.deepEqual(errors, [], `${label}: console errors`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch();
  try {
    await run(browser, {width: 1440, height: 1000}, 'desktop');
    await run(browser, {width: 390, height: 844}, 'phone');
    console.log('rig editor browser check passed');
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exit(1);
});
