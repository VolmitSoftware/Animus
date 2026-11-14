// Measures every row of controls on every screen of the built site, at 1440
// and 390 px wide, dark and light, and fails when controls that sit side by
// side differ in height or centre line (a field next to a button, a dropdown
// next to a button, a toolbar, a chip row). Corners are shadcn's own. A row is the controls of one parent box that overlap vertically,
// and also the largest box whose controls all sit on one line; each control
// is measured with getBoundingClientRect.
// Buttons of one rank in a row share one look: border, background, height,
// padding and font weight. A filled button (background and border one
// colour, as a primary or destructive one), one bordered button among
// borderless ones (the row's accent), an icon-only button and a pressed,
// selected or current one stand apart on purpose and are not compared; the
// rest must match, so a borderless text button next to bordered ones
// fails.
// Screens: the start page on a first visit (the pairing field and Pair), a
// pairing link, the example-packs workspace and each of its documents
// (the first of each kind in every view), New document, the NPC list, Send
// to a server, the converter, every document of the gui_editors,
// rig_editor and groups_recordings fixtures and ../schema/fixtures/export,
// then a server paired with the fake server (default --data), its Home and
// each of its documents, and the start page's Continue list.
// Every inspector part is opened first.
// ANIMUS_CONSISTENCY_WIDTHS (optional, comma separated) limits the widths;
// ANIMUS_CONSISTENCY_SCREENS (optional, a regular expression) checks only
// the screens whose name matches; ANIMUS_CONSISTENCY_LIST=1 prints every
// problem; ANIMUS_CONSISTENCY_CENSUS (optional, a file outside the
// repository) receives the count of every control shape seen;
// ANIMUS_SCREENSHOTS (optional, outside the repository) a shot of every
// screen.
//
//   python3 tool/serve_site.py --port <port>
//   dart run tool/fake_server.dart --port=<port> --origin=http://127.0.0.1:<site port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_FAKE_URL=http://127.0.0.1:<port> \
//     PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/consistency_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {documentHrefs, startAction} = require('./support/shell.cjs');

const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;
const censusFile = process.env.ANIMUS_CONSISTENCY_CENSUS;
const folders = [
  ...['gui_editors', 'rig_editor', 'groups_recordings'].map(name => path.resolve(__dirname, 'fixtures', name)),
  path.resolve(__dirname, '../../schema/fixtures/export'),
];
const widths = (process.env.ANIMUS_CONSISTENCY_WIDTHS || '1440,390').split(',').map(Number);
const screens = process.env.ANIMUS_CONSISTENCY_SCREENS ? new RegExp(process.env.ANIMUS_CONSISTENCY_SCREENS) : null;

/**
 * Rows the rule does not bind, each with its reason. A selector names the
 * control; a row is skipped when any of its controls matches.
 */
const exempt = [
  // Timeline keys and events are marks drawn on a lane, sized by the timeline.
  {selector: '.animus-rig-key, .animus-rig-event'},
];

