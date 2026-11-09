// Walks every page of the built site at 1440x900 and 390x844 in dark and
// light and fails on a shape the editor does not use: a non-zero
// border-radius outside the round-by-meaning exceptions below, any inset or
// offset box-shadow (only flat, zero-offset rings are allowed), a border on
// one side only in the pink accent, and an accent gradient. ::before and
// ::after are checked too, and one control of each kind again with :hover,
// :focus-visible and :active forced on it. It also checks that buttons share the heights of
// the button system and that phone controls keep a 44 px target.
// Pages: home, the example-packs workspace and every document kind in it
// with each view, the gui_editors, rig_editor and groups_recordings fixtures
// (that one also with its NPC list and group form, and its recordings
// list), New document,
// Send to a server, the NPC list, the converter, a pairing link, the command
// palette, the top bar menus and a delete confirmation.
// ANIMUS_SCREENSHOTS (optional, outside the repository) receives a shot of
// every page per width and theme.
//
//   python3 tool/serve_site.py --port <port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/shape_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {isCompact} = require('./support/shell.cjs');

const url = process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })();
const shots = process.env.ANIMUS_SCREENSHOTS;
const folders = ['fixtures/gui_editors', 'fixtures/rig_editor', 'fixtures/groups_recordings'].map(folder => path.resolve(__dirname, folder));

// Round by meaning, and the only places a border-radius is allowed. Empty:
// the rig gizmo's handles are round, but they are SVG circles on the 3D
// stage, not CSS boxes.
const roundExceptions = [];

// One control of each kind is also checked with :hover, :focus-visible and
// :active forced on it, so a state cannot bring back an underline or a ring.
const stateTargets = [
  ".arcane-button[data-variant='primary']",
  ".arcane-button[data-variant='secondary']",
  ".arcane-button[data-variant='outline']",
  ".arcane-button[data-variant='ghost']",
  ".arcane-button[data-variant='destructive']",
  ".arcane-button[data-size^='icon']",
  '.animus-tab',
  '.animus-tab[aria-selected="true"]',
  '.animus-menu-item',
  '.animus-tree-item',
  '.animus-side-link',
  'input[type="text"], input:not([type])',
  'input[type="checkbox"]',
  'input[type="range"]',
  'button[role="combobox"]',
  'input[role="combobox"]',
  'textarea',
  'summary',
  '.arcane-toast-close',
];

// Button heights in px: small and default, plus the 44 px phone target.
const buttonHeights = {desktop: [28, 36], phone: [44]};

/** Every shape violation on the page, as readable strings. */
async function scan(page, only = null) {
  return page.evaluate(({exceptions, only}) => {
    const pinks = ['rgb(255, 95, 168)', 'rgb(224, 64, 140)', 'rgb(255, 179, 214)', 'rgb(194, 24, 91)', 'rgb(160, 18, 74)'];
    const describe = el => {
      const id = el.id ? `#${el.id}` : '';
      const classes = typeof el.className === 'string' && el.className.trim() ? `.${el.className.trim().split(/\s+/).slice(0, 3).join('.')}` : '';
      return `${el.tagName.toLowerCase()}${id}${classes}`;
    };
    const shadowFails = value => {
      if (!value || value === 'none') return false;
      // Split on commas outside parentheses.
      const parts = value.split(/,(?![^(]*\))/);
      return parts.some(part => {
        if (/\binset\b/.test(part)) return true;
        const lengths = part.replace(/rgba?\([^)]*\)/g, '').match(/-?[\d.]+px/g) || [];
        const [x = '0px', y = '0px', blur = '0px'] = lengths;
        return parseFloat(x) !== 0 || parseFloat(y) !== 0 || parseFloat(blur) !== 0;
      });
    };
    const out = [];
    const check = (el, style, label) => {
      const radius = ['borderTopLeftRadius', 'borderTopRightRadius', 'borderBottomLeftRadius', 'borderBottomRightRadius']
          .map(key => style[key]).filter(value => value && !/^0px( 0px)?$/.test(value));
      if (radius.length && !exceptions.some(selector => el.matches(selector))) out.push(`${label} radius ${radius[0]}`);
      if (shadowFails(style.boxShadow)) out.push(`${label} box-shadow ${style.boxShadow}`);
      const sides = ['Top', 'Right', 'Bottom', 'Left'].map(side => ({
        width: parseFloat(style[`border${side}Width`]) || 0,
        colour: style[`border${side}Color`],
        style: style[`border${side}Style`],
      })).map(side => ({...side, width: side.style === 'none' || side.style === 'hidden' ? 0 : side.width}));
      const uniform = sides.every(side => side.width === sides[0].width && side.colour === sides[0].colour);
      if (!uniform && sides.some(side => side.width > 0 && pinks.some(pink => side.colour.startsWith(pink.slice(0, -1))))) {
        out.push(`${label} one-sided accent border ${sides.map(side => `${side.width}px ${side.colour}`).join(' / ')}`);
      }
      if (/gradient/.test(style.backgroundImage) && pinks.some(pink => style.backgroundImage.includes(pink.slice(4, -1)))) {
        out.push(`${label} accent gradient ${style.backgroundImage.slice(0, 80)}`);
      }
    };
    for (const el of document.querySelectorAll(only || 'body *')) {
      if (el.closest('svg') && el.tagName.toLowerCase() !== 'svg') continue;
      check(el, getComputedStyle(el), describe(el));
      for (const pseudo of ['::before', '::after']) {
        const style = getComputedStyle(el, pseudo);
        if (style.content === 'none' || style.content === 'normal') continue;
        check(el, style, `${describe(el)}${pseudo}`);
      }
    }
    return [...new Set(out)];
  }, {exceptions: roundExceptions, only});
}

