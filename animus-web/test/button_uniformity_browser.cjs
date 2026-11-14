// Button sizes, heights and order on every main screen, at 1600, 768 and
// 390 px. The allowed heights are the library's own: a default and a small
// button, cloned into a box outside every screen and measured there. A
// screen fails on:
//   - a button, field, dropdown or toggle whose height is not one of them;
//   - controls of one row with different heights (a button beside a taller
//     field included);
//   - a group of buttons with uneven gaps;
//   - a primary button that is not the last control of its row, or two
//     primary buttons in one row;
//   - a row of controls that wrapped onto a second line;
//   - icons inside buttons at more than one size;
//   - at 760 px and below, a dialog or form footer whose buttons are not
//     stacked full width with the primary on top;
//   - a control cut off by the tab strip, switch, toolbar or bar holding it,
//     or a tab or switch label the row squeezed;
//   - a top bar wider than the screen or with parts over each other;
//   - a blank page heading.
// A row is the controls laid out by one flex row, through inline wrappers
// and through one-line boxes inside that row. Tabs, menu items, options,
// switches, checkboxes and tree rows are not buttons here. The exemptions
// below are not buttons of a row either, each with its reason.
// Screens: the start page on a first visit and with something to continue,
// the example-packs workspace and each of its documents (the first of each
// kind in every view), New document, the lists, Send to a server, the
// converter, the pair page, the shortcuts dialog, a delete confirmation,
// the nav drawer on phones, the documents of the gui_editors, rig_editor
// and groups_recordings fixtures and ../schema/fixtures/export, then a
// server paired with the fake server, its Home, Review (empty and with a
// change) and its documents. An NPC or trait group is checked on every tab
// of its form. The server goes by a long name, and after its walk gains
// real-shaped data (support/real_shape.cjs): Movement with 40 more routes,
// an NPC named only by a colour code and the GUIs list are checked again.
// ANIMUS_BUTTONS_WIDTHS (comma separated) limits the widths;
// ANIMUS_BUTTONS_SCREENS (a regular expression) checks only the screens
// whose name matches; ANIMUS_BUTTONS_WITHIN (a CSS selector list) checks
// only the controls inside those parts (the frame's, say);
// ANIMUS_BUTTONS_LIST=1 prints every problem;
// ANIMUS_SCREENSHOTS (outside the repository) receives a shot per screen.
//
//   python3 tool/serve_site.py --port <port>
//   dart run tool/fake_server.dart --port=<port> --origin=http://127.0.0.1:<site port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_FAKE_URL=http://127.0.0.1:<port> \
//     PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/button_uniformity_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {documentHrefs, startAction} = require('./support/shell.cjs');
const {nameServer, addRealShape, realShapeScreens} = require('./support/real_shape.cjs');

const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;
const widths = (process.env.ANIMUS_BUTTONS_WIDTHS || '1600,768,390').split(',').map(Number);
const screens = process.env.ANIMUS_BUTTONS_SCREENS ? new RegExp(process.env.ANIMUS_BUTTONS_SCREENS) : null;
const within = process.env.ANIMUS_BUTTONS_WITHIN || null;
const folders = [
  ...['gui_editors', 'rig_editor', 'groups_recordings'].map(name => path.resolve(__dirname, 'fixtures', name)),
  path.resolve(__dirname, '../../schema/fixtures/export'),
];

/** Controls that are not buttons of a row, each with its reason. */
const exempt = [
  // The (i) beside a label is a help mark inside the label's text.
  '[data-animus-info]',
  // The library's own corner close mark on a dialog or sheet.
  '.arcane-dialog-close', '.arcane-sheet-close',
  // Inventory and chest slots draw the game's 18 px grid at the editor's scale.
  '.animus-item-slot', '.animus-chest-slot',
  // Marks drawn on the stage, the route grid and the rig (SVG handles).
  'svg',
  // Toasts are passing notices with the library's own close mark.
  '[class*="toast"]',
  // A status badge that opens the problem list keeps the badge's size.
  '.ui-badge-button',
  // Timeline keys and events are marks drawn on a lane, sized by the timeline.
  '.animus-rig-key', '.animus-rig-event',
  // A trading screen's offer rows are part of the Minecraft window drawing.
  '.animus-merchant-offer',
  // A text animation's colour frames are a palette of swatches that wraps.
  '.animus-anim-swatch',
  // The library's command palette and dropdown search fields keep its own size.
  '[data-arcane-command-input]',
];