/** Every consistency fault on the page, and the shape of every control. */
async function scan(page, skipped) {
  return page.evaluate(skip => {
    const out = [];
    const census = [];
    const drawn = '.animus-mc-stage, .animus-chest-screen, svg, canvas, .animus-sr-only, [aria-hidden="true"], .animus-boot, [class*="toast"]';
    const selector = 'button, a.arcane-button, [role="combobox"], .arcane-text-input-container, input, select';
    const plain = new Set(['checkbox', 'radio', 'range', 'file', 'hidden', 'color']);
    const shown = el => {
      if (el.closest(drawn)) return false;
      const closed = el.closest('details:not([open])');
      if (closed && !el.closest('summary')) return false;
      const style = getComputedStyle(el);
      if (style.visibility === 'hidden' || style.display === 'none' || parseFloat(style.opacity) === 0) return false;
      if (style.clipPath.startsWith('inset(50%') || /rect\(0(px)?,? 0/.test(style.clip)) return false;
      const box = el.getBoundingClientRect();
      if (box.width <= 1 || box.height <= 1) return false;
      for (let up = el.parentElement; up && up !== document.body; up = up.parentElement) {
        const outer = up.getBoundingClientRect();
        if ((outer.width <= 1 || outer.height <= 1) && getComputedStyle(up).overflow !== 'visible') return false;
      }
      return true;
    };
    const names = el => typeof el.className === 'string'
      ? el.className.trim().split(/\s+/).filter(name => name && !/^(is-|has-)/.test(name)).slice(0, 3).join('.') : '';
    const describe = el => {
      const label = el.getAttribute('aria-label') || el.getAttribute('placeholder')
        || (el.querySelector('input') && (el.querySelector('input').getAttribute('aria-label') || el.querySelector('input').getAttribute('placeholder')))
        || el.textContent.trim().replace(/\s+/g, ' ');
      return `${el.tagName.toLowerCase()}${names(el) ? `.${names(el)}` : ''}${label ? ` "${label.slice(0, 24)}"` : ''}`;
    };
    const round = value => Math.round(value * 2) / 2;
    // Text a person sees: neither an icon-font glyph nor a screen-reader
    // label clipped to a pixel counts, so an icon button stays icon-only.
    const shownText = control => {
      const walker = document.createTreeWalker(control, NodeFilter.SHOW_TEXT);
      for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        if (!/[^\s\uE000-\uF8FF]/.test(node.textContent) || node.parentElement.closest('[data-arcane-semantic-icon], svg')) continue;
        const range = document.createRange();
        range.selectNodeContents(node);
        const box = range.getBoundingClientRect();
        if (box.width > 2 && box.height > 2) return true;
      }
      return false;
    };
    // A button that takes part in the same-rank comparison: one with text,
    // not filled, not pressed, selected or current.
    const ranks = control => {
      // A toggle group's items are a switch, not actions of a rank.
      // An ActionRow's primary is that row's one filled button.
      if (!control.matches('button, a.arcane-button') || control.matches('[role="tab"], [role="combobox"], .arcane-toggle-group-item, [aria-pressed="true"], [aria-selected="true"], [aria-current]:not([aria-current="false"]), [data-action-slot="primary"]')) return false;
      if (!shownText(control)) return false;
      const style = getComputedStyle(control);
      const filled = !/rgba\(.*, 0\)$/.test(style.backgroundColor) && style.backgroundColor === style.borderTopColor;
      return !filled;
    };
    const look = (control, box) => {
      const style = getComputedStyle(control);
      const border = parseFloat(style.borderTopWidth) > 0 && !/rgba\(.*, 0\)$/.test(style.borderTopColor) ? `border ${style.borderTopWidth}` : 'no border';
      return `${border}, background ${style.backgroundColor}, h${round(box.height)}, pad ${style.paddingLeft}, weight ${style.fontWeight}`;
    };

    // Outermost matches only: the input inside a bordered field box is part
    // of that box.
    // Marks that are not controls of a row: the (i) inside a label, a status
    // badge that opens the problems, the library's corner close on a dialog,
    // and tabs, which are a strip of their own (a form's tab row beside a
    // preview's header is not one row of buttons).
    const marks = '[data-animus-info], .ui-badge-button, .arcane-dialog-close, .arcane-sheet-close, [role="tab"]';
    const controls = [...document.querySelectorAll(selector)].filter(el => {
      if (el.tagName === 'INPUT' && plain.has(el.type)) return false;
      if (el.matches(marks)) return false;
      if (el.parentElement.closest(selector)) return false;
      return shown(el);
    });
    const inside = new Map();
    for (const control of controls) {
      for (let up = control.parentElement; up; up = up.parentElement) inside.set(up, (inside.get(up) || 0) + 1);
    }

    const sameLine = (a, b) => Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > Math.min(a.height, b.height) / 2;
    const apart = (a, b) => b.left >= a.right - 1 || b.right <= a.left + 1;
    const within = new Map();
    const rowControls = [];
    for (const control of controls) {
      const box = control.getBoundingClientRect();
      const style = getComputedStyle(control);
      census.push([
        `${control.tagName.toLowerCase()}.${names(control)}`,
        `h${round(box.height)}`, `font ${style.fontSize}`, `radius ${style.borderTopLeftRadius}`,
        `border ${style.borderTopWidth}`, `pad ${style.paddingLeft}`,
      ].join(' | '));
      // Text set as a button (a help tip on a label, a link in a sentence)
      // takes its line's height, not a control's.
      if (style.display.startsWith('inline') && parseFloat(style.borderTopWidth) === 0 && style.backgroundColor === 'rgba(0, 0, 0, 0)' && parseFloat(style.paddingLeft) === 0) continue;
      // A tile or a multi-line button, taller than any control, sets its
      // own height.
      if (box.height > 48) continue;
      const member = {control, box};
      rowControls.push(member);
      for (let up = control.parentElement; up && up !== document.body; up = up.parentElement) {
        if (!within.has(up)) within.set(up, []);
        within.get(up).push(member);
      }
    }

    // Two readings of "side by side", both checked. A row is the controls
    // of one parent box that overlap vertically (each child holding one
    // control), which finds the lines of a wrapped row; and it is the
    // largest box whose controls all sit on one line, which finds a field
    // next to a group of buttons.
    const lines = [];
    const groups = new Map();
    for (const member of rowControls) {
      let item = member.control;
      while (item.parentElement && inside.get(item.parentElement) === 1) item = item.parentElement;
      const row = item.parentElement;
      if (!row) continue;
      if (!groups.has(row)) groups.set(row, []);
      groups.get(row).push(member);
    }
    for (const [row, members] of groups) {
      const left = [...members].sort((a, b) => a.box.top - b.box.top);
      while (left.length) {
        const first = left.shift();
        const line = [first];
        for (let index = 0; index < left.length;) {
          if (sameLine(first.box, left[index].box) && apart(first.box, left[index].box)) line.push(...left.splice(index, 1)); else index++;
        }
        if (line.length > 1) lines.push({row, line});
      }
    }
    const oneLine = box => {
      const members = within.get(box);
      return members.length === inside.get(box) && members.every(member => sameLine(members[0].box, member.box))
        && members.every((member, index) => members.every((other, at) => at === index || apart(member.box, other.box)));
    };
    for (const [box, members] of within) {
      if (members.length < 2 || !oneLine(box)) continue;
      if (box.parentElement && within.has(box.parentElement) && oneLine(box.parentElement)) continue;
      lines.push({row: box, line: [...members]});
    }

    const reported = new Set();
    for (const {row, line} of lines) {
      if (line.some(member => skip.some(rule => member.control.matches(rule)))) continue;
      line.sort((a, b) => a.box.left - b.box.left);
      const heights = line.map(member => round(member.box.height));
      const centres = line.map(member => round(member.box.top + member.box.height / 2));
      const where = `${row.tagName.toLowerCase()}${names(row) ? `.${names(row)}` : ''}`;
      const list = line.map((member, index) => `${describe(member.control)} ${heights[index]}`).join(' + ');
      if (Math.max(...heights) - Math.min(...heights) > 0.5) out.push(`heights differ in ${where}: ${list}`);
      else if (Math.max(...centres) - Math.min(...centres) > 0.5) {
        out.push(`centre lines differ by ${Math.max(...centres) - Math.min(...centres)} px in ${where}: ${list}`);
      }
      let ranked = line.filter(({control}) => ranks(control)).map(({control, box}) => ({control, look: look(control, box)}));
      // One bordered button among borderless ones is that row's accent.
      const bordered = ranked.filter(member => !member.look.startsWith('no border'));
      if (bordered.length === 1 && ranked.length > 1) ranked = ranked.filter(member => member !== bordered[0]);
      const counts = new Map();
      for (const {look: seen} of ranked) counts.set(seen, (counts.get(seen) || 0) + 1);
      if (counts.size > 1) {
        const usual = [...counts].sort((a, b) => b[1] - a[1])[0][0];
        const odd = ranked.filter(member => member.look !== usual && !reported.has(member.control));
        for (const member of odd) reported.add(member.control);
        if (odd.length) out.push(`same-rank buttons differ in ${where}: ${odd.map(member => `${describe(member.control)} (${member.look})`).join(', ')} against ${usual}`);
      }
    }
    return {problems: [...new Set(out)], census};
  }, skipped);
}

