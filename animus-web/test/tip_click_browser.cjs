// A tip opened by the keyboard never eats a click. On a new NPC's Basics
// tab it focuses each (i) with the keyboard until one's tip covers a
// control, clicks that control through the tip, and fails unless the
// click reaches the control and the tip closes. The open tip's Docs link stays
// clickable, and a tip opened by hovering its (i) stays open while the
// pointer moves onto it; opened by the keyboard later, it lets clicks
// through again. It needs no fake server.
//
//   python3 tool/serve_site.py --port <port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> \
//     PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/tip_click_browser.cjs
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {blankWorkspace} = require('./support/shell.cjs');

const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site'); })()).replace(/\/$/, '');

/** Focuses [info] the way Tab does, so it shows its focus ring and its tip. */
async function keyboardFocus(page, info) {
  await page.keyboard.press('Shift');
  await info.focus();
}

/**
 * The open tip of [info] and a control under it: a point inside the tip
 * where, with the tip hidden, a button, field or picker of the form sits.
 */
async function coveredControl(info) {
  return info.evaluate((button) => {
    const wrap = button.closest('.animus-info');
    const tip = wrap && wrap.querySelector('[role="tooltip"]');
    if (!tip || tip.getAttribute('data-arcane-state') !== 'open') return null;
    const box = tip.getBoundingClientRect();
    const links = [...tip.querySelectorAll('a, button')].map((link) => link.getBoundingClientRect());
    const was = tip.style.visibility;
    tip.style.visibility = 'hidden';
    try {
      for (let y = box.top + 4; y < box.bottom - 2; y += 6) {
        for (let x = box.left + 4; x < box.right - 2; x += 8) {
          const under = document.elementFromPoint(x, y);
          const control = under && under.closest('#animus-main button, #animus-main input, #animus-main select, #animus-main textarea, #animus-main [role="combobox"]');
          if (!control || control.closest('.animus-info')) continue;
          // The point is on the tip's text, not on its Docs link.
          if (links.some((r) => x >= r.left && x <= r.right && y >= r.top && y <= r.bottom)) continue;
          window.__covered = control;
          return {x, y, control: `${control.tagName}#${control.id} ${control.getAttribute('aria-label') || control.textContent.trim().slice(0, 24)}`};
        }
      }
      return null;
    } finally {
      tip.style.visibility = was;
    }
  });
}

(async () => {
  const browser = await chromium.launch();
  const page = await (await browser.newContext({viewport: {width: 1600, height: 900}, colorScheme: 'dark'})).newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto(url);
  await blankWorkspace(page, 'Tips');
  const workspace = await page.evaluate(() => location.hash);
  await page.goto(`${url}/${workspace}/npcs`);
  await page.getByRole('button', {name: 'New NPC', exact: true}).first().click();
  const dialog = page.getByRole('dialog', {name: 'New NPC'});
  await dialog.waitFor();
  await dialog.locator('#npc-new-name').fill('Tipper');
  await dialog.getByRole('button', {name: /Create/}).click();
  await page.locator('[data-npc-tab="basics"]').waitFor();
  await page.waitForTimeout(600);

  // Keyboard focus on an (i) opens its tip over the form.
  const infos = page.locator('[data-npc-tab="basics"] [data-animus-info]').filter({visible: true});
  let point = null;
  let info = null;
  for (let i = 0; i < await infos.count() && !point; i++) {
    info = infos.nth(i);
    await keyboardFocus(page, info);
    // Its tip opens, and the one before it closes after its delay.
    await page.waitForFunction((button) => button.closest('.animus-info').classList.contains('is-open') && document.querySelectorAll('.animus-info.is-open').length === 1, await info.elementHandle());
    point = await coveredControl(info);
  }
  assert.ok(point, 'some (i) tip covers a control of the form');

  // Its Docs link is still a link a pointer can press.
  const docs = page.locator('.animus-info.is-open [role="tooltip"] a').first();
  const docsBox = await docs.boundingBox();
  const docsHit = await page.evaluate(({x, y}) => !!document.elementFromPoint(x, y)?.closest('.animus-info.is-open [role="tooltip"] a'), {x: docsBox.x + docsBox.width / 2, y: docsBox.y + docsBox.height / 2});
  assert.ok(docsHit, 'the Docs link of a tip opened by the keyboard takes the pointer');

  // A click on the control under the tip reaches the control.
  await page.evaluate(() => {
    window.__hit = null;
    document.addEventListener('click', (event) => {
      if (window.__hit === null) window.__hit = `${event.target.tagName}.${String(event.target.className).split(' ')[0]} ${window.__covered.contains(event.target)}`;
    }, {capture: true, once: true});
  });
  await page.mouse.click(point.x, point.y);
  await page.waitForTimeout(400);
  const hit = await page.evaluate(() => window.__hit);
  assert.ok(hit && hit.endsWith(' true'), `the click went to ${hit}, not the covered ${point.control}`);
  assert.equal(await page.locator('.animus-info.is-open').count(), 0, 'the tip closed');

  // A tip opened by hovering its (i) stays open as the pointer moves onto it.
  await page.mouse.click(5, 895);
  const hover = infos.first();
  const at = await hover.boundingBox();
  await page.mouse.move(at.x + at.width / 2, at.y + at.height / 2);
  await page.locator('.animus-info.is-open').waitFor();
  const tip = await page.locator('.animus-info.is-open [role="tooltip"]').boundingBox();
  await page.mouse.move(tip.x + 12, tip.y + 10, {steps: 8});
  await page.waitForTimeout(500);
  assert.equal(await page.locator('.animus-info.is-open').count(), 1, 'a hovered tip stays open under the pointer');

  // Hovered before, then opened by the keyboard, it lets clicks through again.
  await page.mouse.move(5, 895, {steps: 4});
  await page.waitForFunction(() => document.querySelectorAll('.animus-info.is-open').length === 0);
  await keyboardFocus(page, hover);
  await page.waitForFunction((button) => button.closest('.animus-info').classList.contains('is-open'), await hover.elementHandle());
  const through = await hover.evaluate((button) => {
    const tip = button.closest('.animus-info').querySelector('[role="tooltip"]');
    const box = tip.getBoundingClientRect();
    const under = document.elementFromPoint(box.left + 6, box.top + 6);
    return !tip.contains(under);
  });
  assert.ok(through, 'a tip hovered earlier and opened by the keyboard lets clicks through');

  assert.deepEqual(errors, []);
  await browser.close();
  console.log('tip click: ok');
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