/** The library's default and small button heights, measured outside every screen. */
async function reference(page) {
  return page.evaluate(() => {
    const root = document.getElementById('arcane-root') || document.body;
    const box = document.createElement('div');
    box.style.cssText = 'position:absolute;left:-10000px;top:0;display:block';
    root.appendChild(box);
    const heights = {};
    for (const size of ['md', 'sm']) {
      const source = document.querySelector(`.arcane-button[data-size="${size}"]`);
      if (!source) continue;
      const clone = source.cloneNode(true);
      clone.removeAttribute('id');
      box.appendChild(clone);
      heights[size] = Math.round(clone.getBoundingClientRect().height * 10) / 10;
    }
    box.remove();
    return heights;
  });
}

/** Every problem on the page. */
async function scan(page, allowed, skip) {
  return page.evaluate(({allowed, skip, within}) => {
    const problems = [];
    const controlSelector = 'button, [role="button"], select, input:not([type="checkbox"]):not([type="radio"]):not([type="range"]):not([type="hidden"]):not([type="color"]):not([type="file"])';
    const notButtons = '[role="tab"], [role="menuitem"], [role="menuitemradio"], [role="menuitemcheckbox"], [role="option"], [role="switch"], [role="checkbox"], [role="treeitem"], summary, [data-arcane-command-item]';
    const shown = el => {
      if (!el.checkVisibility({opacityProperty: true, visibilityProperty: true})) return false;
      const r = el.getBoundingClientRect();
      if (r.width < 2 || r.height < 2) return false;
      // Parked above or left of the page until focused (the skip link).
      if (r.bottom <= 0 || r.right <= 0) return false;
      return !el.closest('[aria-hidden="true"], .animus-sr-only, [inert]');
    };
    const name = el => (el.getAttribute('aria-label') || el.textContent || el.getAttribute('placeholder') || el.value || '').trim().replace(/\s+/g, ' ').slice(0, 30);
    const describe = el => `${el.tagName.toLowerCase()}${el.classList.length ? '.' + [...el.classList].filter(c => c).slice(0, 2).join('.') : ''} "${name(el)}" ${Math.round(el.getBoundingClientRect().height)}`;
    const controls = [...document.querySelectorAll(controlSelector)].filter(el => shown(el) && !el.matches(notButtons) && !el.closest(notButtons) && !el.matches(skip) && !el.closest(skip) && (!within || el.closest(within)));
    const isFlexRow = el => {
      const cs = getComputedStyle(el);
      return (cs.display === 'flex' || cs.display === 'inline-flex') && cs.flexDirection.startsWith('row');
    };
    const rowOf = el => {
      let node = el;
      let root = null;
      const height = el.getBoundingClientRect().height;
      while (node.parentElement && node.parentElement !== document.body) {
        const parent = node.parentElement;
        const display = getComputedStyle(parent).display;
        if (isFlexRow(parent)) {
          root = parent;
          node = parent;
          continue;
        }
        if (display === 'inline' || display === 'contents' || display === 'inline-block') {
          node = parent;
          continue;
        }
        // A one-line box laid out inside a flex row is part of that row.
        if (parent.parentElement && isFlexRow(parent.parentElement) && parent.getBoundingClientRect().height <= height * 1.5 + 1) {
          node = parent;
          continue;
        }
        break;
      }
      return root;
    };

    // Heights.
    for (const el of controls) {
      const h = el.getBoundingClientRect().height;
      if (!allowed.some(a => Math.abs(a - h) <= 0.75)) problems.push(`height ${Math.round(h * 10) / 10} not ${allowed.join(' or ')}: ${describe(el)}`);
    }

    // Rows.
    const rows = new Map();
    for (const el of controls) {
      const root = rowOf(el);
      if (!root) continue;
      if (!rows.has(root)) rows.set(root, []);
      rows.get(root).push(el);
    }
    const where = el => `${el.tagName.toLowerCase()}${el.classList.length ? '.' + [...el.classList].slice(0, 2).join('.') : ''}`;
    for (const [root, items] of rows) {
      if (items.length < 2) continue;
      const rects = items.map(el => el.getBoundingClientRect());
      const heights = rects.map(r => r.height);
      const low = Math.min(...heights);
      if (Math.max(...heights) - low > 1) problems.push(`heights differ in ${where(root)}: ${items.map(describe).join(' + ')}`);
      const tops = rects.map(r => r.top);
      if (Math.max(...tops) - Math.min(...tops) > low / 2) problems.push(`row wraps in ${where(root)}: ${items.map(describe).join(' + ')}`);
      const primaries = items.filter(el => el.matches('[data-variant="primary"], [data-action-slot="primary"]'));
      if (primaries.length > 1) problems.push(`two primary buttons in ${where(root)}: ${primaries.map(describe).join(' + ')}`);
      for (const primary of primaries) {
        const left = primary.getBoundingClientRect().left;
        const after = items.filter(el => el !== primary && el.getBoundingClientRect().left > left + 1);
        if (after.length) problems.push(`primary not last in ${where(root)}: ${describe(primary)} before ${after.map(describe).join(' + ')}`);
      }
    }

    // Gaps inside a group: a flex row whose children are all one control each.
    const groups = new Set();
    for (const el of controls) {
      let child = el;
      while (child.parentElement && !isFlexRow(child.parentElement)) child = child.parentElement;
      if (child.parentElement) groups.add(child.parentElement);
    }
    for (const group of groups) {
      // A toggle group's items are joined, not spaced.
      if (group.querySelector('.arcane-toggle-group-item')) continue;
      const kids = [...group.children].filter(shown);
      if (kids.length < 3) continue;
      const inner = kids.map(kid => (kid.matches(controlSelector) ? kid : kid.querySelectorAll(controlSelector).length === 1 ? kid.querySelector(controlSelector) : null));
      if (inner.some(el => !el || !controls.includes(el))) continue;
      const rects = inner.map(el => el.getBoundingClientRect()).sort((a, b) => a.left - b.left);
      if (Math.max(...rects.map(r => r.top)) - Math.min(...rects.map(r => r.top)) > 4) continue;
      const gaps = rects.slice(1).map((r, i) => Math.round(r.left - rects[i].right));
      if (Math.max(...gaps) - Math.min(...gaps) > 1) problems.push(`uneven gaps ${gaps.join('/')} in ${where(group)}: ${inner.map(describe).join(' + ')}`);
    }

    // Footers on phones: stacked, full width, primary on top.
    if (window.innerWidth <= 760) {
      for (const footer of document.querySelectorAll('[data-action-row="footer"]')) {
        const buttons = controls.filter(el => footer.contains(el));
        if (buttons.length < 2) continue;
        const end = footer.getBoundingClientRect();
        const narrow = buttons.filter(el => el.getBoundingClientRect().width < end.width - 2);
        if (narrow.length) problems.push(`footer not full width in ${where(footer)}: ${narrow.map(describe).join(' + ')}`);
        const primary = buttons.find(el => el.matches('[data-action-slot="primary"]'));
        if (primary && buttons.some(el => el !== primary && el.getBoundingClientRect().top < primary.getBoundingClientRect().top)) problems.push(`footer primary not on top in ${where(footer)}: ${describe(primary)}`);
      }
    }

    // Icon sizes inside buttons.
    const sizes = new Map();
    for (const el of controls) {
      for (const icon of el.querySelectorAll('i, svg')) {
        if (!shown(icon)) continue;
        const w = Math.round(icon.getBoundingClientRect().width);
        if (!sizes.has(w)) sizes.set(w, describe(el));
      }
    }
    if (sizes.size > 1) problems.push(`icons at ${[...sizes.keys()].sort((a, b) => a - b).join(', ')} px: ${[...sizes].map(([w, el]) => `${w} in ${el}`).join('; ')}`);

    // Controls cut off by the strip or bar holding them: a tab strip
    // squeezed to a line, a toggle group scrolled past its last item.
    const strips = '.ui-tabs, .ui-toggle-choice, [role="tablist"], [role="toolbar"], [role="group"], .ui-actions, .ui-topbar, .ui-save-bar';
    const cuttable = [...document.querySelectorAll('button, [role="tab"], [role="button"], select, input')].filter(el => shown(el) && !el.matches(skip) && !el.closest(skip) && (!within || el.closest(within)));
    for (const el of cuttable) {
      // A switch or tab whose label the row squeezed ("Trad" for "Trading screen").
      if (el.closest('.ui-tabs, .ui-toggle-choice, [role="tablist"]') && el.scrollWidth > el.clientWidth + 1) problems.push(`label cut: ${describe(el)} shows ${el.clientWidth} of ${el.scrollWidth} px`);
      const box = el.getBoundingClientRect();
      for (let up = el.parentElement; up && up !== document.body; up = up.parentElement) {
        const style = getComputedStyle(up);
        if (style.overflowX === 'visible' && style.overflowY === 'visible') continue;
        if (!up.matches(strips)) break;
        const outer = up.getBoundingClientRect();
        const cutX = Math.max(0, outer.left - box.left) + Math.max(0, box.right - outer.right);
        const cutY = Math.max(0, outer.top - box.top) + Math.max(0, box.bottom - outer.bottom);
        if (cutX > 1 || cutY > 1) problems.push(`cut off by ${where(up)}: ${describe(el)} loses ${Math.round(cutX)}x${Math.round(cutY)} px`);
        break;
      }
    }

    // The top bar: one line, nothing sticking out of it or over a neighbour.
    const bar = document.querySelector('.ui-topbar');
    if (bar && shown(bar)) {
      if (bar.scrollWidth > bar.clientWidth + 1) problems.push(`top bar overflows by ${bar.scrollWidth - bar.clientWidth} px`);
      const parts = [...bar.querySelectorAll('button, a, [data-testid="context-state"]')].filter(el => shown(el) && !el.parentElement.closest('button, a, [data-testid="context-state"]'));
      for (let i = 0; i < parts.length; i++) {
        const a = parts[i].getBoundingClientRect();
        if (a.left < bar.getBoundingClientRect().left - 1 || a.right > bar.getBoundingClientRect().right + 1) problems.push(`top bar part outside the bar: ${describe(parts[i])}`);
        for (let j = i + 1; j < parts.length; j++) {
          const b = parts[j].getBoundingClientRect();
          const x = Math.min(a.right, b.right) - Math.max(a.left, b.left);
          const y = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
          if (x > 1 && y > 1) problems.push(`top bar parts overlap: ${describe(parts[i])} and ${describe(parts[j])} by ${Math.round(x)} px`);
        }
      }
    }

    // A page heading always says something.
    const heading = document.getElementById('animus-main-heading');
    if (heading && heading.isConnected && !heading.closest('[hidden], [aria-hidden="true"]') && !heading.textContent.trim()) problems.push('blank page heading');
    return problems;
  }, {allowed, skip, within});
}

