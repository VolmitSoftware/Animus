// The info control (lib/components/info_tip.dart) on an NPC of the example
// packs, at 1440 px with a mouse and 390 px with touch, in both themes: hover
// and keyboard focus show the tooltip named by aria-describedby, Escape hides
// it, a click opens the wiki heading, and a tap toggles the tooltip with its
// Docs link. The control is square and flat, and nothing scrolls sideways.
// Requests to the wiki are answered locally; nothing leaves the machine.
//
//   python3 tool/serve_site.py --port <port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/info_tip_browser.cjs
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const folderDocs = 'https://docs.volmitsoftware.com/animus/04-npc-definitions#definition-file';

async function noSideways(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 0, `${where} scrolls sideways by ${overflow}px`);
}

/** The tooltip the control names, and whether it is shown. */
async function tooltip(page, button) {
  const id = await button.getAttribute('aria-describedby');
  assert.ok(id, 'the control names its tooltip');
  const tip = page.locator(`[id="${id}"]`);
  assert.equal(await tip.getAttribute('role'), 'tooltip');
  return tip;
}

async function run(browser, {viewport, touch, theme}) {
  const label = `${viewport.width}px ${theme}`;
  const context = await browser.newContext({viewport, hasTouch: touch, isMobile: touch, colorScheme: theme});
  await context.route('https://docs.volmitsoftware.com/**', route => route.fulfill({status: 200, contentType: 'text/html', body: '<title>docs</title>'}));
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });

  await page.goto(url);
  await page.getByRole('button', {name: 'Create with example packs'}).click();
  await page.waitForFunction(() => location.hash.includes('/workspace/'));
  const workspace = await page.evaluate(() => location.hash.split('/')[2]);
  await page.goto(`${url}/#/workspace/${workspace}/npc/${encodeURIComponent('examples/bodyguard')}`);

  const button = page.getByRole('link', {name: 'Folder docs', exact: true});
  await button.waitFor();
  assert.equal(await button.textContent(), 'i');
  assert.equal(await button.getAttribute('href'), folderDocs);
  assert.equal(await button.getAttribute('target'), '_blank');
  const tip = await tooltip(page, button);
  assert.equal(await tip.isVisible(), false, `${label}: the tooltip starts hidden`);
  const text = (await tip.textContent()).trim();
  assert.ok(text.length > 0 && text.split(/(?<=[.!?])\s/).length <= 2, `${label}: one or two sentences, got "${text}"`);

  // Square and flat, in the button colours.
  const shape = await button.evaluate(el => {
    const style = getComputedStyle(el);
    const box = el.getBoundingClientRect();
    return {radius: style.borderRadius, shadow: style.boxShadow, width: Math.round(box.width), height: Math.round(box.height)};
  });
  assert.equal(shape.radius, '0px');
  assert.equal(shape.shadow, 'none');
  assert.equal(shape.width, shape.height, `${label}: the control is square`);
  if (touch) assert.ok(shape.height >= 44, `${label}: a 44 px touch target, got ${shape.height}`);

  if (!touch) {
    // Hover shows it; leaving hides it.
    await button.hover();
    await tip.waitFor({state: 'visible'});
    await noSideways(page, `${label} hovered tip`);
    await page.mouse.move(0, 0);
    await tip.waitFor({state: 'hidden'});

    // Keyboard focus shows it with a visible ring; Escape hides it.
    await button.focus();
    await page.keyboard.press('Shift+Tab');
    await page.keyboard.press('Tab');
    assert.equal(await button.evaluate(el => el === document.activeElement), true, `${label}: the control takes focus`);
    await tip.waitFor({state: 'visible'});
    const ring = await button.evaluate(el => getComputedStyle(el).outlineStyle);
    assert.notEqual(ring, 'none', `${label}: visible focus`);
    await page.keyboard.press('Escape');
    await tip.waitFor({state: 'hidden'});

    // A click opens the wiki heading in a new tab.
    const [docs] = await Promise.all([context.waitForEvent('page'), button.click()]);
    await docs.waitForLoadState();
    assert.equal(docs.url(), folderDocs);
    await docs.close();
  } else {
    // A tap toggles the tooltip, which then carries the wiki link.
    await button.tap();
    await tip.waitFor({state: 'visible'});
    assert.equal(context.pages().length, 1, `${label}: a tap does not leave the page`);
    const docsLink = page.locator('.animus-info.is-open .animus-info-docs');
    await docsLink.waitFor();
    assert.equal(await docsLink.getAttribute('href'), folderDocs);
    const inside = await tip.evaluate(el => {
      const box = el.parentElement.getBoundingClientRect();
      return box.left >= 0 && box.right <= document.documentElement.clientWidth;
    });
    assert.ok(inside, `${label}: the tooltip stays on screen`);
    await noSideways(page, `${label} tapped tip`);
    await button.tap();
    await tip.waitFor({state: 'hidden'});

    // A tap elsewhere closes an open tip.
    await button.tap();
    await tip.waitFor({state: 'visible'});
    await page.locator('#animus-main-heading').tap();
    await tip.waitFor({state: 'hidden'});
  }

  // Every info control on the page links into the Animus wiki and names a tooltip.
  const all = await page.locator('.animus-info-button').evaluateAll(links => links.map(link => ({
    href: link.getAttribute('href'),
    tip: document.getElementById(link.getAttribute('aria-describedby') || '')?.textContent || '',
    name: link.getAttribute('aria-label') || '',
  })));
  assert.ok(all.length > 0);
  for (const info of all) {
    assert.match(info.href, /^https:\/\/docs\.volmitsoftware\.com\/animus\/\d\d-[a-z0-9-]+(#[a-z0-9-]+)?$/, `${label}: ${info.name} links into the wiki`);
    assert.ok(info.tip.trim().length > 0, `${label}: ${info.name} has tooltip text`);
    assert.match(info.name, / docs$/, `${label}: ${info.name} is named`);
  }

  await noSideways(page, label);
  assert.deepEqual(errors, [], `${label}: console errors`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch();
  try {
    for (const theme of ['dark', 'light']) {
      await run(browser, {viewport: {width: 1440, height: 900}, touch: false, theme});
      await run(browser, {viewport: {width: 390, height: 844}, touch: true, theme});
    }
    console.log('info_tip_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exit(1);
});
