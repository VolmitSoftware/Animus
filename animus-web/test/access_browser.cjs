// Keyboard, names, touch targets and contrast on every screen, at 1600, 768
// and 390 px in dark and light (support/schemes.cjs). A screen fails on:
//   - a control without an accessible name, as Chromium's accessibility
//     tree computes it (buttons, links, fields, dropdowns, switches,
//     checkboxes, radios, tabs, options, menu items, sliders), or a group,
//     toolbar or tab strip of controls marked with a role but no name (a
//     form field's row is named by its label);
//   - a control the keyboard reaches that shows no focus indicator: neither
//     it, the box that draws it (up to three parents), nor its next sibling
//     changes outline, ring, border, fill or (on a drawn mark) stroke when it
//     takes keyboard focus;
//   - more than [maxStopsBeforeMain] tab stops before the page's content,
//     the skip link not counted;
//   - on phones (touch, 390 px): a control whose 44 x 44 px square around
//     its centre does not all land on it, a label of it, the library
//     wrapper that toggles it or the slider track it moves on, or a slider
//     track under 44 px tall. Text links inside a sentence are exempt;
//   - text below 4.5:1 against what is behind it, or 3:1 when large (24 px,
//     or 18.66 px bold). Text drawn with an outline (a text shadow on every
//     side) is measured against the outline; disabled controls and pictures
//     drawn in block characters are exempt.
// Screens: test/support/screens.cjs with its frame pages and every tab of
// the first document of each kind.
// ANIMUS_ACCESS_WIDTHS (comma separated) limits the widths;
// ANIMUS_ACCESS_SCREENS (a regular expression) checks only the screens
// whose name matches; ANIMUS_ACCESS_LIST=1 prints every problem;
// ANIMUS_SCREENSHOTS (outside the repository) receives a shot per screen.
//
//   python3 tool/serve_site.py --port <port>
//   dart run tool/fake_server.dart --port=<port> --origin=http://127.0.0.1:<site port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_FAKE_URL=http://127.0.0.1:<port> \
//     PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/access_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {walkScreens} = require('./support/screens.cjs');
const {schemes, themedContext} = require('./support/schemes.cjs');

const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;
const widths = (process.env.ANIMUS_ACCESS_WIDTHS || '1600,768,390').split(',').map(Number);
const screens = process.env.ANIMUS_ACCESS_SCREENS ? new RegExp(process.env.ANIMUS_ACCESS_SCREENS) : null;

/**
 * Stops before the content, the skip link aside: seven on a wide screen (the
 * brand, the workspace, Search, Simple and Advanced, the menu and the nav,
 * one stop), with room for one more.
 */
const maxStopsBeforeMain = 8;

/** Controls that are not touch targets of their own, each with its reason. */
const notTargets = [
  // Inventory and chest slots draw the game's 9-column grid at the width the screen allows.
  '.animus-item-slot', '.animus-chest-slot',
  // A trading screen's offer rows are part of the Minecraft window drawing.
  '.animus-merchant-offer',
  // Timeline keys and events are marks on a lane, sized by the timeline.
  '.animus-rig-key', '.animus-rig-event',
  // A text animation's colour frames are a palette of swatches.
  '.animus-anim-swatch',
  // Marks drawn on the stage, the route grid and the rig.
  'svg',
  // A slider's thumb is a mark on its track; the track takes the taps and
  // is checked as a whole below.
  '.arcane-slider-thumb',
].join(', ');

/** Rows that are touch targets as a whole; the controls inside them are checked. */
const wholeRows = [
  // The code view's lines and the list's rows are picked by row.
  '.animus-code-line', '.ui-library-row',
].join(', ');

const named = new Set(['button', 'link', 'textbox', 'searchbox', 'combobox', 'listbox', 'checkbox', 'switch', 'radio', 'tab', 'menuitem', 'menuitemcheckbox', 'menuitemradio', 'option', 'slider', 'spinbutton', 'treeitem']);
const groups = new Set(['group', 'radiogroup', 'toolbar', 'tablist']);