/** The first of each [stateTargets] with each interaction state forced on it. */
async function scanStates(page, cdp) {
  const out = [];
  for (const target of stateTargets) {
    const found = await page.evaluate(selector => {
      const el = [...document.querySelectorAll(selector)].find(candidate => candidate.getClientRects().length);
      if (!el) return false;
      el.setAttribute('data-shape-probe', '');
      return true;
    }, target);
    if (!found) continue;
    for (const states of [['hover'], ['focus', 'focus-visible'], ['active'], ['hover', 'focus', 'focus-visible']]) {
      // The node id goes stale when the app re-renders, so it is looked up
      // again for each state and once more if the page moved underneath.
      for (let attempt = 0; ; attempt++) {
        try {
          const {root} = await cdp.send('DOM.getDocument', {depth: 0});
          const {nodeId} = await cdp.send('DOM.querySelector', {nodeId: root.nodeId, selector: '[data-shape-probe]'});
          if (!nodeId) break;
          await cdp.send('CSS.forcePseudoState', {nodeId, forcedPseudoClasses: states});
          for (const problem of await scan(page, '[data-shape-probe]')) out.push(`${target} :${states.join(':')} ${problem}`);
          await cdp.send('CSS.forcePseudoState', {nodeId, forcedPseudoClasses: []});
          break;
        } catch (error) {
          if (attempt >= 3) throw error;
        }
      }
    }
    await page.evaluate(() => document.querySelector('[data-shape-probe]')?.removeAttribute('data-shape-probe'));
  }
  return out;
}

/** Buttons whose height is outside the system, and phone targets under 44 px. */
async function sizes(page, compact) {
  return page.evaluate(({allowed, compact}) => {
    const out = [];
    for (const el of document.querySelectorAll('button, .arcane-button, [role="button"], [role="tab"], [role="combobox"], input:not([type="checkbox"]):not([type="radio"]):not([type="range"]):not([type="file"]):not([type="hidden"]):not([type="color"])')) {
      const box = el.getBoundingClientRect();
      if (!box.width || !box.height || getComputedStyle(el).visibility === 'hidden') continue;
      // Chest slots, stage tools and tree rows are sized by their grid, not the button system.
      if (el.closest('.animus-chest, .animus-chest-screen, .animus-mc-stage canvas, [data-size-exempt]')) continue;
      const height = Math.round(box.height);
      const label = `${el.tagName.toLowerCase()}${el.className && typeof el.className === 'string' ? `.${el.className.trim().split(/\s+/).slice(0, 2).join('.')}` : ''} "${(el.getAttribute('aria-label') || el.textContent || '').trim().slice(0, 24)}"`;
      if (compact) {
        if (height < 44 && el.matches('button, .arcane-button, [role="button"], [role="tab"], [role="combobox"], input')) out.push(`${label} ${height}px`);
      } else if (el.matches('button.arcane-button, a.arcane-button, button.arcane-cycle-button') && !allowed.includes(height)) {
        out.push(`${label} ${height}px`);
      }
    }
    return [...new Set(out)];
  }, {allowed: buttonHeights.desktop, compact});
}

