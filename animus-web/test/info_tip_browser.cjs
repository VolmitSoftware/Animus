// Help tips (lib/components/info_tip.dart) on an NPC of the example packs, at
// 1440 px with a mouse and 390 px with touch, in both themes. The label text
// itself carries the tip: a dotted muted underline, no icon or box. Hover
// shows the tooltip named by aria-describedby; Tab onto the text only shows
// the focus ring and Space opens it; Escape hides it, Enter and the
// tooltip's Docs link open the wiki heading, a click on a label still
// focuses its field and opens nothing, and a tap toggles the tooltip. Nothing scrolls
// sideways. Requests to the wiki are answered locally.
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

/** The tooltip the text names. */
async function tooltip(page, text) {
  const id = await text.getAttribute('aria-describedby');
  assert.ok(id, 'the text names its tooltip');
  const tip = page.locator(`[id="${id}"]`);
  assert.equal(await tip.getAttribute('role'), 'tooltip');
  return tip;
}

/** Marks the first tip text inside a label that names a field, opens the
 * sections around it, and returns it. */
async function labelTip(page) {
  const id = await page.evaluate(() => {
    const text = [...document.querySelectorAll('label .animus-tip-text')].find(el => el.closest('label').control?.matches('input:not([type="checkbox"]), textarea, button[role="combobox"]'));
    for (let section = text?.closest('details'); section; section = section.parentElement.closest('details')) section.open = true;
    return text?.closest('label').control.id;
  });
  assert.ok(id, 'a tip on a field label');
  const text = page.locator(`label[for="${id}"] .animus-tip-text`);
  await text.waitFor();
  assert.equal(await text.count(), 1, 'a tip on a field label');
  return text;
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

  const text = page.getByRole('link', {name: 'Folder', exact: true});
  await text.waitFor();
  assert.equal((await text.textContent()).trim(), 'Folder');
  assert.equal(await text.getAttribute('tabindex'), '0');
  const tip = await tooltip(page, text);
  assert.equal(await tip.isVisible(), false, `${label}: the tooltip starts hidden`);
  const words = (await tip.textContent()).trim();
  assert.ok(words.length > 0 && words.split(/(?<=[.!?])\s/).length <= 2, `${label}: one or two sentences, got "${words}"`);
  const docsLink = page.locator(`[id="${await text.getAttribute('aria-describedby')}"] + .animus-tip-docs`);
  assert.equal(await docsLink.getAttribute('href'), folderDocs);
  assert.equal(await docsLink.textContent(), 'Docs');

  // The text itself is the affordance: a dotted muted underline and the help
  // cursor, with no icon, box, bracket or marker beside it.
  const look = await text.evaluate(el => {
    const style = getComputedStyle(el);
    const box = el.getBoundingClientRect();
    const extra = ['::before', '::after'].map(part => getComputedStyle(el, part).content).filter(content => content !== 'none' && content !== 'normal');
    const line = parseFloat(style.lineHeight) || parseFloat(style.fontSize) * 1.2;
    return {
      line: style.textDecorationLine,
      style: style.textDecorationStyle,
      cursor: style.cursor,
      border: style.borderTopWidth,
      background: style.backgroundColor,
      radius: style.borderRadius,
      shadow: style.boxShadow,
      extra,
      height: box.height,
      lineHeight: line,
    };
  });
  assert.equal(look.line, 'underline');
  assert.equal(look.style, 'dotted');
  assert.equal(look.cursor, 'help');
  assert.equal(look.border, '0px');
  assert.equal(look.background, 'rgba(0, 0, 0, 0)');
  assert.equal(look.radius, '0px');
  assert.equal(look.shadow, 'none');
  assert.deepEqual(look.extra, [], `${label}: no marker beside the text`);
  assert.ok(look.height >= look.lineHeight - 0.5, `${label}: the target is a full text line (${look.height} < ${look.lineHeight})`);
  assert.equal(await page.locator('.animus-info, .animus-info-button').count(), 0, `${label}: no icon controls remain`);

  if (!touch) {
    // Hover shows it after a short delay and it stays while the pointer is
    // over it; the Docs link inside it opens the wiki heading.
    await text.hover();
    await tip.waitFor({state: 'visible'});
    await noSideways(page, `${label} hovered tip`);
    await docsLink.hover();
    await page.waitForTimeout(400);
    assert.equal(await tip.isVisible(), true, `${label}: the tooltip stays open under the pointer`);
    const [fromLink] = await Promise.all([context.waitForEvent('page'), docsLink.click()]);
    await fromLink.waitForLoadState();
    assert.equal(fromLink.url(), folderDocs);
    await fromLink.close();
    await page.mouse.move(0, 0);
    await tip.waitFor({state: 'hidden'});

    // A click on a field label's tip text focuses the field and opens nothing,
    // not even under the resting pointer.
    const fieldTip = await labelTip(page);
    await fieldTip.click();
    assert.equal(await fieldTip.evaluate(el => document.activeElement === el.closest('label').control), true, `${label}: the label still focuses its field`);
    assert.equal(context.pages().length, 1, `${label}: a click opens no page`);
    await page.waitForTimeout(500);
    assert.equal(await fieldTip.evaluate(el => getComputedStyle(el.closest('.animus-tip').querySelector('.animus-tip-pop')).display), 'none', `${label}: a label click keeps the tip shut`);
    await page.mouse.move(0, 0);

    // Tab gives it a visible ring and leaves the tooltip shut; Space opens
    // it, Escape hides it, Enter opens the wiki heading.
    await text.focus();
    await page.keyboard.press('Shift+Tab');
    await page.keyboard.press('Tab');
    assert.equal(await text.evaluate(el => el === document.activeElement), true, `${label}: the text takes focus`);
    assert.notEqual(await text.evaluate(el => getComputedStyle(el).outlineStyle), 'none', `${label}: visible focus`);
    await page.waitForTimeout(300);
    assert.equal(await tip.isVisible(), false, `${label}: Tab passing through leaves the tooltip shut`);
    await page.keyboard.press('Space');
    await tip.waitFor({state: 'visible'});
    await page.keyboard.press('Escape');
    await tip.waitFor({state: 'hidden'});
    const [fromKey] = await Promise.all([context.waitForEvent('page'), page.keyboard.press('Enter')]);
    await fromKey.waitForLoadState();
    assert.equal(fromKey.url(), folderDocs);
    await fromKey.close();

    // Focus that lands on the text any other way (a field's blur handing
    // focus back, a script) shows only the ring.
    await page.keyboard.press('Escape');
    await fieldTip.evaluate(el => el.closest('label').control.focus());
    await page.keyboard.press('Shift');
    await text.evaluate(el => el.focus());
    await page.waitForTimeout(500);
    assert.equal(await tip.isVisible(), false, `${label}: focus without Space leaves the tooltip shut`);
    await text.evaluate(el => el.blur());

    // A pointer moving along the text does not open it; resting on it does.
    const box = await text.boundingBox();
    await page.mouse.move(box.x - 30, box.y + box.height / 2);
    await page.evaluate(id => {
      window.__tipShown = false;
      const pop = document.getElementById(id).parentElement;
      const watch = () => {
        if (getComputedStyle(pop).display !== 'none') window.__tipShown = true;
        if (!window.__tipDone) requestAnimationFrame(watch);
      };
      window.__tipDone = false;
      requestAnimationFrame(watch);
    }, await text.getAttribute('aria-describedby'));
    for (let step = 0; step <= 12; step++) {
      await page.mouse.move(box.x + (box.width * step) / 12, box.y + box.height / 2);
      await page.waitForTimeout(40);
    }
    await page.mouse.move(box.x + box.width + 40, box.y + box.height / 2);
    await page.waitForTimeout(300);
    assert.equal(await page.evaluate(() => { window.__tipDone = true; return window.__tipShown; }), false, `${label}: a pointer passing over the text leaves the tooltip shut`);
    await text.hover();
    await tip.waitFor({state: 'visible'});
    await page.mouse.move(0, 0);
    await tip.waitFor({state: 'hidden'});
  } else {
    // A tap toggles the tooltip and its Docs link without leaving the page
    // or raising the keyboard for the field.
    await text.tap();
    await tip.waitFor({state: 'visible'});
    await docsLink.waitFor({state: 'visible'});
    assert.equal(context.pages().length, 1, `${label}: a tap does not leave the page`);
    const inside = await tip.evaluate(el => {
      const box = el.parentElement.getBoundingClientRect();
      return box.left >= 0 && box.right <= document.documentElement.clientWidth;
    });
    assert.ok(inside, `${label}: the tooltip stays on screen`);
    await noSideways(page, `${label} tapped tip`);
    const [fromTap] = await Promise.all([context.waitForEvent('page'), docsLink.tap()]);
    await fromTap.waitForLoadState();
    assert.equal(fromTap.url(), folderDocs);
    await fromTap.close();
    await text.tap();
    await tip.waitFor({state: 'hidden'});

    // A tap elsewhere closes an open tip.
    await text.tap();
    await tip.waitFor({state: 'visible'});
    await page.locator('#animus-main-heading').tap();
    await tip.waitFor({state: 'hidden'});

    // On a field label a tap shows the tip instead of raising the keyboard.
    const fieldTip = await labelTip(page);
    await fieldTip.tap();
    await (await tooltip(page, fieldTip)).waitFor({state: 'visible'});
    assert.equal(await fieldTip.evaluate(el => document.activeElement === el.closest('label').control), false, `${label}: a tap shows the tip instead of focusing the field`);
    await page.locator('#animus-main-heading').tap();
  }

  // Every tip on the page names a tooltip with text and links into the wiki.
  const all = await page.locator('.animus-tip').evaluateAll(tips => tips.map(root => {
    const text = root.querySelector(':scope > .animus-tip-text');
    return {
      name: text?.textContent.trim() || '',
      role: text?.getAttribute('role'),
      focusable: text?.getAttribute('tabindex'),
      tip: document.getElementById(text?.getAttribute('aria-describedby') || '')?.textContent || '',
      href: root.querySelector('.animus-tip-docs')?.getAttribute('href') || '',
    };
  }));
  assert.ok(all.length > 1);
  for (const info of all) {
    assert.ok(info.name.length > 0, `${label}: a tip without text`);
    assert.equal(info.role, 'link');
    assert.equal(info.focusable, '0');
    assert.ok(info.tip.trim().length > 0, `${label}: ${info.name} has tooltip text`);
    assert.match(info.href, /^https:\/\/docs\.volmitsoftware\.com\/animus\/\d\d-[a-z0-9-]+(#[a-z0-9-]+)?$/, `${label}: ${info.name} links into the wiki`);
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