/** Controls and role-marked groups with no accessible name. */
async function unnamed(page, cdp) {
  const {nodes} = await cdp.send('Accessibility.getFullAXTree');
  const byId = new Map(nodes.map(node => [node.nodeId, node]));
  const out = [];
  for (const node of nodes) {
    if (node.ignored || node.backendDOMNodeId == null) continue;
    const role = node.role?.value;
    const label = String(node.name?.value || '').trim();
    if (label) continue;
    if (named.has(role)) {
      out.push({id: node.backendDOMNodeId, role});
    } else if (groups.has(role)) {
      let controls = 0;
      const walk = id => {
        const child = byId.get(id);
        if (!child) return;
        if (!child.ignored && named.has(child.role?.value)) controls++;
        for (const next of child.childIds || []) walk(next);
      };
      for (const next of node.childIds || []) walk(next);
      if (controls >= 2) out.push({id: node.backendDOMNodeId, role, group: true});
    }
  }
  const problems = [];
  for (const {id, role, group} of out) {
    const {object} = await cdp.send('DOM.resolveNode', {backendNodeId: id}).catch(() => ({}));
    if (!object?.objectId) continue;
    const {result} = await cdp.send('Runtime.callFunctionOn', {
      objectId: object.objectId,
      returnByValue: true,
      functionDeclaration: `function (group) {
        const el = this.nodeType === 1 ? this : this.parentElement;
        if (!el) return null;
        // A group counts only when the page marked it with a role. A form
        // field's row is a group whose control its label names (shadcn Field).
        if (group && (!el.getAttribute('role') || el.matches('[data-slot="field"]'))) return null;
        const box = el.getBoundingClientRect();
        if (box.width < 1 && box.height < 1) return null;
        const cls = typeof el.className === 'string' ? el.className.split(' ').filter(Boolean).slice(0, 2).join('.') : '';
        const near = (el.closest('[aria-label], label, .arcane-field, [data-npc-tab], section') || el.parentElement);
        const context = (near?.getAttribute?.('aria-label') || near?.innerText || '').trim().replace(/\\s+/g, ' ').slice(0, 40);
        return el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + (cls ? '.' + cls : '') + ' near "' + context + '"';
      }`,
      arguments: [{value: Boolean(group)}],
    }).catch(() => ({result: {}}));
    if (result?.value) problems.push(`no name (${role}): ${result.value}`);
    await cdp.send('Runtime.releaseObject', {objectId: object.objectId}).catch(() => {});
  }
  return problems;
}

