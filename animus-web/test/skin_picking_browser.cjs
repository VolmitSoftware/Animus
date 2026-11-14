// Picking skins on an NPC's Basics tab, at 1440x900 dark and 390x844
// light. Writes its own data folder (no fake server): three unsigned skins
// with their PNGs kept (solid red classic, solid blue slim, solid magenta
// classic) and two player NPCs, one wearing the red skin. Then, on Anna:
//   1. Change lists the saved skins with each skin's own PNG face;
//   2. picking blue, then magenta, then red writes `skin` each time, the
//      stage draws that colour (and not the last one; desktop, where the
//      preview sits beside the form), and the field's face follows;
//   3. the Arms switch (Look, Skin layers, Advanced) writes a slim copy;
//   4. a built-in skin (Guard) makes a skin document labelled Guard and
//      wears it; the built-in faces are served PNGs;
//   5. a PNG address (Advanced) and Upload PNG both make and wear a new
//      skin, and the stage draws the PNG;
//   6. a reload keeps the stored skin and the stage still draws it.
// Bert (no skin) reads Default. No console errors, no sideways scrolling.
//
//   python3 tool/serve_site.py --port <WEB_PORT> --directory <site> &
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<WEB_PORT> ANIMUS_SCREENSHOTS=<scratch folder> \
//     node test/skin_picking_browser.cjs
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const zlib = require('node:zlib');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {showView, setMode} = require('./support/shell.cjs');
const {npcTab} = require('./support/npc_tabs.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;

/** A skin document the way the editor writes one: its id is the SHA-256 of the text. */
function skinDocument(slim, profile) {
  const raw = Buffer.from(JSON.stringify({profileId: profile, profileName: 'animus-editor', textures: {SKIN: slim ? {metadata: {model: 'slim'}} : {}}})).toString('base64');
  const text = `${JSON.stringify({textureRaw: raw, signature: null}, null, 2)}\n`;
  return {id: crypto.createHash('sha256').update(text).digest('hex'), text};
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

const RED = [210, 30, 30];
const BLUE = [30, 50, 220];
const MAGENTA = [200, 30, 200];
const red = skinDocument(false, 'r'.repeat(32));
const blue = skinDocument(true, 'b'.repeat(32));
const magenta = skinDocument(false, 'm'.repeat(32));

function npc(id, name, extra) {
  return {
    schemaVersion: 1, revision: 1, uuid: crypto.randomUUID(), id, group: 'town', name, type: 'minecraft:player', spawned: false,
    position: {world: 'world', x: 0.5, y: 64, z: 0.5, yaw: 0, pitch: 0}, ...extra,
  };
}

function writeFixture(folder) {
  const write = (file, value) => {
    fs.mkdirSync(path.dirname(path.join(folder, file)), {recursive: true});
    fs.writeFileSync(path.join(folder, file), typeof value === 'string' || Buffer.isBuffer(value) ? value : `${JSON.stringify(value, null, 2)}\n`);
  };
  for (const [skin, rgb] of [[red, RED], [blue, BLUE], [magenta, MAGENTA]]) {
    write(`skins/${skin.id}.json`, skin.text);
    write(`editor/skins/${skin.id}.png`, png(rgb));
  }
  write('npcs/town/anna.json', npc('anna', 'Anna', {skin: red.id}));
  write('npcs/town/bert.json', npc('bert', 'Bert', {}));
}

async function shot(page, name) {
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `skin-picking-${name}.png`)});
}

async function codeOf(page) {
  await showView(page, 'code');
  const text = await page.locator('.animus-code textarea').inputValue();
  await showView(page, 'visual');
  return JSON.parse(text);
}

/** The skin field on Basics. */
async function skinField(page) {
  const field = (await npcTab(page, 'Basics')).locator('[data-skin="field"]');
  await field.scrollIntoViewIfNeeded();
  return field;
}

/** Opens Change and returns the chooser dialog. */
async function chooseSkin(page) {
  await (await skinField(page)).locator('[data-skin="change"]').click();
  const dialog = page.getByRole('dialog', {name: 'Choose a skin'});
  await dialog.waitFor();
  return dialog;
}

async function openNpc(page, workspace, id) {
  await page.evaluate((hash) => { location.hash = hash; }, `#/workspace/${workspace}/npc/${encodeURIComponent(id)}`);
  await page.locator('#animus-main h1').waitFor();
}

/** Waits for the stage to draw a frame after [before] with every texture bound. */
async function drawnAfter(page, before) {
  await page.waitForFunction((n) => {
    const host = document.querySelector('.animus-mc-gl-host');
    return host && Number(host.getAttribute('data-frames')) > n && host.getAttribute('data-unbuilt') === '0';
  }, before);
}

const frames = (page) => page.locator('.animus-mc-gl-host').getAttribute('data-frames').then(Number);

