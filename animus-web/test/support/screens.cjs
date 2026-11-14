// The screens the layout checks walk at one width: the example packs (the
// shop in Simple with each mode it shows, a new quest, then every document
// with a page in Advanced, in the Form and Split views and, for behaviors,
// the Conversation view), the gui_editors, rig_editor and groups_recordings
// fixtures, and a server paired with the fake server. On a document the
// preview is shown (the Preview button on phones), the shop's own toggles
// are tried one by one, a behavior's Test is started, and the first
// document of each kind goes fullscreen and, with `tabs`, shows each of its
// form's tabs. With `pages` the frame's other screens come too: the start
// page, Home, New, the lists, Send to server, Import, the shortcuts and
// delete dialogs, the pair page and Review. The paired server has a long
// name, and after its walk gains real-shaped data (support/real_shape.cjs):
// Movement with many routes, an NPC named only by colour codes, the GUIs list.
const path = require('node:path');
const {documentHrefs, pickTab, startAction} = require('./shell.cjs');
const {nameServer, addRealShape, realShapeScreens} = require('./real_shape.cjs');

const folders = ['gui_editors', 'rig_editor', 'groups_recordings'].map(name => path.resolve(__dirname, '..', 'fixtures', name));

/** Kinds with a page of their own; packs, skins and server behaviors are forms only. */
const pageKinds = new Set(['npc', 'group', 'behavior', 'shop', 'quest', 'inventory', 'menu', 'hologram', 'rig', 'animation', 'route', 'recording', 'text-animation', 'presets']);

/** Kinds whose page shows a preview frame once it is revealed. */
const framedKinds = new Set(['npc', 'behavior', 'shop', 'quest', 'inventory', 'menu', 'hologram', 'text-animation']);

async function control(fake, route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {method: 'POST', headers: {'content-type': 'application/json'}, body: JSON.stringify(body || {})});
  return response.json();
}

/**
 * Visits every screen; [check] (name, {framed}) inspects each one as it is
 * shown, [wanted] (name) picks the screens to visit.
 */
