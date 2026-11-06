// Walks every screen of the built site at 1440, 1280, 1024, 768 and 390 px
// wide, dark and light, and fails on a layout fault: boxes that overlap a
// sibling, content clipped by its container, sideways scrolling, a border
// or divider running through a control, an input too narrow for its value
// or placeholder, text running out of its box, and a row label that
// focuses no control. Every inspector part is opened first, so closed parts
// are checked too.
// Screens: home, the example-packs workspace and each of its documents,
// every document in the gui_editors, rig_editor, groups_recordings,
// npc_browser and npc_preview fixtures and ../schema/fixtures/export (NPCs,
// behaviors, routes, groups, recordings, shops, chest GUIs, menus,
// holograms, text animations, rigs, animations), one document per kind in
// the preview, code and split views, New document, the NPC and recording
// lists, Send to a server and the converter.
// ANIMUS_LAYOUT_WIDTHS (optional, comma separated) limits the widths;
// ANIMUS_SCREENSHOTS (optional, outside the repository) receives a shot of
// every screen.
//
//   python3 tool/serve_site.py --port <port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/layout_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const url = process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })();
const shots = process.env.ANIMUS_SCREENSHOTS;
const folders = [
  ...['gui_editors', 'rig_editor', 'groups_recordings', 'npc_browser', 'npc_preview'].map(name => path.resolve(__dirname, 'fixtures', name)),
  path.resolve(__dirname, '../../schema/fixtures/export'),
];
const widths = (process.env.ANIMUS_LAYOUT_WIDTHS || '1440,1280,1024,768,390').split(',').map(Number);

