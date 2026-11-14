// Every preview fits its frame: at 1600 (preview beside the form), 768 (the
// band above it) and 390 px (behind the Preview button), in the Form and
// Split views, and in fullscreen, a screen fails when
//   - a preview frame (the StageFrame, a 3D stage's frame, the route grid,
//     the recording and rig stages) or the frame's body is larger inside
//     than its box (scrollHeight > clientHeight + 1 or scrollWidth >
//     clientWidth + 1), so it scrolls or spills;
//   - a box between the preview's content and the page's scroller, or inside
//     the preview, clips or scrolls what it holds (overflow hidden, clip,
//     auto or scroll with content larger than the box);
//   - a chest, trading or GUI window, or a 3D canvas, has an edge outside
//     the visible part of its frame;
//   - a window drawn through the fit box is scaled above its natural size,
//     or the space kept for it differs from its scaled size.
// The 3D canvas clips its own labels on purpose, and form controls, text
// cut with an ellipsis and screen-reader text clip their own text; those
// are not counted. Screens: every document page of test/support/screens.cjs
// (the shop in Simple and Advanced with each mode, the trading screen and
// the chest window, the chest GUI, menu, hologram, text animation, presets,
// a new quest, each NPC with its Movement tab, each behavior with its Test
// started, trait groups, rigs, animations, routes, recordings) and a
// server paired with the fake server (NPCs on its terrain).
// ANIMUS_FIT_WIDTHS (comma separated) limits the widths;
// ANIMUS_FIT_SCREENS (a regular expression) checks only the screens whose
// name matches; ANIMUS_FIT_LIST=1 prints every problem; ANIMUS_SCREENSHOTS
// (outside the repository) receives a shot per screen.
//
//   python3 tool/serve_site.py --port <port>
//   dart run tool/fake_server.dart --port=<port> --origin=http://127.0.0.1:<site port> --data=test/fixtures/terrain_view
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_FAKE_URL=http://127.0.0.1:<port> \
//     PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/preview_fit_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {walkScreens} = require('./support/screens.cjs');

const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;
const widths = (process.env.ANIMUS_FIT_WIDTHS || '1600,768,390').split(',').map(Number);
const screens = process.env.ANIMUS_FIT_SCREENS ? new RegExp(process.env.ANIMUS_FIT_SCREENS) : null;

