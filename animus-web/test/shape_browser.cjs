// Walks every page of the built site at 1440x900 and 390x844 in dark and
// light and fails on a crescent: an inset box-shadow (an inner highlight), a
// border on one side only in the pink accent, or an accent gradient. Corners
// and outer shadows are shadcn's own. ::before and ::after are checked too,
// and one control of each kind again with :hover, :focus-visible and :active
// forced on it. Button sizes are button_uniformity_browser.cjs's.
// Pages: home, the example-packs workspace and every document kind in it
// with each view, the gui_editors, rig_editor and groups_recordings fixtures
// (that one also with its NPC list and group form, and its recordings
// list), New document,
// Send to a server, the NPC list, the converter, a pairing link, the command
// palette, the top bar menus and a delete confirmation.
// ANIMUS_SHAPE_SCREENS (optional, a regular expression) checks only the
// pages whose name matches. ANIMUS_SCREENSHOTS (optional, outside the
// repository) receives a shot of
// every page per width and theme.
//
//   python3 tool/serve_site.py --port <port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/shape_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {documentHrefs, isCompact, startAction} = require('./support/shell.cjs');

const url = process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })();
const shots = process.env.ANIMUS_SCREENSHOTS;
const screens = process.env.ANIMUS_SHAPE_SCREENS ? new RegExp(process.env.ANIMUS_SHAPE_SCREENS) : null;
const folders = ['fixtures/gui_editors', 'fixtures/rig_editor', 'fixtures/groups_recordings'].map(folder => path.resolve(__dirname, folder));

// One control of each kind is also checked with :hover, :focus-visible and
// :active forced on it, so a state cannot bring back an underline or a ring.
const stateTargets = [
  ".arcane-button[data-variant='primary']",
  ".arcane-button[data-variant='secondary']",
  ".arcane-button[data-variant='outline']",
  ".arcane-button[data-variant='ghost']",
  ".arcane-button[data-variant='destructive']",
  ".arcane-button[data-size^='icon']",
  '[role="tab"]',
  '[role="tab"][aria-selected="true"]',
  '.arcane-toggle-group-item',
  '.ui-nav-item',
  '.ui-library-row',
  '.animus-info [data-animus-info]',
  'input[type="text"], input:not([type])',
  'input[type="checkbox"]',
  '[role="slider"]',
  'button[role="combobox"]',
  'input[role="combobox"]',
  'textarea',
  'summary',
  '.arcane-toast-close',
];