/** How many sampled stage pixels read as each colour. */
async function stageColours(page) {
  const image = await page.locator('.animus-mc-stage').screenshot();
  return page.evaluate(async (base64) => {
    const picture = new Image();
    picture.src = `data:image/png;base64,${base64}`;
    await picture.decode();
    const canvas = document.createElement('canvas');
    canvas.width = picture.naturalWidth;
    canvas.height = picture.naturalHeight;
    const context = canvas.getContext('2d');
    context.drawImage(picture, 0, 0);
    const data = context.getImageData(0, 0, canvas.width, canvas.height).data;
    const counts = {red: 0, blue: 0, magenta: 0};
    for (let i = 0; i < data.length; i += 16) {
      const [r, g, b] = [data[i], data[i + 1], data[i + 2]];
      if (r > 120 && g < 70 && b < 70) counts.red++;
      if (b > 120 && r < 70 && g < 90) counts.blue++;
      if (r > 110 && b > 110 && g < 70) counts.magenta++;
    }
    return counts;
  }, image.toString('base64'));
}

/** Asserts the stage draws [colour] and none of the other skin colours. */
async function stageWears(page, colour, where) {
  // The stage redraws as soon as the texture binds; give it a few frames.
  for (let attempt = 0; attempt < 20; attempt++) {
    const counts = await stageColours(page);
    const others = Object.entries(counts).filter(([name]) => name !== colour).every(([, count]) => count < 20);
    if (counts[colour] > 200 && others) return;
    await page.waitForTimeout(150);
  }
  assert.fail(`${where}: the stage does not draw the ${colour} skin: ${JSON.stringify(await stageColours(page))}`);
}

/** The colour of the face (8..16, 8..16) of the PNG behind a face thumbnail's background. */
async function faceColour(locator) {
  return locator.evaluate(async (element) => {
    const style = element.style.backgroundImage;
    const found = /url\("?(.*?)"?\)/.exec(style);
    if (!found) throw new Error(`no background image on ${element.outerHTML.slice(0, 300)}: ${style}`);
    const url = found[1];
    const picture = new Image();
    picture.src = url;
    await picture.decode();
    const canvas = document.createElement('canvas');
    canvas.width = picture.naturalWidth;
    canvas.height = picture.naturalHeight;
    const context = canvas.getContext('2d');
    context.drawImage(picture, 0, 0);
    return {size: [picture.naturalWidth, picture.naturalHeight], rgb: [...context.getImageData(12, 12, 1, 1).data.slice(0, 3)]};
  });
}

