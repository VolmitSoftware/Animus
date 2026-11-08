// Field feedback. The mouse wheel over a focused number box scrolls the
// page and leaves the value alone, and one press on a spin button steps
// once. A field's problem waits while its box is being typed in: it shows
// once typing pauses or focus leaves. Dropdowns and the command palette
// open opaque, with no fade. 1440 px and 390 px, both themes, no console
// errors. Needs no fake server.
//
//   python3 tool/serve_site.py --port <port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/field_feedback_browser.cjs
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const modKey = process.platform === 'darwin' ? 'Meta' : 'Control';

/** Opacity keyframes below 1 on [selector]'s element once it appears after [open]. */
async function fadeOf(page, selector, open) {
  await page.evaluate((sel) => {
    window.__fade = null;
    const seen = () => {
      const el = document.querySelector(sel);
      if (!el) return false;
      const frames = el.getAnimations().flatMap(a => a.effect ? a.effect.getKeyframes() : []);
      window.__fade = frames.filter(f => f.opacity !== undefined && Number(f.opacity) < 1).map(f => f.opacity);
      return true;
    };
    const watch = new MutationObserver(() => { if (seen()) watch.disconnect(); });
    watch.observe(document.body, {childList: true, subtree: true});
  }, selector);
  await open();
  await page.waitForFunction(() => window.__fade !== null);
  return page.evaluate(() => window.__fade);
}

async function run(browser, {viewport, theme}) {
  const label = `${viewport.width}px ${theme}`;
  const compact = viewport.width <= 760;
  const context = await browser.newContext({viewport, colorScheme: theme, hasTouch: false});
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });

  await page.goto(url);
  await page.getByLabel('Workspace name').fill(`Fields ${label}`);
  await page.getByRole('button', {name: 'Create blank workspace'}).click();
  await page.getByRole('heading', {name: 'New document'}).waitFor();
  const workspace = await page.evaluate(() => location.hash.split('/')[2]);

  // Number boxes: the wheel scrolls the page, a spin press steps once.
  await page.goto(`${url}/#/workspace/${workspace}/new/npc`);
  await page.getByRole('heading', {name: 'New NPC'}).waitFor();
  const yaw = page.getByLabel('Yaw', {exact: true});
  await yaw.evaluate(el => el.scrollIntoView({block: 'center'}));
  await yaw.click({position: {x: 12, y: 8}});
  await page.keyboard.press(`${modKey}+a`);
  await page.keyboard.type('90');
  let box = await yaw.boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  const before = await page.evaluate(() => scrollY);
  // Toward whichever end the page has room to scroll to.
  const delta = before > 0 ? -120 : 120;
  for (let i = 0; i < 4; i++) await page.mouse.wheel(0, delta);
  await page.waitForTimeout(400);
  assert.equal(await yaw.inputValue(), '90', `${label}: the wheel leaves Yaw alone`);
  assert.notEqual(await page.evaluate(() => scrollY), before, `${label}: the wheel scrolls the page from ${before}`);
  if (!compact) {
    await yaw.scrollIntoViewIfNeeded();
    await yaw.focus();
    box = await yaw.boundingBox();
    // The spin buttons show on hover; step onto them first.
    await page.mouse.move(box.x + box.width / 2, box.y + box.height * 0.3);
    await page.mouse.move(box.x + box.width - 14, box.y + box.height * 0.3, {steps: 6});
    await page.waitForTimeout(200);
    await page.mouse.down();
    await page.mouse.up();
    await page.waitForTimeout(2000);
    assert.equal(await yaw.inputValue(), '91', `${label}: one spin press steps once, got ${await yaw.inputValue()}`);
  }

  // A field's problem waits for a pause in typing, then stays after blur.
  await page.goto(`${url}/#/workspace/${workspace}/new/shop`);
  await page.getByRole('heading', {name: 'New shop'}).waitFor();
  const id = page.getByLabel('Id', {exact: true});
  const problem = page.locator('.animus-field-error');
  await id.click();
  await page.keyboard.press(`${modKey}+a`);
  await page.keyboard.press('Backspace');
  await page.keyboard.type('ha', {delay: 60});
  await page.waitForTimeout(150);
  assert.equal(await problem.count(), 0, `${label}: no problem while typing`);
  await problem.waitFor({timeout: 2000});
  await page.keyboard.type('rbor', {delay: 60});
  assert.equal(await problem.count(), 0, `${label}: the problem waits again while typing`);
  await page.keyboard.press('Tab');
  await problem.waitFor({timeout: 300});
  assert.match(await problem.textContent(), /namespace:path/);
  await page.waitForTimeout(1200);
  assert.equal(await problem.count(), 1, `${label}: the problem stays after blur`);

  // Popups open opaque.
  await page.goto(`${url}/#/workspace/${workspace}/new/npc`);
  await page.getByRole('heading', {name: 'New NPC'}).waitFor();
  const body = page.getByRole('combobox', {name: 'Body'});
  assert.deepEqual(await fadeOf(page, '.animus-select-popup', () => body.click()), [], `${label}: the dropdown does not fade in`);
  await page.keyboard.press('Escape');
  if (!compact) {
    await page.locator('body').click({position: {x: 1, y: 1}});
    assert.deepEqual(await fadeOf(page, '.animus-palette', () => page.keyboard.press(`${modKey}+k`)), [], `${label}: the palette does not fade in`);
    await page.keyboard.press('Escape');
  }

  assert.deepEqual(errors, [], `${label}: console errors`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch();
  try {
    for (const viewport of [{width: 1440, height: 900}, {width: 390, height: 844}]) {
      for (const theme of ['dark', 'light']) await run(browser, {viewport, theme});
    }
    console.log('field feedback: ok');
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exit(1);
});