/** Focus rings, stops before the content, touch targets and contrast. */
async function scan(page, {touch}) {
  // Keyboard modality: the rings that follow are the ones a Tab would show.
  await page.keyboard.press('Shift');
  return page.evaluate(({touch, notTargets, wholeRows, maxStopsBeforeMain}) => {
    const problems = [];
    const hidden = el => !!el.closest('[inert], [aria-hidden="true"], .animus-sr-only');
    const shown = el => {
      if (!el.checkVisibility({opacityProperty: true, visibilityProperty: true})) return false;
      const r = el.getBoundingClientRect();
      return r.width >= 2 && r.height >= 2 && !hidden(el);
    };
    const describe = el => {
      const cls = typeof el.className === 'string' ? el.className.split(' ').filter(Boolean).slice(0, 2).join('.') : '';
      const text = (el.getAttribute('aria-label') || el.innerText || el.getAttribute('placeholder') || '').trim().replace(/\s+/g, ' ').slice(0, 30);
      return `${el.tagName.toLowerCase()}${cls ? '.' + cls : ''} "${text}"`;
    };
    const tabbable = el => {
      if (el.disabled || hidden(el)) return false;
      const index = el.getAttribute('tabindex');
      if (index !== null && Number(index) < 0) return false;
      if (el.matches('summary') && !el.parentElement?.matches('details')) return false;
      if (el.matches('a:not([href])') && index === null) return false;
      return shown(el);
    };
    const focusables = [...document.querySelectorAll('a[href], button, input:not([type="hidden"]), select, textarea, summary, [tabindex]')].filter(tabbable);

    // Stops before the content.
    const main = document.getElementById('animus-main');
    if (main) {
      const before = focusables.filter(el => !main.contains(el) && (el.compareDocumentPosition(main) & Node.DOCUMENT_POSITION_FOLLOWING) && !/^Skip to content$/.test((el.textContent || '').trim()));
      if (before.length > maxStopsBeforeMain) problems.push(`${before.length} tab stops before the content: ${before.map(describe).join(', ')}`);
    }

    // Focus rings, with transitions off so the computed look is the end state.
    const still = document.createElement('style');
    still.textContent = '*, *::before, *::after { transition: none !important; animation: none !important; }';
    document.head.appendChild(still);
    const look = el => {
      if (!el) return '';
      const cs = getComputedStyle(el);
      const outline = cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) > 0 && !/rgba\([^)]*,\s*0\)$/.test(cs.outlineColor) ? `${cs.outlineStyle} ${cs.outlineWidth} ${cs.outlineColor}` : 'none';
      // SVG marks (route points, rig handles) show focus with their stroke or fill.
      return [outline, cs.boxShadow, cs.borderTopColor, cs.borderBottomColor, cs.backgroundColor, cs.stroke, cs.strokeWidth, cs.fill].join('|');
    };
    const parts = el => [el, el.parentElement, el.parentElement?.parentElement, el.parentElement?.parentElement?.parentElement, el.nextElementSibling];
    const scrollX = window.scrollX;
    const scrollY = window.scrollY;
    const scrollers = new Map();
    for (const el of focusables) {
      for (let up = el.parentElement; up; up = up.parentElement) if (up.scrollTop || up.scrollLeft) scrollers.set(up, [up.scrollTop, up.scrollLeft]);
    }
    for (const el of focusables) {
      if (!el.isConnected || !shown(el)) continue;
      if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur();
      const resting = parts(el).map(look);
      el.focus({preventScroll: true});
      if (document.activeElement !== el) continue;
      if (!el.matches(':focus-visible')) {
        problems.push(`no keyboard focus style matches: ${describe(el)}`);
        continue;
      }
      const focused = parts(el).map(look);
      if (focused.every((value, index) => value === resting[index])) problems.push(`no focus ring: ${describe(el)}`);
      el.blur();
    }
    still.remove();
    window.scrollTo(scrollX, scrollY);
    for (const [el, [top, left]] of scrollers) {
      el.scrollTop = top;
      el.scrollLeft = left;
    }

    // Touch targets: the 44 px square around each control lands on it.
    if (touch) {
      const size = 44;
      const controls = [...document.querySelectorAll('a[href], button, input:not([type="hidden"]), select, textarea, summary, [role="button"], [role="switch"], [role="checkbox"], [role="radio"], [role="tab"], [role="option"], [role="menuitem"], [role="combobox"], [role="slider"]')]
        .filter(el => shown(el) && !el.disabled && !el.matches(notTargets) && !el.closest(notTargets) && !el.matches(wholeRows));
      const owns = (el, hit) => {
        if (!hit) return false;
        if (hit === el || el.contains(hit)) return true;
        const label = hit.closest('label');
        if (label && (label.control === el || label.contains(el))) return true;
        // The library's checkbox wrapper toggles the box inside it.
        const wrapper = hit.closest('.arcane-checkbox-wrapper, .arcane-radio-wrapper');
        if (wrapper && wrapper.contains(el) && wrapper.querySelectorAll('[role="checkbox"], [role="radio"], input').length === 1) return true;
        // A tap on a slider's track moves its thumb there.
        const slider = hit.closest('.arcane-slider');
        return !!slider && slider.contains(el);
      };
      for (const track of document.querySelectorAll('[data-arcane-slider-track]')) {
        const box = track.getBoundingClientRect();
        if (shown(track) && box.height < size - 0.5) problems.push(`slider track under 44 px: ${describe(track.closest('.arcane-slider') || track)} ${Math.round(box.width)}x${Math.round(box.height)}`);
      }
      for (const el of controls) {
        // A text link inside a sentence is exempt.
        if (el.matches('a') && getComputedStyle(el).display === 'inline' && el.parentElement && /^(p|li|span|small|div)$/i.test(el.parentElement.tagName) && (el.parentElement.textContent || '').trim().length > (el.textContent || '').trim().length + 1) continue;
        const r = el.getBoundingClientRect();
        const cx = r.left + r.width / 2;
        const cy = r.top + r.height / 2;
        if (cx < 0 || cy < 0 || cx > innerWidth || cy > innerHeight) continue;
        // Only a control the page shows at its centre (not under a bar or a popover).
        if (!owns(el, document.elementFromPoint(cx, cy))) continue;
        // The centre, the middle of each side of the 44 px square and its
        // corners rounded off by 6 px (a control's own rounded corners do
        // not take taps). Points the page does not show here (under the top
        // bar, the save bar or a notice, past a scrolled box) are not tested.
        const half = size / 2 - 1;
        const corner = half - 6;
        const offsets = [[0, 0], [-half, 0], [half, 0], [0, -half], [0, half], [-corner, -corner], [corner, -corner], [-corner, corner], [corner, corner]];
        let view = {left: 0, top: 0, right: innerWidth, bottom: innerHeight};
        for (let up = el.parentElement; up; up = up.parentElement) {
          const cs = getComputedStyle(up);
          if (cs.overflowX === 'visible' && cs.overflowY === 'visible') continue;
          const box = up.getBoundingClientRect();
          view = {left: Math.max(view.left, box.left), top: Math.max(view.top, box.top), right: Math.min(view.right, box.right), bottom: Math.min(view.bottom, box.bottom)};
        }
        const misses = [];
        for (const [dx, dy] of offsets) {
          const x = cx + dx;
          const y = cy + dy;
          if (x < view.left + 1 || x > view.right - 1 || y < view.top + 1 || y > view.bottom - 1) continue;
          const hit = document.elementFromPoint(x, y);
          // A passing notice over the page hides the control there for now.
          if (hit && hit.closest('[data-arcane-toast-surface], [class*="toast"]')) continue;
          if (!owns(el, hit)) misses.push(`${Math.round(dx)},${Math.round(dy)}`);
        }
        if (misses.length) problems.push(`touch target under 44 px: ${describe(el)} ${Math.round(r.width)}x${Math.round(r.height)} misses ${misses.join(' ')}`);
      }
    }

    // Contrast.
    const parse = text => {
      const m = /rgba?\(([\d.]+),?\s*([\d.]+),?\s*([\d.]+)(?:[,/]\s*([\d.]+))?\)/.exec(text || '');
      return m ? [Number(m[1]), Number(m[2]), Number(m[3]), m[4] === undefined ? 1 : Number(m[4])] : null;
    };
    const over = (top, under) => [0, 1, 2].map(i => top[i] * top[3] + under[i] * (1 - top[3])).concat(1);
    const luminance = ([r, g, b]) => {
      const channel = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
      return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
    };
    const ratio = (a, b) => { const x = luminance(a); const y = luminance(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
    const pageColour = () => parse(getComputedStyle(document.body).backgroundColor) || [255, 255, 255, 1];
    // What is behind the element: the stacked backgrounds up to an opaque one.
    // An image or a canvas behind it makes the ratio unknown (null).
    const behind = el => {
      const stack = [];
      for (let node = el; node && node.nodeType === 1; node = node.parentElement) {
        const cs = getComputedStyle(node);
        if (cs.backgroundImage && cs.backgroundImage !== 'none' && !/gradient/.test(cs.backgroundImage)) return null;
        const colour = parse(cs.backgroundColor);
        if (colour && colour[3] > 0) {
          stack.push(colour);
          if (colour[3] >= 1) break;
        }
      }
      let colour = pageColour();
      if (colour[3] < 1) colour = over(colour, [255, 255, 255, 1]);
      for (let i = stack.length - 1; i >= 0; i--) colour = over(stack[i], colour);
      return colour;
    };
    // An outline: unblurred shadows straight out to every side in one
    // colour (a drop shadow beside them does not count).
    const outlineOf = cs => {
      const shadow = cs.textShadow;
      if (!shadow || shadow === 'none') return null;
      const sides = new Set();
      let colour = null;
      for (const part of shadow.split(/,(?![^(]*\))/).map(each => each.trim())) {
        const c = parse(part);
        const numbers = part.replace(/rgba?\([^)]*\)/, '').trim().split(/\s+/).map(parseFloat);
        if (!c || numbers.length < 2 || (numbers[2] || 0) > 0.5) continue;
        if (numbers[0] !== 0 && numbers[1] !== 0) continue;
        if (colour && colour.join() !== c.join()) return null;
        colour = c;
        if (numbers[0] > 0) sides.add('r');
        if (numbers[0] < 0) sides.add('l');
        if (numbers[1] > 0) sides.add('b');
        if (numbers[1] < 0) sides.add('t');
      }
      return sides.size === 4 ? colour : null;
    };
    const seen = new Set();
    // Block and box characters draw pictures (pixel-art holograms), not text.
    const drawing = /^[\s\u2500-\u259f]+$/;
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (!node.textContent.trim() || drawing.test(node.textContent)) continue;
      const el = node.parentElement;
      if (!el || seen.has(el) || !shown(el)) continue;
      seen.add(el);
      if (el.closest('[disabled], [aria-disabled="true"], [data-disabled="true"], .disabled, canvas, svg, noscript, script, style')) continue;
      const cs = getComputedStyle(el);
      const fg = parse(cs.color);
      if (!fg || fg[3] === 0) continue;
      const outline = outlineOf(cs);
      const backdrop = outline ? over(outline, behind(el) || [0, 0, 0, 1]) : behind(el);
      if (!backdrop) continue;
      const text = over(fg, backdrop);
      const value = ratio(text, backdrop);
      const px = parseFloat(cs.fontSize);
      const large = px >= 24 || (px >= 18.66 && Number(cs.fontWeight) >= 700);
      const need = large ? 3 : 4.5;
      if (value < need) problems.push(`contrast ${value.toFixed(2)} under ${need}: ${describe(el)} ${cs.color} on rgb(${backdrop.slice(0, 3).map(Math.round).join(', ')})`);
    }
    return problems;
  }, {touch, notTargets, wholeRows, maxStopsBeforeMain});
}

