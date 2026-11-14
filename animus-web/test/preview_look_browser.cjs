// Opens test/fixtures/preview_look in the built site and checks what the
// Preview stage draws from an NPC's effective definition: a body made
// glowing by its trait group gets an outline in the group's team colour (a
// body without one gets none), and the name over a body at scale 16 stays
// inside the stage with the framing margin even where the stage draws it at
// its minimum font size. Desktop dark and phone light, no console errors.
//
//   python3 tool/serve_site.py --port <port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_SCREENSHOTS=<folder> node test/preview_look_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {topbarAction, workspaceOpen} = require('./support/shell.cjs');
const {showPreview, stageDrawn} = require('./support/stage.cjs');

const folder = path.resolve(__dirname, 'fixtures/preview_look');
const site = process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })();
const shots = process.env.ANIMUS_SCREENSHOTS;

// Pixels of the stage within a few steps of #FF55FF (light_purple), counted in the page.
async function purple(page) {
  const png = await page.locator('.animus-mc-stage').screenshot();
  if (shots) {
    fs.mkdirSync(shots, {recursive: true});
    fs.writeFileSync(path.join(shots, `look-${Date.now()}.png`), png);
  }
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
    let count = 0;
    for (let i = 0; i < data.length; i += 4) {
      if (Math.abs(data[i] - 255) < 8 && Math.abs(data[i + 1] - 85) < 8 && Math.abs(data[i + 2] - 255) < 8) count++;
    }
    return count;
  }, png.toString('base64'));
}

async function open(page, workspace, id) {
  await page.goto(`${site}/#${workspace}/npc/${encodeURIComponent(id)}`);
  await showPreview(page);
  await stageDrawn(page);
  // A frame after the stage settles its size.
  const frames = Number(await page.locator('.animus-mc-gl-host').getAttribute('data-frames'));
  await page.locator('.animus-mc-stage').hover();
  await page.mouse.move(0, 0);
  await page.waitForTimeout(300);
  return frames;
}

async function run(browser, viewport, dark) {
  const page = await browser.newPage({viewport});
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(site);
  await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(folder);
  await workspaceOpen(page);
  const isDark = await page.evaluate(() => document.documentElement.classList.contains('dark'));
  if (isDark !== dark) await topbarAction(page, dark ? 'Use dark theme' : 'Use light theme');
  const workspace = page.url().split('#')[1].split('?')[0];

  // Glowing comes from the harbor-crew group, not the Ferryman's own file.
  await open(page, workspace, 'harbor/ferryman');
  const outlined = await purple(page);
  assert.ok(outlined > 150, `ferryman: ${outlined} outline pixels in the team colour`);
  await open(page, workspace, 'harbor/dockhand');
  const plain = await purple(page);
  assert.ok(plain < 20, `dockhand: ${plain} team-colour pixels without glowing`);

  // The name over a scale 16 body clears the top of the stage.
  await open(page, workspace, 'harbor/giant');
  const gap = await page.evaluate(() => {
    const stage = document.querySelector('.animus-mc-stage').getBoundingClientRect();
    const plate = document.querySelector('.animus-mc-text.is-nameplate').getBoundingClientRect();
    return {top: plate.top - stage.top, bottom: stage.bottom - plate.bottom, height: stage.height};
  });
  assert.ok(gap.top >= gap.height * 0.06, `giant: the name is ${gap.top.toFixed(1)}px from the top of a ${gap.height}px stage`);
  assert.ok(gap.bottom > 0, 'giant: the name is inside the stage');

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  assert.ok(overflow <= 0, `${overflow}px of horizontal overflow`);
  assert.deepEqual(errors, []);
  await page.close();
}

(async () => {
  const browser = await chromium.launch({headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
  try {
    await run(browser, {width: 1440, height: 900}, true);
    await run(browser, {width: 390, height: 844}, false);
    console.log('preview_look_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