/** Every layout fault on the page, as readable strings. */
async function scan(page) {
  return page.evaluate(() => {
    const out = [];
    const describe = el => {
      const id = el.id ? `#${el.id}` : '';
      const classes = typeof el.className === 'string' && el.className.trim()
        ? `.${el.className.trim().split(/\s+/).filter(name => !name.startsWith('arcane-')).slice(0, 2).join('.')}` : '';
      const label = el.getAttribute('aria-label') || el.getAttribute('placeholder') || (el.children.length ? '' : el.textContent.trim());
      return `${el.tagName.toLowerCase()}${id}${classes === '.' ? '' : classes}${label ? ` "${label.slice(0, 28)}"` : ''}`;
    };
    // Canvases, the 3D stage, the chest window and the timeline playhead draw
    // their own pictures, and toasts float over the page; boxes inside them
    // are placed on purpose.
    const drawn = '.animus-mc-stage, .animus-chest-screen, .animus-rig-playhead, svg, canvas, .animus-sr-only, [aria-hidden="true"], .animus-boot, [class*="toast"]';
    const shown = el => {
      if (el.closest(drawn)) return false;
      // Inside a closed part only the summary shows.
      const closed = el.closest('details:not([open])');
      if (closed && !el.closest('summary')) return false;
      const style = getComputedStyle(el);
      if (style.visibility === 'hidden' || style.display === 'none' || parseFloat(style.opacity) === 0) return false;
      // Visually hidden labels are 1 px boxes read only by screen readers.
      if (style.clipPath.startsWith('inset(50%') || /rect\(0(px)?,? 0/.test(style.clip)) return false;
      const box = el.getBoundingClientRect();
      if (box.width <= 1 || box.height <= 1) return false;
      // Inside a visually hidden box, such as a table head kept for screen readers.
      for (let up = el.parentElement; up && up !== document.body; up = up.parentElement) {
        const outer = up.getBoundingClientRect();
        if ((outer.width <= 1 || outer.height <= 1) && getComputedStyle(up).overflow !== 'visible') return false;
      }
      return true;
    };
    const flows = el => !['absolute', 'fixed', 'sticky'].includes(getComputedStyle(el).position);
    // The part of [el] its clipping ancestors leave visible.
    const clipBoxes = new Map();
    const visibleBox = el => {
      if (clipBoxes.has(el)) return clipBoxes.get(el);
      const box = el.getBoundingClientRect();
      let left = box.left, right = box.right, top = box.top, bottom = box.bottom;
      for (let up = el.parentElement; up && up !== document.body; up = up.parentElement) {
        const style = getComputedStyle(up);
        if (style.position === 'fixed') break;
        const clipX = style.overflowX !== 'visible', clipY = style.overflowY !== 'visible';
        if (!clipX && !clipY) continue;
        const outer = up.getBoundingClientRect();
        if (clipX) { left = Math.max(left, outer.left); right = Math.min(right, outer.right); }
        if (clipY) { top = Math.max(top, outer.top); bottom = Math.min(bottom, outer.bottom); }
      }
      const result = {left, right, top, bottom, empty: right - left < 1 || bottom - top < 1};
      clipBoxes.set(el, result);
      return result;
    };
    const all = [...document.querySelectorAll('body *')].filter(shown);
    if (all.length < 20) out.push(`only ${all.length} elements on the page`);

    // Siblings in flow whose boxes cross.
    for (const parent of new Set(all.map(el => el.parentElement))) {
      if (!parent) continue;
      const kids = [...parent.children].filter(el => shown(el) && flows(el));
      for (let i = 0; i < kids.length; i++) {
        const a = kids[i].getBoundingClientRect();
        for (let j = i + 1; j < kids.length; j++) {
          const b = kids[j].getBoundingClientRect();
          const x = Math.min(a.right, b.right) - Math.max(a.left, b.left);
          const y = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
          if (x > 1 && y > 1) out.push(`overlap ${describe(kids[i])} and ${describe(kids[j])} by ${Math.round(x)}x${Math.round(y)}`);
        }
      }
    }

    // Containers that cut content off sideways.
    for (const el of all) {
      const style = getComputedStyle(el);
      if (el.matches('input, textarea, select, pre, code, .animus-code *')) continue;
      const extra = el.scrollWidth - el.clientWidth;
      if (extra <= 1 || el.clientWidth === 0) continue;
      if (style.overflowX === 'hidden' || style.overflowX === 'clip') {
        if (style.textOverflow === 'ellipsis') continue;
        out.push(`clipped ${describe(el)} hides ${extra}px`);
      } else if (style.overflowX === 'auto' || style.overflowX === 'scroll') {
        // Tab strips and the animation timeline scroll sideways by design.
        if (el.matches('.animus-tabs, [role="tablist"], .animus-rig-lanes')) continue;
        out.push(`side scroll ${describe(el)} by ${extra}px`);
      }
    }

    // Borders and thin dividers running through a control.
    const controls = all.filter(el => el.matches('input:not([type="hidden"]), select, textarea, button, [role="combobox"]') && !visibleBox(el).empty);
    const lines = [];
    for (const el of all) {
      const style = getComputedStyle(el);
      // A sticky label stays in view over content scrolling under it.
      if (style.position === 'sticky') continue;
      const box = el.getBoundingClientRect();
      const clear = colour => colour === 'transparent' || /rgba\([^)]*,\s*0\)$/.test(colour);
      const width = side => ['none', 'hidden'].includes(style[`border${side}Style`]) || clear(style[`border${side}Color`]) ? 0 : parseFloat(style[`border${side}Width`]) || 0;
      if (width('Left') > 0) lines.push({el, x: box.left, top: box.top, bottom: box.bottom});
      if (width('Right') > 0) lines.push({el, x: box.right, top: box.top, bottom: box.bottom});
      if (width('Top') > 0) lines.push({el, y: box.top, left: box.left, right: box.right});
      if (width('Bottom') > 0) lines.push({el, y: box.bottom, left: box.left, right: box.right});
      if (box.width <= 3 && box.height > 8 && style.backgroundColor !== 'rgba(0, 0, 0, 0)') lines.push({el, x: box.left + box.width / 2, top: box.top, bottom: box.bottom});
      if (box.height <= 3 && box.width > 8 && style.backgroundColor !== 'rgba(0, 0, 0, 0)') lines.push({el, y: box.top + box.height / 2, left: box.left, right: box.right});
    }
    for (const line of lines) {
      const seen = visibleBox(line.el);
      if (seen.empty) continue;
      for (const control of controls) {
        if (control.contains(line.el)) continue;
        const c = visibleBox(control);
        const crossesX = line.x !== undefined && line.x > c.left + 1 && line.x < c.right - 1
          && Math.min(line.bottom, seen.bottom, c.bottom) - Math.max(line.top, seen.top, c.top) > 2
          && line.x >= seen.left - 0.5 && line.x <= seen.right + 0.5;
        const crossesY = line.y !== undefined && line.y > c.top + 1 && line.y < c.bottom - 1
          && Math.min(line.right, seen.right, c.right) - Math.max(line.left, seen.left, c.left) > 2
          && line.y >= seen.top - 0.5 && line.y <= seen.bottom + 0.5;
        if (crossesX || crossesY) out.push(`line of ${describe(line.el)} crosses ${describe(control)}`);
      }
    }

    // Inputs too narrow for their value or placeholder.
    const measure = document.createElement('canvas').getContext('2d');
    const textWidth = (text, style) => {
      measure.font = `${style.fontStyle} ${style.fontWeight} ${style.fontSize} ${style.fontFamily}`;
      return measure.measureText(text).width;
    };
    for (const el of controls) {
      const style = getComputedStyle(el);
      const room = el.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
      let text = null, need = 0;
      if (el.matches('input:not([type]), input[type="text"], input[type="number"], input[type="search"], input[type="url"]')) {
        // A long text value scrolls inside its field; a number has to fit,
        // with room for the browser's step arrows.
        const arrows = el.type === 'number' ? 18 : 0;
        for (const candidate of el.type === 'number' ? [el.value, el.placeholder] : [el.placeholder]) {
          if (!candidate) continue;
          const width = textWidth(candidate, style) + arrows;
          if (width > need) { need = width; text = candidate; }
        }
      } else if (el.matches('select') && el.selectedIndex >= 0) {
        // The right padding already keeps the arrow clear.
        text = el.options[el.selectedIndex].text;
        need = textWidth(text, style);
      }
      // A select or text field already as wide as its column has all the
      // room the layout gives; a long option or example then truncates. A
      // number field always has to fit.
      const column = el.parentElement.closest(':not(.arcane-native-select-shell):not([class$="-select-wrapper"])');
      const columnStyle = getComputedStyle(column);
      const columnWidth = column.clientWidth - parseFloat(columnStyle.paddingLeft) - parseFloat(columnStyle.paddingRight);
      const full = el.type !== 'number' && el.getBoundingClientRect().width >= columnWidth - 1;
      if (text && need > room + 1 && !full) out.push(`narrow ${describe(el)} needs ${Math.ceil(need)}px, has ${Math.floor(room)}px for "${text.slice(0, 28)}"`);
    }

    // A range or hint said twice: as the placeholder and again under the field.
    for (const el of controls) {
      const hint = el.placeholder && el.closest('.animus-gui-control, .animus-field, label')?.querySelector('.animus-gui-help, .animus-field-help');
      if (hint && hint.textContent.trim().toLowerCase().replace(/\.$/, '') === el.placeholder.trim().toLowerCase()) {
        out.push(`hint repeats the placeholder of ${describe(el)}`);
      }
    }

    // A row label that names no control: clicking it focuses nothing.
    for (const label of document.querySelectorAll('#animus-main label[for]')) {
      if (!shown(label)) continue;
      const target = document.getElementById(label.htmlFor);
      if (!target || !target.matches('input, select, textarea, button')) out.push(`label ${describe(label)} names no control`);
    }

    // Text running past its box.
    for (const el of all) {
      if (el.matches('input, textarea, select, option, pre, code, .animus-code *')) continue;
      const own = [...el.childNodes].filter(node => node.nodeType === Node.TEXT_NODE && node.textContent.trim());
      if (!own.length) continue;
      const style = getComputedStyle(el);
      if (style.textOverflow === 'ellipsis') continue;
      const box = el.getBoundingClientRect();
      const range = document.createRange();
      for (const node of own) {
        range.selectNodeContents(node);
        const text = range.getBoundingClientRect();
        if (text.width === 0) continue;
        const spill = Math.max(text.right - box.right, box.left - text.left);
        if (spill > 2) { out.push(`text out of ${describe(el)} by ${Math.round(spill)}px`); break; }
      }
    }

    if (document.documentElement.scrollWidth > document.documentElement.clientWidth) {
      out.push(`page scrolls sideways by ${document.documentElement.scrollWidth - document.documentElement.clientWidth}px`);
    }
    return [...new Set(out)];
  });
}