async function settle(page) {
  await page.waitForTimeout(250);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function run(browser, width, scheme, failures, counts) {
  const {context} = await themedContext(browser, width, scheme);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const cdp = await context.newCDPSession(page);
  await cdp.send('DOM.enable');
  await cdp.send('Accessibility.enable');
  const touch = width <= 390;
  let count = 0;
  const check = async name => {
    await page.mouse.move(0, 0);
    await settle(page);
    counts.screens++;
    const problems = [...await unnamed(page, cdp), ...await scan(page, {touch})];
    for (const problem of new Set(problems)) failures.push(`${width} ${scheme} ${name} | ${problem}`);
    if (shots) {
      fs.mkdirSync(shots, {recursive: true});
      await page.screenshot({path: path.join(shots, `access-${width}-${scheme}-${String(++count).padStart(3, '0')}-${name.replace(/[^a-z0-9]+/gi, '-')}.png`)});
    }
  };
  await walkScreens(page, {url, fake, width, check, tabs: true, pages: true, wanted: name => !screens || screens.test(name)});
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  const failures = [];
  const counts = {screens: 0};
  try {
    for (const scheme of schemes()) {
      for (const width of widths) {
        const started = Date.now();
        const before = failures.length;
        const seen = counts.screens;
        await run(browser, width, scheme, failures, counts);
        console.log(`access_browser: ${width} ${scheme} ${counts.screens - seen} screens, ${failures.length - before} problems, ${Math.round((Date.now() - started) / 1000)} s`);
      }
    }
  } finally {
    await browser.close();
  }
  if (process.env.ANIMUS_ACCESS_LIST) {
    for (const failure of failures) console.log(failure);
  }
  assert.deepEqual(failures, [], `${failures.length} access problems:\n${failures.slice(0, 80).join('\n')}`);
  console.log('access_browser: ok');
})().catch(error => { console.error(error.message || error); process.exit(1); });