async function settle(page) {
  await page.waitForTimeout(150);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

/** Opens every closed part through its summary, as a person does. */
async function openParts(page) {
  for (let round = 0; round < 60; round++) {
    const clicked = await page.evaluate(() => {
      const summary = document.querySelector('#animus-main details:not([open]):not([data-consistency-tried]) > summary');
      if (!summary) return false;
      summary.parentElement.setAttribute('data-consistency-tried', '');
      summary.click();
      return true;
    });
    if (!clicked) return;
    await settle(page);
  }
}

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {method: 'POST', headers: {'content-type': 'application/json'}, body: JSON.stringify(body || {})});
  return response.json();
}

async function run(browser, width, theme, failures, census) {
  const viewport = {width, height: width <= 390 ? 844 : 900};
  const context = await browser.newContext({viewport, colorScheme: theme, hasTouch: width <= 390});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  let count = 0;
  const wanted = name => !screens || screens.test(name);
  const visit = async name => {
    if (!wanted(name)) return;
    await page.mouse.move(0, 0);
    await settle(page);
    await openParts(page);
    const found = await scan(page, exempt.map(rule => rule.selector));
    for (const problem of found.problems) failures.push(`${width} ${theme} ${name} | ${problem}`);
    for (const shape of found.census) census.set(shape, (census.get(shape) || 0) + 1);
    if (shots) {
      fs.mkdirSync(shots, {recursive: true});
      await page.screenshot({path: path.join(shots, `${width}-${theme}-${String(++count).padStart(3, '0')}-${name.replace(/[^a-z0-9]+/gi, '-')}.png`), fullPage: true});
    }
  };
  const hash = async value => {
    await page.evaluate(next => { location.hash = next; }, value);
    await page.waitForFunction(next => location.hash.split('?')[0] === next.split('?')[0], value)
      .catch(async () => { throw new Error(`${width} ${theme}: ${value} settled on ${await page.evaluate(() => location.hash)}`); });
  };

  // Every document the sidebar links to, in the visual view, and the first
  // of each kind in the other views too.
  const walk = async label => {
    const hrefs = await documentHrefs(page);
    const kinds = new Set();
    for (const href of [...new Set(hrefs)]) {
      const match = /#\/workspace\/[^/]+\/([^/?]+)\/([^?]+)$/.exec(href);
      if (!match || match[1] === 'new' || match[1] === 'list') continue;
      const views = kinds.has(match[1]) ? ['visual'] : ['visual', 'preview', 'code', 'split', ...(match[1] === 'behavior' ? ['conversation'] : [])];
      kinds.add(match[1]);
      for (const view of views) {
        if (!wanted(`${label} ${match[1]} ${decodeURIComponent(match[2])} ${view}`)) continue;
        await hash(`${href}?view=${view}`);
        await page.locator('#animus-main').waitFor();
        await page.waitForTimeout(view === 'preview' || view === 'split' ? 400 : 100);
        const opened = await page.evaluate(() => new URLSearchParams(location.hash.split('?')[1] || '').get('view') || 'visual');
        await visit(`${label} ${match[1]} ${decodeURIComponent(match[2])} ${opened}`);
        // An NPC or trait group form is cut into tabs: every tab is a screen.
        if (opened === 'visual' && (match[1] === 'npc' || match[1] === 'group')) {
          const tabs = page.locator('.animus-npc-tabs [role="tab"]');
          const first = await tabs.evaluateAll(all => all.findIndex(tab => tab.getAttribute('aria-selected') === 'true'));
          for (let index = 0; index < await tabs.count(); index++) {
            if (index === first) continue;
            const name = (await tabs.nth(index).innerText()).trim();
            await tabs.nth(index).click();
            await page.locator(`[data-npc-tab="${name.toLowerCase().replace(/[^a-z0-9]+/g, '-')}"]`).waitFor();
            await visit(`${label} ${match[1]} ${decodeURIComponent(match[2])} ${opened} tab ${name}`);
          }
        }
      }
    }
  };

  await page.goto(url);
  await page.evaluate(mode => { window.localStorage.setItem('animus.theme', mode); window.localStorage.setItem('animus.mode', 'advanced'); }, theme);
  await page.reload();
  await page.getByRole('heading', {name: 'Animus web editor'}).waitFor();
  await page.getByRole('textbox', {name: 'Pairing link'}).waitFor();
  await visit('home');

  await page.getByRole('button', {name: 'Open examples'}).click();
  await page.locator('[data-testid="home-count"]').first().waitFor();
  const workspace = /#\/workspace\/([^/?]+)/.exec(page.url())[1];
  await visit('workspace');
  await walk('examples');
  for (const [name, route] of [
    ['new document', `#/workspace/${workspace}/new`], ['new npc', `#/workspace/${workspace}/new/npc`],
    ['npc list', `#/workspace/${workspace}/npcs`], ['list shops', `#/workspace/${workspace}/list/shop`], ['list movement', `#/workspace/${workspace}/list/route`], ['sync', `#/workspace/${workspace}/sync`], ['converter', '#/citizens'],
  ]) {
    if (!wanted(name)) continue;
    await hash(route);
    await visit(name);
  }

  for (const folder of folders) {
    await page.goto(url);
    await page.getByRole('heading', {name: 'Continue'}).waitFor();
    const chooser = page.waitForEvent('filechooser');
    await startAction(page, 'Open folder');
    await (await chooser).setFiles(folder);
    await page.waitForFunction(() => /#\/workspace\//.test(location.hash));
    await page.locator('.ui-save-bar').waitFor();
    const label = path.basename(folder);
    await walk(label);
    const opened = /#\/workspace\/([^/?]+)/.exec(page.url())[1];
    await hash(`#/workspace/${opened}/npcs`);
    await visit(`${label} npc list`);
    if (label === 'groups_recordings') {
      await hash(`#/workspace/${opened}/recordings`);
      await page.locator('#movement-list').waitFor();
      await visit(`${label} recordings list`);
    }
  }

  // A pairing link, then the paired server.
  const {code} = await control('pair?paths=both', {});
  await page.goto(`${url}/#/pair/${code}`);
  await page.getByTestId('pair-submit').waitFor();
  await visit('pair');
  await page.getByTestId('pair-submit').click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await page.locator('[data-testid="home-count"]').first().waitFor();
  await visit('server');
  // The workspace fills from the catalog by itself: wait for Home's NPC count
  // (the nav shows no counts as an icon rail).
  const server = await page.evaluate(() => location.hash.split('/')[2]);
  await hash(`#/workspace/server-${server}`);
  await page.waitForFunction(() => /^[1-9]\d*$/.test(document.querySelector('.ui-home-card[data-job="npcs"] [data-testid="home-count"]')?.textContent?.trim() || ''));
  await visit('server catalog opened');
  await walk('server');
  await page.goto(`${url}/#/`);
  await page.getByRole('heading', {name: 'Continue'}).waitFor();
  await visit('home paired');

  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  const failures = [];
  const census = new Map();
  try {
    for (const width of widths) {
      for (const theme of ['dark', 'light']) {
        const started = Date.now();
        const before = failures.length;
        await run(browser, width, theme, failures, census);
        console.log(`consistency_browser: ${width} ${theme} ${failures.length - before} problems, ${Math.round((Date.now() - started) / 1000)} s`);
      }
    }
  } finally {
    await browser.close();
  }
  if (censusFile) {
    fs.writeFileSync(censusFile, [...census].sort((a, b) => a[0].localeCompare(b[0])).map(([shape, seen]) => `${String(seen).padStart(6)}  ${shape}`).join('\n'));
  }
  if (process.env.ANIMUS_CONSISTENCY_LIST) {
    for (const failure of failures) console.log(failure);
  }
  assert.deepEqual(failures, [], `${failures.length} consistency problems:\n${failures.slice(0, 80).join('\n')}`);
  console.log('consistency_browser: ok');
})().catch(error => { console.error(error.message || error); process.exit(1); });