async function walkScreens(page, {url, fake, width, wanted = () => true, check, tabs = false, pages = false}) {
  const hash = async value => {
    await page.evaluate(next => { location.hash = next; }, value);
    await page.waitForFunction(next => location.hash.split('?')[0] === next.split('?')[0], value)
      .catch(async () => { throw new Error(`${width}: ${value} settled on ${await page.evaluate(() => location.hash)}`); });
    await page.locator('main').first().waitFor();
  };
  const visit = async (name, options = {}) => {
    if (wanted(name)) await check(name, options);
  };
  // On phones the preview waits behind a button: show it.
  const reveal = async () => {
    if (width > 760) return;
    const main = page.locator('#animus-main');
    const button = main.locator('button[aria-controls="ui-doc-preview"][aria-pressed="false"]');
    if (await button.count()) {
      await button.first().click();
      await page.waitForTimeout(200);
    }
  };
  const fullscreen = async name => {
    const button = page.locator('.ui-stage').getByRole('button', {name: 'Fullscreen', exact: true}).first();
    if (!(await button.count()) || !(await button.isVisible())) return;
    await button.click();
    const entered = await page.waitForFunction(() => document.fullscreenElement != null, null, {timeout: 3000}).then(() => true, () => false);
    if (!entered) throw new Error(`${width} ${name}: the frame did not enter fullscreen`);
    await page.waitForTimeout(300);
    await visit(`${name} fullscreen`, {framed: true});
    await page.evaluate(() => document.exitFullscreen());
    await page.waitForFunction(() => document.fullscreenElement == null);
  };
  // Each toggle in the preview's own controls (mode shown, window), one by one.
  const eachChoice = async (label, name) => {
    const group = page.locator(`.ui-stage [role="group"][aria-label="${label}"]`).first();
    if (!(await group.count())) return;
    const items = group.locator('.arcane-toggle-group-item');
    for (let i = 0; i < await items.count(); i++) {
      const text = (await items.nth(i).innerText()).trim();
      await items.nth(i).click();
      await visit(`${name} ${label.toLowerCase()} ${text.toLowerCase()}`, {framed: true});
    }
  };
  // A behavior's Test: Run it (from its Starts dropdown's second start when
  // it has several) and answer the first question.
  const startTest = async name => {
    const pane = page.locator('[data-testid="test-pane"]');
    if (!(await pane.count())) return;
    const trigger = pane.getByRole('combobox', {name: 'Starts'}).first();
    if (await trigger.count()) {
      await trigger.click();
      const list = page.locator(`[id="${await trigger.getAttribute('aria-controls')}"]`);
      const options = list.getByRole('option');
      await options.nth((await options.count()) > 1 ? 1 : 0).click();
    }
    const run = pane.getByRole('button', {name: 'Run', exact: true});
    if (!(await run.count())) return;
    await run.click();
    await page.waitForTimeout(200);
    const answer = pane.locator('[data-answer]:not([disabled])');
    if (await answer.count()) await answer.first().click();
    await visit(`${name} started`, {framed: true});
  };
  // Every tab of the form's first tab strip, the first one last.
  const eachTab = async name => {
    const strip = page.locator('#animus-main [role="tablist"]').first();
    if (!(await strip.count())) return;
    const all = strip.getByRole('tab');
    const labels = (await all.allInnerTexts()).map(text => text.trim());
    for (const [index, label] of [...labels.entries()].slice(1)) {
      await all.nth(index).click();
      await visit(`${name} tab ${label.toLowerCase()}`);
    }
    if (labels.length > 1) await all.first().click();
  };
  const walk = async label => {
    const hrefs = [...new Set(await documentHrefs(page))];
    const firstOf = new Set();
    for (const href of hrefs) {
      const match = /#\/workspace\/[^/]+\/([^/?]+)\/([^?]+)$/.exec(href);
      if (!match || !pageKinds.has(match[1])) continue;
      const kind = match[1];
      const id = decodeURIComponent(match[2]);
      const first = !firstOf.has(kind);
      firstOf.add(kind);
      for (const view of ['visual', 'split', ...(kind === 'behavior' ? ['conversation'] : [])]) {
        const name = `${label} ${kind} ${id} ${view}`;
        if (!wanted(name)) continue;
        await hash(`${href}?view=${view}`);
        await page.waitForTimeout(view === 'split' ? 400 : 150);
        const opened = await page.evaluate(() => new URLSearchParams(location.hash.split('?')[1] || '').get('view') || 'visual');
        if (opened !== view && view !== 'visual') continue;
        await reveal();
        await visit(name, {framed: framedKinds.has(kind) && view !== 'split'});
        if (view === 'split') continue;
        if (kind === 'shop') {
          await eachChoice('Mode shown', name);
          await eachChoice('Window', name);
        }
        if (kind === 'behavior') await startTest(name);
        if (first && view === 'visual') {
          await fullscreen(name);
          if (tabs) {
            await eachTab(name);
          } else if (kind === 'npc') {
            const movement = page.locator('#animus-main').getByRole('tab', {name: 'Movement', exact: true});
            if (await movement.count()) {
              await movement.first().click();
              await visit(`${name} movement`);
            }
          }
        }
      }
    }
  };
  const setMode = async mode => {
    await page.evaluate(value => window.localStorage.setItem('animus.mode', value), mode);
    await page.reload();
    await page.locator('.ui-save-bar, [data-testid="home-count"]').first().waitFor();
  };
  const dialog = async (name, open) => {
    if (!wanted(name)) return;
    await open();
    const shown = await page.locator('[role="alertdialog"]:visible, [role="dialog"]:visible').first().waitFor({timeout: 3000}).then(() => true, () => false);
    if (!shown) throw new Error(`${width} ${name}: no dialog opened`);
    await page.waitForTimeout(200);
    await visit(name);
    await page.keyboard.press('Escape');
  };

  await page.goto(url);
  await page.evaluate(() => window.localStorage.setItem('animus.mode', 'simple'));
  await page.reload();
  await page.getByRole('heading', {name: 'Animus web editor'}).waitFor();
  if (pages) await visit('start');
  await page.getByRole('button', {name: 'Open examples'}).click();
  await page.locator('[data-testid="home-count"]').first().waitFor();
  const workspace = /#\/workspace\/([^/?]+)/.exec(page.url())[1];

  // Simple: the shop as an owner first meets it.
  if (wanted('examples simple shop')) {
    await hash(`#/workspace/${workspace}/shop/${encodeURIComponent('shop:bakery')}`);
    await reveal();
    await visit('examples simple shop shop:bakery', {framed: true});
    await eachChoice('Mode shown', 'examples simple shop shop:bakery');
  }

  await setMode('advanced');
  if (pages) {
    await page.goto(url);
    await page.getByRole('heading', {name: 'Continue'}).waitFor();
    await visit('home');
    await dialog('delete confirm', async () => {
      await page.locator('.ui-continue-row').first().getByRole('button', {name: 'More actions'}).click();
      await page.getByRole('menuitem', {name: 'Delete workspace'}).click();
    });
    await hash(`#/workspace/${workspace}`);
    await page.locator('[data-testid="home-count"]').first().waitFor();
    await visit('workspace');
    await dialog('shortcuts dialog', async () => {
      await page.locator('body').click({position: {x: 1, y: page.viewportSize().height - 1}});
      await page.keyboard.press('?');
    });
    for (const [name, route] of [
      ['new document', `#/workspace/${workspace}/new`], ['new npc', `#/workspace/${workspace}/new/npc`],
      ['list npcs', `#/workspace/${workspace}/npcs`], ['list behaviors', `#/workspace/${workspace}/list/behavior`],
      ['list shops', `#/workspace/${workspace}/list/shop`], ['list movement', `#/workspace/${workspace}/list/route`],
      ['sync', `#/workspace/${workspace}/sync`], ['import', '#/citizens'],
    ]) {
      if (!wanted(name)) continue;
      await hash(route);
      await page.waitForTimeout(200);
      await visit(name);
    }
  }
  if (wanted('examples quest new')) {
    await hash(`#/workspace/${workspace}/new/quest`);
    await page.getByLabel('Title', {exact: true}).fill('Bread for the baker');
    await page.getByRole('button', {name: 'Create', exact: true}).click();
    await page.waitForFunction(() => location.hash.includes('/quest/'));
  }
  await walk('examples');

  for (const folder of folders) {
    const label = path.basename(folder);
    await page.goto(url);
    await page.getByRole('heading', {name: 'Continue'}).waitFor();
    const chooser = page.waitForEvent('filechooser');
    await startAction(page, 'Open folder');
    await (await chooser).setFiles(folder);
    await page.waitForFunction(() => /#\/workspace\//.test(location.hash));
    await page.locator('.ui-save-bar').waitFor();
    await walk(label);
  }

  // Shapes real server data had: a Movement list longer than the page, an
  // NPC named only "&f" and an imported module with many settings.
  {
    await page.goto(url);
    await page.getByRole('heading', {name: 'Continue'}).waitFor();
    const chooser = page.waitForEvent('filechooser');
    await startAction(page, 'Open folder');
    await (await chooser).setFiles(path.resolve(__dirname, '..', 'fixtures', 'real_shapes'));
    await page.waitForFunction(() => /#\/workspace\//.test(location.hash));
    await page.locator('.ui-save-bar').waitFor();
    const opened = /#\/workspace\/([^/?]+)/.exec(page.url())[1];
    await hash(`#/workspace/${opened}/list/route`);
    await visit('real_shapes list movement');
    await hash(`#/workspace/${opened}/npc/${encodeURIComponent('world/citizens-60')}`);
    await reveal();
    await visit('real_shapes npc world/citizens-60 visual', {framed: true});
    await hash(`#/workspace/${opened}/npc/${encodeURIComponent('world/imported')}`);
    await pickTab(page, page.locator('.animus-npc-tabs'), 'Modules');
    await visit('real_shapes npc world/imported visual tab modules');
  }

  await nameServer(fake);
  const {code} = await control(fake, 'pair?paths=both', {});
  await page.goto(`${url}/#/pair/${code}`);
  await page.getByTestId('pair-submit').waitFor();
  if (pages) await visit('pair');
  await page.getByTestId('pair-submit').click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await page.locator('[data-testid="home-count"]').first().waitFor();
  if (pages) await visit('server');
  const server = await page.evaluate(() => location.hash.split('/')[2]);
  await hash(`#/workspace/server-${server}`);
  await page.locator('.ui-save-bar').waitFor();
  // Review is offered only when something waits in it: an up to date
  // server in Simple shows no Review button at all (Advanced keeps it for
  // the apply history).
  if (pages && wanted('review')) {
    const simple = (await page.evaluate(() => window.localStorage.getItem('animus.mode'))) !== 'advanced';
    if (simple && await page.locator('.ui-save-bar [data-save-action="review"]').count()) throw new Error(`${width}: Review is offered with nothing to review`);
  }
  await walk('server');
  // Real-shaped data: many NPC routes, a name made only of colour codes,
  // raw-id behaviors and shops, pixel-art holograms. Seen as a first-time
  // owner sees them, in Simple.
  await addRealShape(fake);
  await setMode('simple');
  for (const [name, route] of realShapeScreens(`server-${server}`)) {
    if (!wanted(name)) continue;
    await hash(route);
    if (name.endsWith('movement')) await page.waitForFunction(() => /\b([4-9]\d|\d{3,}) routes\b/.test(document.getElementById('animus-main')?.innerText || ''), null, {timeout: 20000})
        .catch(async () => { throw new Error(`Movement never listed the walkers: ${(await page.locator('#animus-main').innerText()).slice(0, 300)}`); });
    await page.waitForTimeout(300);
    await reveal();
    await visit(name, {framed: name.endsWith('npc')});
  }
}

/**
 * What Simple must never show: a raw id as a heading, a list row's title or
 * a dropdown's value; colour codes in a text box nobody is editing; a
 * tooltip in the brand pink; a "Hide preview" button; a doubled plural
 * ("presetss").
 */
async function simpleProblems(page) {
  return page.evaluate(() => {
    const out = [];
    // The walk switches to Advanced for Code and Split; those screens may show it all.
    if (window.localStorage.getItem('animus.mode') === 'advanced') return out;
    const shown = el => { const box = el.getBoundingClientRect(); return box.width > 0 && box.height > 0 && getComputedStyle(el).visibility !== 'hidden'; };
    const rawId = /^[a-z0-9_-]+:[a-z0-9_-]+(\/[a-z0-9_-]+)*$/;
    const looks = [
      ['heading', '#animus-main-heading'],
      ['row', '#animus-main .ui-library-title'],
      ['dropdown', '#animus-main [role="combobox"]'],
    ];
    for (const [what, selector] of looks) {
      for (const el of document.querySelectorAll(selector)) {
        const text = el.textContent.trim();
        if (shown(el) && rawId.test(text)) out.push(`raw id as a ${what}: ${text}`);
      }
    }
    const codes = /&[0-9a-fk-or#]|<\/?(gold|red|green|aqua|bold|italic|yellow|white|gray|grey|blue|light_purple|dark_[a-z]+)>/i;
    for (const input of document.querySelectorAll('#animus-main input[type="text"], #animus-main input:not([type])')) {
      if (shown(input) && input !== document.activeElement && codes.test(input.value)) out.push(`colour codes in an idle text box: ${input.value.slice(0, 40)}`);
    }
    const primary = getComputedStyle(document.querySelector('#arcane-root') || document.documentElement).getPropertyValue('--primary').trim();
    for (const tip of document.querySelectorAll('[role="tooltip"]')) {
      if (!shown(tip)) continue;
      const probe = document.createElement('span');
      probe.style.color = primary;
      document.body.appendChild(probe);
      const pink = getComputedStyle(probe).color;
      probe.remove();
      if (getComputedStyle(tip).backgroundColor === pink) out.push(`a tooltip in the primary colour: ${tip.textContent.trim().slice(0, 30)}`);
    }
    for (const button of document.querySelectorAll('button')) {
      if (shown(button) && /^\s*Hide preview\s*$/.test(button.textContent)) out.push('a Hide preview button');
    }
    const plural = /\b\d[\d,]* [A-Za-z]*[^aeiouls\s]ss\b/;
    const text = (document.getElementById('animus-main') || document.body).innerText;
    const doubled = plural.exec(text);
    if (doubled) out.push(`a doubled plural: ${doubled[0]}`);
    return out;
  });
}

/**
 * The arcane runtime shows a tooltip for every element with a title. It must
 * leave the element where Jaspr put it: a node moved into a wrapper of its
 * own makes Jaspr fail on its next change (a badge going from Online to
 * Pairing ended, a route change) and the page stops rendering. Hovers each
 * titled element and fails one whose parent changes or that gains a wrapper.
 */
async function titleProblems(page) {
  return page.evaluate(() => {
    const out = [];
    if (document.querySelector('.arcane-tooltip-trigger')) out.push('the arcane runtime moved an element into a tooltip span');
    const titled = [...document.querySelectorAll('[title]')].filter(el => !el.closest('svg') && el.getAttribute('title'));
    for (const el of titled.slice(0, 40)) {
      const parent = el.parentNode;
      const index = [...parent.childNodes].indexOf(el);
      el.dispatchEvent(new PointerEvent('pointerover', {bubbles: true, pointerType: 'mouse'}));
      el.dispatchEvent(new PointerEvent('pointerout', {bubbles: true, pointerType: 'mouse', relatedTarget: document.body}));
      if (el.parentNode !== parent || [...parent.childNodes].indexOf(el) !== index) {
        out.push(`the arcane runtime moved the titled ${el.tagName.toLowerCase()}${typeof el.className === 'string' && el.className ? '.' + el.className.split(' ')[0] : ''}`);
      }
    }
    return out;
  });
}

module.exports = {walkScreens, simpleProblems, titleProblems, pageKinds, framedKinds};