async function settle(page) {
  await page.waitForTimeout(150);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function run(browser, viewport, width, theme, failures) {
  const context = await browser.newContext({viewport, colorScheme: theme});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const cdp = await context.newCDPSession(page);
  await cdp.send('DOM.enable');
  await cdp.send('CSS.enable');
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error' && !/404|Failed to load resource/.test(message.text())) errors.push(message.text()); });
  let count = 0;
  const visit = async name => {
    await settle(page);
    const where = `${width} ${theme} ${name}`;
    for (const problem of await scan(page)) failures.push(`${where}: ${problem}`);
    for (const problem of await scanStates(page, cdp)) failures.push(`${where}: ${problem}`);
    for (const problem of await sizes(page, width === 'phone')) failures.push(`${where}: size ${problem}`);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    if (overflow > 0) failures.push(`${where}: scrolls sideways by ${overflow}px`);
    if (shots) {
      fs.mkdirSync(shots, {recursive: true});
      await page.screenshot({path: path.join(shots, `${width}-${theme}-${String(++count).padStart(2, '0')}-${name.replace(/[^a-z0-9]+/gi, '-')}.png`)});
    }
  };
  const hash = async (value, settled = value) => {
    await page.evaluate(next => { location.hash = next; }, value);
    await page.waitForFunction(next => location.hash === next, settled);
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

  const walk = async label => {
    const hrefs = await page.locator('#animus-sidebar a[href*="/workspace/"]').evaluateAll(links => links.map(link => link.getAttribute('href')));
    const byKind = new Map();
    for (const href of hrefs) {
      const match = /#\/workspace\/[^/]+\/([^/?]+)\/[^?]+$/.exec(href);
      if (match && !byKind.has(match[1])) byKind.set(match[1], href);
    }
    for (const [kind, href] of byKind) {
      for (const view of ['visual', 'preview', 'code', 'split']) {
        await hash(`${href}?view=${view}`);
        await page.locator('#animus-main').waitFor();
        await page.waitForTimeout(view === 'preview' || view === 'split' ? 400 : 100);
        await visit(`${label} ${kind} ${view}`);
      }
    }
  };
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
  // The pair page swaps the code out of the address.
  await hash('#/pair/ANM1notacode', '#/pair');
  await visit('pair');

  // Menus, the palette and a confirmation.
  await hash(`#/workspace/${workspace}`);
  await page.getByRole('heading', {name: 'Try it in the browser'}).waitFor();
  if (await isCompact(page)) {
    await page.locator('#animus-menu-more').click();
    await visit('more menu');
    await page.keyboard.press('Escape');
    await page.locator('#animus-documents-button').click();
    await page.locator('#animus-sidebar.is-open').waitFor();
    await visit('drawer');
    await page.keyboard.press('Escape');
  } else {
    for (const menu of ['open', 'import', 'export']) {
      await page.locator(`#animus-menu-${menu}`).click();
      await visit(`${menu} menu`);
      await page.keyboard.press('Escape');
    }
  }
  await page.locator('body').click({position: {x: 1, y: viewport.height - 1}});
  await page.keyboard.press('Control+k');
  await page.locator('.animus-palette').waitFor();
  await visit('command palette');
  await page.keyboard.press('Escape');
  await page.goto(url);
  await page.getByRole('heading', {name: 'Start a workspace'}).waitFor();
  await page.locator('[aria-label^="Delete "]').first().click();
  await page.getByRole('alertdialog').waitFor();
  await visit('delete confirm');
  await page.keyboard.press('Escape');

  for (const folder of folders) {
    await page.goto(url);
    await page.getByRole('heading', {name: 'From a data folder'}).waitFor();
    const chooser = page.waitForEvent('filechooser');
    await page.getByRole('button', {name: 'Open folder'}).first().click();
    await (await chooser).setFiles(folder);
    await page.waitForFunction(() => /#\/workspace\//.test(location.hash));
    await page.locator('#animus-sidebar a[href*="/workspace/"]').first().waitFor({state: 'attached'});
    await walk(path.basename(folder));
    if (path.basename(folder) === 'groups_recordings') {
      const opened = /#\/workspace\/([^/?]+)/.exec(page.url())[1];
      await hash(`#/workspace/${opened}/npcs`);
      await page.locator('[data-npc="group-assign"]').waitFor();
      await visit('groups_recordings npc list');
      await hash(`#/workspace/${opened}/recordings`);
      await page.locator('[data-recording="list"]').waitFor();
      await visit('groups_recordings recordings list');
    }
  }

  for (const error of errors) failures.push(`${width} ${theme}: console ${error}`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  const failures = [];
  try {
    for (const [width, viewport] of [['desktop', {width: 1440, height: 900}], ['phone', {width: 390, height: 844}]]) {
      for (const theme of ['dark', 'light']) await run(browser, viewport, width, theme, failures);
    }
  } finally {
    await browser.close();
  }
  if (process.env.ANIMUS_SHAPE_LIST) {
    for (const failure of failures) console.log(failure);
  }
  assert.deepEqual(failures, [], `${failures.length} shape problems:\n${failures.slice(0, 80).join('\n')}`);
  console.log('shape_browser: ok');
})().catch(error => { console.error(error.message || error); process.exit(1); });