async function settle(page) {
  await page.waitForTimeout(150);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {method: 'POST', headers: {'content-type': 'application/json'}, body: JSON.stringify(body || {})});
  return response.json();
}

async function run(browser, width, failures, allowedSeen) {
  const viewport = {width, height: width <= 390 ? 844 : 900};
  const context = await browser.newContext({viewport, colorScheme: 'dark', hasTouch: width <= 390});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  let count = 0;
  let allowed = null;
  const wanted = name => !screens || screens.test(name);
  const visit = async name => {
    if (!wanted(name)) return;
    await page.mouse.move(0, 0);
    await settle(page);
    for (const problem of await scan(page, allowed, exempt.join(', '))) failures.push(`${width} ${name} | ${problem}`);
    if (shots) {
      fs.mkdirSync(shots, {recursive: true});
      await page.screenshot({path: path.join(shots, `buttons-${width}-${String(++count).padStart(3, '0')}-${name.replace(/[^a-z0-9]+/gi, '-')}.png`)});
    }
  };
  const hash = async value => {
    await page.evaluate(next => { location.hash = next; }, value);
    await page.waitForFunction(next => location.hash.split('?')[0] === next.split('?')[0], value)
      .catch(async () => { throw new Error(`${width}: ${value} settled on ${await page.evaluate(() => location.hash)}`); });
  };
  const walk = async label => {
    const hrefs = await documentHrefs(page);
    const kinds = new Set();
    for (const href of [...new Set(hrefs)]) {
      const match = /#\/workspace\/[^/]+\/([^/?]+)\/([^?]+)$/.exec(href);
      if (!match || match[1] === 'new' || match[1] === 'list') continue;
      const views = kinds.has(match[1]) ? ['visual'] : ['visual', 'code', 'split', ...(match[1] === 'behavior' ? ['conversation'] : [])];
      kinds.add(match[1]);
      for (const view of views) {
        if (!wanted(`${label} ${match[1]} ${decodeURIComponent(match[2])} ${view}`)) continue;
        await hash(`${href}?view=${view}`);
        await page.locator('#animus-main').waitFor();
        await page.waitForTimeout(view === 'split' ? 400 : 100);
        const opened = await page.evaluate(() => new URLSearchParams(location.hash.split('?')[1] || '').get('view') || 'visual');
        await visit(`${label} ${match[1]} ${decodeURIComponent(match[2])} ${opened}`);
        // An NPC or trait group form is cut into tabs: every tab is a screen.
        if (opened === 'visual' && (match[1] === 'npc' || match[1] === 'group')) {
          const tabs = page.locator('.animus-npc-tabs [role="tab"]');
          const first = await tabs.evaluateAll(all => all.findIndex(tab => tab.getAttribute('aria-selected') === 'true'));
          for (let index = 0; index < await tabs.count(); index++) {
            if (index === first) continue;
            const tab = tabs.nth(index);
            const name = (await tab.innerText()).trim();
            await tab.click();
            await page.locator(`[data-npc-tab="${name.toLowerCase().replace(/[^a-z0-9]+/g, '-')}"]`).waitFor();
            await visit(`${label} ${match[1]} ${decodeURIComponent(match[2])} ${opened} tab ${name}`);
          }
        }
      }
    }
  };

  await page.goto(url);
  await page.evaluate(() => window.localStorage.setItem('animus.mode', 'advanced'));
  await page.reload();
  await page.getByRole('heading', {name: 'Animus web editor'}).waitFor();
  // The first visit has both library sizes (Pair, and the top bar's Search).
  const first = await reference(page);
  if (first.md && first.sm) {
    allowed = [first.md, first.sm];
    await visit('start');
  }
  await page.getByRole('button', {name: 'Open examples'}).click();
  await page.locator('[data-testid="home-count"]').first().waitFor();
  const workspace = /#\/workspace\/([^/?]+)/.exec(page.url())[1];

  // The reference: the list page has the header's default New button and
  // the top bar's small ones.
  await hash(`#/workspace/${workspace}/list/shop`);
  await page.locator('[data-testid="list-new"]').waitFor();
  const heights = await reference(page);
  assert.ok(heights.md && heights.sm, `${width}: no default or small library button to measure: ${JSON.stringify(heights)}`);
  allowed = [heights.md, heights.sm];
  allowedSeen.add(allowed.join('/'));

  await page.goto(url);
  await page.getByRole('heading', {name: 'Continue'}).waitFor();
  await visit('home');
  await hash(`#/workspace/${workspace}`);
  await page.locator('[data-testid="home-count"]').first().waitFor();
  await visit('workspace');
  await walk('examples');
  for (const [name, route] of [
    ['new document', `#/workspace/${workspace}/new`], ['new npc', `#/workspace/${workspace}/new/npc`],
    ['npc list', `#/workspace/${workspace}/npcs`], ['list shops', `#/workspace/${workspace}/list/shop`], ['list movement', `#/workspace/${workspace}/list/route`],
    ['sync', `#/workspace/${workspace}/sync`], ['converter', '#/citizens'],
  ]) {
    if (!wanted(name)) continue;
    await hash(route);
    await visit(name);
  }
  if (wanted('shortcuts dialog')) {
    await hash(`#/workspace/${workspace}`);
    await page.locator('[data-testid="home-count"]').first().waitFor();
    await page.locator('body').click({position: {x: 1, y: viewport.height - 1}});
    await page.keyboard.press('?');
    await page.getByRole('dialog', {name: 'Keyboard shortcuts'}).waitFor();
    await visit('shortcuts dialog');
    await page.keyboard.press('Escape');
  }
  if (wanted('drawer') && width <= 760) {
    await hash(`#/workspace/${workspace}`);
    await page.locator('#ui-nav-button').click();
    await page.locator('#ui-nav-drawer .arcane-sidebar').waitFor();
    await visit('drawer');
    await page.keyboard.press('Escape');
  }
  if (wanted('delete confirm')) {
    await page.goto(url);
    await page.getByRole('heading', {name: 'Continue'}).waitFor();
    await page.locator('.ui-continue-row').first().getByRole('button', {name: 'More actions'}).click();
    await page.getByRole('menuitem', {name: 'Delete workspace'}).click();
    await page.locator('[role="alertdialog"]:visible, [role="dialog"]:visible').first().waitFor();
    await visit('delete confirm');
    await page.keyboard.press('Escape');
  }

  for (const folder of folders) {
    const label = path.basename(folder);
    if (screens && !screens.test(label)) continue;
    await page.goto(url);
    await page.getByRole('heading', {name: 'Continue'}).waitFor();
    const chooser = page.waitForEvent('filechooser');
    await startAction(page, 'Open folder');
    await (await chooser).setFiles(folder);
    await page.waitForFunction(() => /#\/workspace\//.test(location.hash));
    await page.locator('.ui-save-bar').waitFor();
    await walk(label);
  }

  if (!screens || screens.test('server') || screens.test('pair') || screens.test('review')) {
    await nameServer(fake);
    const {code} = await control('pair?paths=both', {});
    await page.goto(`${url}/#/pair/${code}`);
    await page.getByTestId('pair-submit').waitFor();
    await visit('pair');
    await page.getByTestId('pair-submit').click();
    await page.waitForFunction(() => location.hash.startsWith('#/server/'));
    await page.locator('[data-testid="home-count"]').first().waitFor();
    await visit('server');
    // The server's workspace, once its catalog has filled Home's counts
    // (the nav shows none as an icon rail).
    const server = await page.evaluate(() => location.hash.split('/')[2]);
    await hash(`#/workspace/server-${server}`);
    await page.locator('.ui-save-bar').waitFor();
    await page.waitForFunction(() => /^[1-9]\d*$/.test(document.querySelector('.ui-home-card[data-job="npcs"] [data-testid="home-count"]')?.textContent?.trim() || ''));
    if (wanted('review') || wanted('review changes')) {
      const review = page.locator('#ui-review [data-testid="review"]');
      // Up to date, the bar offers no Review; a change, through the Code view.
      await hash(`#/workspace/server-${server}/npc/${encodeURIComponent('examples/greeter')}?view=code`);
      const area = page.locator('.animus-code textarea').first();
      await area.waitFor();
      const json = JSON.parse(await area.inputValue());
      await area.fill(JSON.stringify({...json, name: 'Greeter Prime'}, null, 2));
      await page.getByRole('button', {name: 'Use this code'}).click();
      await page.locator('.ui-save-bar [data-save-action="review"]').click();
      await review.getByTestId('review-changes').waitFor();
      await visit('review changes');
      await page.keyboard.press('Escape');
      await review.waitFor({state: 'hidden'});
    }
    await walk('server');
    // Real-shaped data, added live: the server workspace hears of it at once.
    await addRealShape(fake);
    for (const [name, route] of realShapeScreens(`server-${server}`)) {
      if (!wanted(name)) continue;
      await hash(route);
      await page.locator('#animus-main-heading').waitFor({state: 'attached'});
      // Movement counts the walkers once the catalog has them (the list itself is windowed).
      if (name.endsWith('movement')) await page.waitForFunction(() => /\b([4-9]\d|\d{3,}) routes\b/.test(document.getElementById('animus-main')?.innerText || ''), null, {timeout: 20000})
        .catch(async () => { throw new Error(`Movement never listed the walkers: ${(await page.locator('#animus-main').innerText()).slice(0, 300)}`); });
      await settle(page);
      await visit(name);
    }
  }
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  const failures = [];
  const allowedSeen = new Set();
  try {
    for (const width of widths) {
      const started = Date.now();
      const before = failures.length;
      await run(browser, width, failures, allowedSeen);
      console.log(`button_uniformity_browser: ${width} ${failures.length - before} problems, ${Math.round((Date.now() - started) / 1000)} s`);
    }
  } finally {
    await browser.close();
  }
  console.log(`button_uniformity_browser: library heights ${[...allowedSeen].join(', ')} px`);
  if (process.env.ANIMUS_BUTTONS_LIST) {
    for (const failure of failures) console.log(failure);
  }
  assert.deepEqual(failures, [], `${failures.length} button problems:\n${failures.slice(0, 80).join('\n')}`);
  console.log('button_uniformity_browser: ok');
})().catch(error => { console.error(error.message || error); process.exit(1); });