/** Every shape violation on the page, as readable strings. */
async function scan(page, only = null) {
  return page.evaluate(({only}) => {
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
        // An inner highlight is a crescent; outer shadows are shadcn's elevation.
        if (!/\binset\b/.test(part)) return false;
        const lengths = part.replace(/(rgba?|oklab|color|color-mix)\([^)]*\)/g, '').match(/-?[\d.]+px/g) || [];
        return lengths.some(length => parseFloat(length) !== 0);
      });
    };
    const out = [];
    const check = (el, style, label) => {
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
  }, {only});
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
    if (screens && !screens.test(name)) return;
    await settle(page);
    const where = `${width} ${theme} ${name}`;
    for (const problem of await scan(page)) failures.push(`${where}: ${problem}`);
    for (const problem of await scanStates(page, cdp)) failures.push(`${where}: ${problem}`);
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
  await page.evaluate(mode => { window.localStorage.setItem('animus.theme', mode); window.localStorage.setItem('animus.mode', 'advanced'); }, theme);
  await page.reload();
  await page.getByRole('heading', {name: 'Animus web editor'}).waitFor();
  await visit('home');

  await page.getByRole('button', {name: 'Open examples'}).click();
  await page.locator('[data-testid="home-count"]').first().waitFor();
  const workspace = /#\/workspace\/([^/?]+)/.exec(page.url())[1];
  await visit('workspace');

  const walk = async label => {
    const hrefs = await documentHrefs(page);
    const byKind = new Map();
    for (const href of hrefs) {
      const match = /#\/workspace\/[^/]+\/([^/?]+)\/[^?]+$/.exec(href);
      if (match && !byKind.has(match[1])) byKind.set(match[1], href);
    }
    for (const [kind, href] of byKind) {
      for (const view of ['visual', 'preview', 'code', 'split']) {
        // A view a kind does not have settles on one it has (a conversation
        // opens as its Conversation view).
        await page.evaluate(next => { location.hash = next; }, `${href}?view=${view}`);
        await page.waitForFunction(next => location.hash.split('?')[0] === next, href);
        await page.locator('#animus-main').waitFor();
        await page.waitForTimeout(view === 'preview' || view === 'split' ? 400 : 100);
        await visit(`${label} ${kind} ${view}`);
        // An NPC or trait group form is cut into tabs: every tab is a screen.
        if (view === 'visual' && (kind === 'npc' || kind === 'group')) {
          const tabs = page.locator('.animus-npc-tabs [role="tab"]');
          const first = await tabs.evaluateAll(all => all.findIndex(tab => tab.getAttribute('aria-selected') === 'true'));
          for (let index = 0; index < await tabs.count(); index++) {
            if (index === first) continue;
            const name = (await tabs.nth(index).innerText()).trim();
            await tabs.nth(index).click();
            await page.locator(`[data-npc-tab="${name.toLowerCase().replace(/[^a-z0-9]+/g, '-')}"]`).waitFor();
            await visit(`${label} ${kind} ${view} tab ${name}`);
          }
        }
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
  await hash(`#/workspace/${workspace}/list/shop`);
  await visit('list shops');
  await hash(`#/workspace/${workspace}/list/route`);
  await visit('list movement');
  await hash(`#/workspace/${workspace}/sync`);
  await visit('sync');
  await hash('#/citizens');
  await visit('converter');
  // The pair page swaps the code out of the address.
  await hash('#/pair/ANM1notacode', '#/pair');
  await visit('pair');

  // Menus, the palette and a confirmation.
  await hash(`#/workspace/${workspace}`);
  await page.locator('[data-testid="home-count"]').first().waitFor();
  await page.locator('#ui-overflow-button').click();
  await page.locator('[role="menu"]:visible').first().waitFor();
  await visit('overflow menu');
  await page.keyboard.press('Escape');
  await page.locator('#ui-context-button').click();
  await page.locator('.ui-context-list').waitFor();
  await visit('switcher');
  await page.keyboard.press('Escape');
  if (await isCompact(page)) {
    await page.locator('#ui-nav-button').click();
    await page.locator('#ui-nav-drawer .arcane-sidebar').waitFor();
    await visit('drawer');
    await page.keyboard.press('Escape');
  }
  await page.locator('body').click({position: {x: 1, y: viewport.height - 1}});
  await page.keyboard.press('Control+k');
  await page.locator('[data-arcane-command-input="ui-search"]').waitFor();
  await visit('command palette');
  await page.keyboard.press('Escape');
  await page.goto(url);
  await page.getByRole('heading', {name: 'Continue'}).waitFor();
  await page.locator('.ui-continue-row').first().getByRole('button', {name: 'More actions'}).click();
  await page.getByRole('menuitem', {name: 'Delete workspace'}).click();
  await page.locator('[role="alertdialog"]:visible, [role="dialog"]:visible').first().waitFor();
  await visit('delete confirm');
  await page.keyboard.press('Escape');

  for (const folder of folders) {
    await page.goto(url);
    await page.getByRole('heading', {name: 'Continue'}).waitFor();
    const chooser = page.waitForEvent('filechooser');
    await startAction(page, 'Open folder');
    await (await chooser).setFiles(folder);
    await page.waitForFunction(() => /#\/workspace\//.test(location.hash));
    await page.locator('.ui-save-bar').waitFor();
    await walk(path.basename(folder));
    if (path.basename(folder) === 'groups_recordings') {
      const opened = /#\/workspace\/([^/?]+)/.exec(page.url())[1];
      await hash(`#/workspace/${opened}/npcs`);
      await page.locator('#npc-list .ui-library-row').first().waitFor();
      await visit('groups_recordings npc list');
      await hash(`#/workspace/${opened}/recordings`);
      await page.locator('#movement-list').waitFor();
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