async function run(browser, folder, viewport, theme, tag) {
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
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(folder);
  await page.waitForFunction(() => /#\/workspace\/[^/?]+/.test(location.hash));
  const workspace = /#\/workspace\/([^/?]+)/.exec(page.url())[1];

  // The stage sits beside the form on desktop; on a phone it is behind Preview.
  const staged = viewport.width > 1100;

  // Bert has no skin.
  await openNpc(page, workspace, 'town/bert');
  assert.equal((await (await skinField(page)).locator('[data-skin="name"]').innerText()).trim(), 'Default (Steve or Alex)');

  await openNpc(page, workspace, 'town/anna');
  if (staged) {
    await drawnAfter(page, 0);
    await stageWears(page, 'red', `${tag} opened`);
  }

  // 1. The faces in the chooser are each skin's own face.
  let dialog = await chooseSkin(page);
  for (const [skin, rgb] of [[red, RED], [blue, BLUE], [magenta, MAGENTA]]) {
    const face = await faceColour(dialog.locator(`.ui-library-row[data-key="${skin.id}"] .animus-skin-face`));
    assert.deepEqual(face.size, [64, 64], `${tag}: face of ${skin.id.slice(0, 8)} is a 64x64 skin`);
    assert.deepEqual(face.rgb, rgb, `${tag}: face of ${skin.id.slice(0, 8)}`);
  }
  await shot(page, `${tag}-01-chooser`);
  await page.keyboard.press('Escape');
  await dialog.waitFor({state: 'hidden'});

  // 2. Each pick is stored and drawn, and the field's face follows.
  for (const [skin, colour, rgb] of [[blue, 'blue', BLUE], [magenta, 'magenta', MAGENTA], [red, 'red', RED]]) {
    const before = staged ? await frames(page) : 0;
    dialog = await chooseSkin(page);
    await dialog.locator(`.ui-library-row[data-key="${skin.id}"]`).click();
    await dialog.waitFor({state: 'hidden'});
    if (staged) {
      await drawnAfter(page, before);
      await stageWears(page, colour, `${tag} picked ${colour}`);
    }
    assert.deepEqual((await faceColour((await skinField(page)).locator('.animus-skin-face'))).rgb, rgb, `${tag}: field face for ${colour}`);
    assert.equal((await codeOf(page)).skin, skin.id, `${tag}: ${colour} is stored`);
  }
  await shot(page, `${tag}-02-picked`);

  // 3. Arms (Advanced): a slim copy of the red skin, worn here, then back.
  await setMode(page, 'advanced');
  await openNpc(page, workspace, 'town/anna');
  const arms = async () => {
    const look = await npcTab(page, 'Look');
    const layers = look.locator('[data-npc="skin-layers"] > details');
    if (!(await layers.evaluate((details) => details.open))) await layers.locator(':scope > summary').click();
    return look.getByRole('switch', {name: 'Slim arms (Alex)'});
  };
  await (await arms()).click();
  await page.waitForTimeout(300);
  const slimRed = (await codeOf(page)).skin;
  assert.match(slimRed, /^[a-f0-9]{64}$/);
  assert.notEqual(slimRed, red.id);
  if (staged) await stageWears(page, 'red', `${tag} slim red`);
  await (await arms()).click();
  await page.waitForTimeout(300);
  assert.notEqual((await codeOf(page)).skin, slimRed, `${tag}: classic again`);

  // 4. A built-in skin becomes a labelled skin document and is worn.
  if (staged) await stageWears(page, 'red', `${tag} before the built-in skin`);
  const solid = staged ? await stageColours(page) : null;
  dialog = await chooseSkin(page);
  await dialog.getByText(/^Built-in$/).click();
  const faces = dialog.locator('.ui-library-row .animus-skin-face');
  await faces.first().waitFor();
  assert.equal(await faces.count(), 8);
  for (let index = 0; index < 8; index++) {
    assert.deepEqual((await faceColour(faces.nth(index))).size, [64, 64], `${tag}: built-in faces are served skins`);
  }
  await dialog.locator('.ui-library-row', {hasText: 'Guard'}).click();
  await dialog.waitFor({state: 'hidden'});
  await (await skinField(page)).locator('[data-skin="name"]', {hasText: /^Guard$/}).waitFor();
  if (staged) {
    let counts = await stageColours(page);
    for (let attempt = 0; attempt < 20 && counts.red * 4 > solid.red; attempt++) {
      await page.waitForTimeout(150);
      counts = await stageColours(page);
    }
    assert.ok(counts.red * 4 <= solid.red, `${tag}: the stage dropped the solid red skin: ${JSON.stringify(counts)} after ${JSON.stringify(solid)}`);
  }
  const guardSkin = (await codeOf(page)).skin;
  assert.match(guardSkin, /^[a-f0-9]{64}$/);
  await shot(page, `${tag}-03-built-in`);

  // 5. A PNG address and Upload PNG.
  await page.route('**/editor-test-skin.png', (route) => route.fulfill({status: 200, contentType: 'image/png', body: png(BLUE)}));
  dialog = await chooseSkin(page);
  await dialog.getByLabel('Skin PNG address', {exact: true}).fill(`${site}/editor-test-skin.png`);
  await dialog.getByRole('button', {name: 'Load', exact: true}).click();
  await dialog.waitFor({state: 'hidden'});
  if (staged) await stageWears(page, 'blue', `${tag} from address`);
  const fromAddress = (await codeOf(page)).skin;
  assert.notEqual(fromAddress, guardSkin);
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'animus-skin-picking-'));
  const file = path.join(scratch, 'magenta.png');
  fs.writeFileSync(file, png(MAGENTA));
  dialog = await chooseSkin(page);
  const files = page.waitForEvent('filechooser');
  await dialog.getByRole('button', {name: 'Upload PNG'}).click();
  await (await files).setFiles(file);
  await dialog.waitFor({state: 'hidden'});
  if (staged) await stageWears(page, 'magenta', `${tag} from file`);
  const fromFile = (await codeOf(page)).skin;
  assert.match(fromFile, /^[a-f0-9]{64}$/);
  assert.notEqual(fromFile, fromAddress);
  fs.rmSync(scratch, {recursive: true, force: true});

  // 6. Saved: a reload keeps it and draws it from the kept PNG.
  await page.reload();
  await page.locator('#animus-main h1').waitFor();
  assert.equal((await codeOf(page)).skin, fromFile, `${tag}: kept across a reload`);
  if (staged) {
    await drawnAfter(page, 0);
    await stageWears(page, 'magenta', `${tag} after reload`);
  }
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  assert.ok(overflow <= 1, `${tag}: the page scrolls sideways by ${overflow}px`);
  await shot(page, `${tag}-04-reloaded`);

  assert.deepEqual(errors, [], `${tag}: console errors`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
  try {
    for (const [viewport, theme, tag] of [[{width: 1440, height: 900}, 'dark', 'desktop-dark'], [{width: 390, height: 844}, 'light', 'phone-light']]) {
      const folder = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'animus-skin-picking-')), 'data');
      writeFixture(folder);
      try {
        await run(browser, folder, viewport, theme, tag);
      } finally {
        fs.rmSync(path.dirname(folder), {recursive: true, force: true});
      }
    }
    console.log('skin_picking_browser: ok');
  } finally {
    await browser.close();
  }
})().catch((error) => { console.error(error); process.exit(1); });