async function settle(page) {
  await page.waitForTimeout(150);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

/**
 * Opens every closed inspector part and disclosure through its summary, the
 * way a person does, so the editor keeps it open across its next render.
 */
async function openParts(page) {
  for (let round = 0; round < 60; round++) {
    const clicked = await page.evaluate(() => {
      const summary = document.querySelector('#animus-main details:not([open]):not([data-layout-tried]) > summary');
      if (!summary) return false;
      summary.parentElement.setAttribute('data-layout-tried', '');
      summary.click();
      return true;
    });
    if (!clicked) return;
    await settle(page);
  }
}

async function run(browser, width, theme, failures) {
  const viewport = {width, height: width <= 390 ? 844 : 900};
  const context = await browser.newContext({viewport, colorScheme: theme});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  let count = 0;
  const visit = async name => {
    await settle(page);
    await openParts(page);
    for (const problem of await scan(page)) failures.push(`${width} ${theme} ${name} | ${problem}`);
    if (shots) {
      fs.mkdirSync(shots, {recursive: true});
      await page.screenshot({path: path.join(shots, `${width}-${theme}-${String(++count).padStart(3, '0')}-${name.replace(/[^a-z0-9]+/gi, '-')}.png`), fullPage: true});
    }
  };
  const hash = async value => {
    await page.evaluate(next => { location.hash = next; }, value);
    await page.waitForFunction(next => location.hash === next, value);
  };

  // Every document in the open workspace in the visual view, and the first
  // of each kind in the other views too.
  const walk = async label => {
    const hrefs = await page.locator('#animus-sidebar a[href*="/workspace/"]').evaluateAll(links => links.map(link => link.getAttribute('href')));
    const kinds = new Set();
    for (const href of hrefs) {
      const match = /#\/workspace\/[^/]+\/([^/?]+)\/([^?]+)$/.exec(href);
      if (!match) continue;
      const views = kinds.has(match[1]) ? ['visual'] : ['visual', 'preview', 'code', 'split'];
      kinds.add(match[1]);
      for (const view of views) {
        await hash(`${href}?view=${view}`);
        await page.locator('#animus-main').waitFor();
        await page.waitForTimeout(view === 'preview' || view === 'split' ? 400 : 100);
        await visit(`${label} ${match[1]} ${decodeURIComponent(match[2])} ${view}`);
      }
    }
  };

  await page.goto(url);
  await page.evaluate(mode => window.localStorage.setItem('animus.theme', mode), theme);
  await page.reload();
  await page.getByRole('heading', {name: 'Start a workspace'}).waitFor();
  await visit('home');

  await page.getByRole('button', {name: 'Create with example packs'}).click();
  await page.getByRole('heading', {name: 'Try it in the browser'}).waitFor();
  const workspace = /#\/workspace\/([^/?]+)/.exec(page.url())[1];
  await visit('workspace');
  await walk('examples');
  await hash(`#/workspace/${workspace}/new`);
  await visit('new document');
  await hash(`#/workspace/${workspace}/new/npc`);
  await visit('new npc');
  await hash(`#/workspace/${workspace}/npcs`);
  await visit('npc list');
  await hash(`#/workspace/${workspace}/sync`);
  await visit('sync');
  await hash('#/citizens');
  await visit('converter');

  for (const folder of folders) {
    await page.goto(url);
    await page.getByRole('heading', {name: 'From a data folder'}).waitFor();
    const chooser = page.waitForEvent('filechooser');
    await page.getByRole('button', {name: 'Open folder'}).first().click();
    await (await chooser).setFiles(folder);
    await page.waitForFunction(() => /#\/workspace\//.test(location.hash));
    await page.locator('#animus-sidebar a[href*="/workspace/"]').first().waitFor({state: 'attached'});
    const label = path.basename(folder);
    await walk(label);
    const opened = /#\/workspace\/([^/?]+)/.exec(page.url())[1];
    await hash(`#/workspace/${opened}/npcs`);
    await visit(`${label} npc list`);
    if (label === 'groups_recordings') {
      await hash(`#/workspace/${opened}/recordings`);
      await page.locator('[data-recording="list"]').waitFor();
      await visit(`${label} recordings list`);
    }
  }

  for (const error of errors) failures.push(`${width} ${theme}: console ${error}`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  const failures = [];
  try {
    for (const width of widths) {
      for (const theme of ['dark', 'light']) await run(browser, width, theme, failures);
    }
  } finally {
    await browser.close();
  }
  if (process.env.ANIMUS_LAYOUT_LIST) {
    for (const failure of failures) console.log(failure);
  }
  assert.deepEqual(failures, [], `${failures.length} layout problems:\n${failures.slice(0, 80).join('\n')}`);
  console.log('layout_browser: ok');
})().catch(error => { console.error(error.message || error); process.exit(1); });