/** Every problem in the previews on the page. */
async function scan(page) {
  return page.evaluate(() => {
    const roots = '.ui-stage, .animus-mc-stage-frame, .animus-recording-stage, .animus-route-grid-wrap, .animus-rig-stage';
    const windows = '.animus-chest-screen, .animus-merchant-screen, .animus-mc-stage, .animus-anim-stage';
    // Boxes that cut their own content on purpose: the 3D canvas and its
    // labels, form controls and their text, screen-reader text, drawings.
    const ownClip = '.animus-mc-stage, button, input, textarea, select, [role="combobox"], .animus-sr-only, svg';
    const problems = [];
    const full = document.fullscreenElement;
    const scroller = full || document.querySelector('main.ui-page') || document.scrollingElement;
    const shown = el => el.checkVisibility({opacityProperty: true, visibilityProperty: true}) && el.getBoundingClientRect().width >= 2;
    const name = el => {
      const classes = [...el.classList].filter(c => !/^is-/.test(c)).slice(0, 2).join('.');
      return `${el.tagName.toLowerCase()}${classes ? '.' + classes : ''}`;
    };
    const size = el => `${el.clientWidth}x${el.clientHeight} holds ${el.scrollWidth}x${el.scrollHeight}`;
    const larger = el => el.scrollHeight > el.clientHeight + 1 || el.scrollWidth > el.clientWidth + 1;
    const clips = el => {
      const cs = getComputedStyle(el);
      return cs.overflowX !== 'visible' || cs.overflowY !== 'visible';
    };
    const cutsOwn = el => el.closest(ownClip) || getComputedStyle(el).textOverflow === 'ellipsis';
    const seen = new Set();
    const report = (el, text) => {
      const key = `${text}|${name(el)}`;
      if (seen.has(el) && seen.has(key)) return;
      seen.add(el);
      seen.add(key);
      problems.push(text);
    };
    const found = [...document.querySelectorAll(roots)].filter(el => shown(el) && (!full || full.contains(el)));
    for (const root of found) {
      // The frame and its body: never larger inside than outside.
      const bodies = [root, ...root.querySelectorAll(':scope > .ui-stage-canvas')];
      for (const el of bodies) {
        if (larger(el)) report(el, `frame ${name(el)} scrolls or spills: ${size(el)}`);
      }
      // Anything inside the frame, or between it and the page's scroller,
      // that clips or scrolls its content.
      const inside = [...root.querySelectorAll('*')].filter(el => shown(el) && !cutsOwn(el));
      const above = [];
      for (let el = root.parentElement; el && el !== scroller && el !== document.body && (!full || full.contains(el)); el = el.parentElement) above.push(el);
      for (const el of [...inside, ...above]) {
        if (bodies.includes(el) || !clips(el) || !larger(el)) continue;
        report(el, `${name(el)} clips ${inside.includes(el) ? 'inside' : 'around'} ${name(root)}: ${size(el)}`);
      }
      // Windows and canvases wholly inside the visible part of the
      // outermost frame.
      if (found.some(other => other !== root && other.contains(root))) continue;
      for (const win of [...(root.matches(windows) ? [root] : []), ...root.querySelectorAll(windows)].filter(shown)) {
        let box = root.getBoundingClientRect();
        box = {left: box.left, top: box.top, right: box.right, bottom: box.bottom};
        for (let el = win.parentElement; el && el !== scroller && el !== document.body; el = el.parentElement) {
          if (!clips(el)) continue;
          const r = el.getBoundingClientRect();
          box = {left: Math.max(box.left, r.left), top: Math.max(box.top, r.top), right: Math.min(box.right, r.right), bottom: Math.min(box.bottom, r.bottom)};
        }
        const r = win.getBoundingClientRect();
        const out = [];
        if (r.bottom > box.bottom + 1) out.push(`bottom ${Math.round(r.bottom - box.bottom)}px`);
        if (r.right > box.right + 1) out.push(`right ${Math.round(r.right - box.right)}px`);
        if (r.left < box.left - 1) out.push(`left ${Math.round(box.left - r.left)}px`);
        if (r.top < box.top - 1) out.push(`top ${Math.round(box.top - r.top)}px`);
        if (out.length) report(win, `${name(win)} ${Math.round(r.width)}x${Math.round(r.height)} cut off in ${name(root)} (${out.join(', ')} outside)`);
      }
      // A window scaled to fit: never above its natural size, and the space
      // kept for it is its scaled size.
      for (const host of root.querySelectorAll('[data-window-fit]')) {
        const win = host.firstElementChild;
        if (!win || !shown(win)) continue;
        const zoom = Number(getComputedStyle(win).zoom || '1');
        if (zoom > 1.0001) report(win, `${name(win)} scaled above its natural size (zoom ${zoom})`);
        const gap = host.getBoundingClientRect().height - win.getBoundingClientRect().height;
        if (Math.abs(gap) > 1) report(host, `${name(win)} keeps ${Math.round(host.getBoundingClientRect().height)}px for ${Math.round(win.getBoundingClientRect().height)}px`);
      }
    }
    return {frames: found.length, problems};
  });
}

async function settle(page) {
  await page.waitForTimeout(250);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function run(browser, width, failures, counts) {
  const viewport = {width, height: width <= 390 ? 844 : 900};
  const context = await browser.newContext({viewport, colorScheme: 'dark', hasTouch: width <= 390});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  let count = 0;
  const check = async (name, {framed = false} = {}) => {
    await page.mouse.move(0, 0);
    await settle(page);
    const {frames, problems} = await scan(page);
    counts.screens++;
    if (framed && !frames) failures.push(`${width} ${name} | no preview frame`);
    for (const problem of problems) failures.push(`${width} ${name} | ${problem}`);
    if (shots) {
      fs.mkdirSync(shots, {recursive: true});
      await page.screenshot({path: path.join(shots, `fit-${width}-${String(++count).padStart(3, '0')}-${name.replace(/[^a-z0-9]+/gi, '-')}.png`)});
    }
  };
  await walkScreens(page, {url, fake, width, check, wanted: name => !screens || screens.test(name)});
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  const failures = [];
  const counts = {screens: 0};
  try {
    for (const width of widths) {
      const started = Date.now();
      const before = failures.length;
      const seen = counts.screens;
      await run(browser, width, failures, counts);
      console.log(`preview_fit_browser: ${width} ${counts.screens - seen} screens, ${failures.length - before} problems, ${Math.round((Date.now() - started) / 1000)} s`);
    }
  } finally {
    await browser.close();
  }
  if (process.env.ANIMUS_FIT_LIST) {
    for (const failure of failures) console.log(failure);
  }
  assert.deepEqual(failures, [], `${failures.length} preview fit problems:\n${failures.slice(0, 80).join('\n')}`);
  console.log('preview_fit_browser: ok');
})().catch(error => { console.error(error.message || error); process.exit(1); });
