// Field feedback. The mouse wheel over a focused number box scrolls the
// page and leaves the value alone, and one press on a spin button steps
// once. A field's problem waits while its box is being typed in: it shows
// once typing pauses or focus leaves. Dropdowns and the command palette
// open opaque, with no fade. A capability switch focuses its first
// required argument and holds that problem, in the header too, until focus
// leaves the step; a conversation line added with Enter is pending until it
// is left empty. Tab passing a help tip does not open it, Space does, and a
// label click keeps it shut while the pointer stays. A toast lets clicks through to what is under
// it except on its own button. 1440 px and 390 px, both themes, no console
// errors. Needs no fake server.
//
//   python3 tool/serve_site.py --port <port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/field_feedback_browser.cjs
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {choose} = require('./support/select.cjs');

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

/** Header diagnostics whose JSON path (the item's title) or text matches [pattern]. */
async function headerProblems(page, pattern) {
  return (await page.locator('.animus-diagnostics li').evaluateAll(items => items.map(li => `${li.title} ${li.textContent}`))).filter(text => pattern.test(text));
}

/** Pending fields, help tips and toasts in the starter workspace. */
async function quietFeedback(page, label) {
  await page.goto(url);
  await page.getByLabel('Workspace name').fill(`Quiet ${label}`);
  await page.getByRole('button', {name: 'Create with example packs'}).click();
  await page.getByRole('heading', {name: 'Try it in the browser'}).waitFor();
  const workspace = await page.evaluate(() => location.hash.split('/')[2]);

  // A capability switch: the new required argument is focused and pending.
  await page.goto(`${url}/#/workspace/${workspace}/behavior/${encodeURIComponent('greeter:greeter')}?view=visual`);
  const capability = page.locator('.animus-capability').first().locator('button[role="combobox"]').first();
  await capability.scrollIntoViewIfNeeded();
  await choose(capability, 'animus:give-item');
  const block = page.locator('.animus-capability', {has: page.locator('[data-argument="item"]')}).first();
  await block.locator('[data-argument="item"]').waitFor();
  await page.waitForFunction(() => document.activeElement && document.activeElement.closest('[data-argument="item"]'), null, {timeout: 2000});
  await page.waitForTimeout(400);
  assert.deepEqual(await headerProblems(page, /arguments\.item/), [], `${label}: no header problem while Item waits to be filled`);
  assert.equal(await block.locator('.animus-gui-problem').count(), 0, `${label}: no problem at the step while Item waits`);
  await page.locator('h1').first().click();
  await page.waitForFunction(() => [...document.querySelectorAll('.animus-diagnostics li')].some(li => /arguments\.item/.test(li.title)), null, {timeout: 2000});
  assert.ok((await block.locator('.animus-gui-problem').allInnerTexts()).some(text => /item/i.test(text)), `${label}: Item left empty shows its problem`);

  // A conversation line added with Enter is pending until it is left empty.
  await page.goto(`${url}/#/workspace/${workspace}/new/conversation`);
  await page.getByRole('button', {name: 'Create behavior'}).click();
  await page.waitForFunction(() => location.hash.includes('view=conversation'));
  const first = page.locator('[data-conv-input]').first();
  await first.waitFor();
  await first.click();
  await page.keyboard.press('End');
  const lines = await page.locator('[data-conv-input]').count();
  await page.keyboard.press('Enter');
  await page.waitForFunction(n => document.querySelectorAll('[data-conv-input]').length === n + 1 && document.activeElement && document.activeElement.value === '', lines);
  await page.waitForTimeout(400);
  assert.deepEqual(await headerProblems(page, /at least 1 characters/), [], `${label}: the new empty line is not an error while focused`);
  assert.equal(await page.locator('.animus-conv-step.has-error').count(), 0, `${label}: no step error while the line waits`);
  await page.locator('h1').first().click();
  await page.locator('.animus-conv-step.has-error').first().waitFor({timeout: 2000});

  // Help tips: Tab passes through without opening; Space opens; a label
  // click keeps it shut and focuses the control.
  // The conversation's Start label carries one.
  const tip = page.locator('label[for="conv-start"] .animus-tip-text');
  await tip.scrollIntoViewIfNeeded();
  await tip.evaluate(el => {
    const all = [...document.querySelectorAll('input, button, [tabindex="0"], textarea, a[href]')].filter(e => !e.disabled && e.offsetParent !== null);
    all[all.indexOf(el) - 1].focus();
  });
  await page.keyboard.press('Tab');
  assert.equal(await tip.evaluate(el => el === document.activeElement), true, `${label}: Tab reaches the tip text`);
  const pop = tip.locator('xpath=..').locator('.animus-tip-pop');
  await page.waitForTimeout(300);
  assert.equal(await pop.evaluate(el => getComputedStyle(el).display), 'none', `${label}: Tab passing through does not open the tip`);
  await page.keyboard.press('Space');
  await page.waitForFunction(el => getComputedStyle(el).display !== 'none', await pop.elementHandle());
  await page.keyboard.press('Escape');
  await page.waitForFunction(el => getComputedStyle(el).display === 'none', await pop.elementHandle());
  if (!compactWidth(page)) {
    await tip.click();
    await page.waitForTimeout(500);
    assert.equal(await pop.evaluate(el => getComputedStyle(el).display), 'none', `${label}: a label click keeps the tip shut`);
    // The click opened the Start picker through its label; close it.
    await page.keyboard.press('Escape');
  }

  // A toast lets clicks through except on its own button.
  await page.locator('h1').first().click();
  await page.keyboard.press(`${modKey}+s`);
  const toast = page.locator('.arcane-toast').first();
  await toast.waitFor();
  const hit = await toast.evaluate(el => {
    const box = el.querySelector('.arcane-toast-content, .arcane-toast-message').getBoundingClientRect();
    const under = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
    const close = el.querySelector('button');
    const closeBox = close.getBoundingClientRect();
    return {body: el.contains(under), close: close.contains(document.elementFromPoint(closeBox.left + closeBox.width / 2, closeBox.top + closeBox.height / 2))};
  });
  assert.deepEqual(hit, {body: false, close: true}, `${label}: the toast body passes clicks through, its button does not`);
}

function compactWidth(page) {
  return page.viewportSize().width <= 760;
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

  await quietFeedback(page, label);

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
