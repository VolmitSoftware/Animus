// The editor's dropdowns (lib/components/select) and the collapsible sidebar,
// on an NPC of the example packs at 1440 px and 390 px in both themes:
// keyboard-only selection with the combobox and listbox roles, the search
// filter, "+ New rig" making a rig in the NPC's folder and picking it back,
// "Manage rigs", the phone sheet, placement above the control near the
// bottom, following the control through a page scroll (closing once it is out of sight), closing on an outside press, and a sidebar section
// and NPC folder that stay collapsed across a reload. Square, flat, no
// console errors and nothing scrolls sideways.
//
//   python3 tool/serve_site.py --port <port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/dropdowns_browser.cjs
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {sidebar, closeDrawer, isCompact} = require('./support/shell.cjs');
const {listboxOf, valueOf} = require('./support/select.cjs');

const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const npc = 'examples/bodyguard';

function step(label, text) {
  console.log(`  ${label}: ${text}`);
}

async function noSideways(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 0, `${where} scrolls sideways by ${overflow}px`);
}

/** Opens the inspector part holding [selector] when it is folded away. */
async function reveal(page, selector) {
  await page.locator(selector).waitFor({state: 'attached'});
  await page.locator(selector).evaluate(element => {
    for (let at = element.closest('details'); at; at = at.parentElement && at.parentElement.closest('details')) at.open = true;
  });
  const trigger = page.locator(selector);
  await trigger.scrollIntoViewIfNeeded();
  return trigger;
}

async function focused(page) {
  return page.evaluate(() => document.activeElement && document.activeElement.id);
}

async function flat(locator) {
  return locator.evaluate(element => {
    const style = getComputedStyle(element);
    return {radius: style.borderRadius, shadow: style.boxShadow, border: style.borderTopWidth, height: element.getBoundingClientRect().height};
  });
}

async function run(browser, {viewport, touch, theme}) {
  const label = `${viewport.width}px ${theme}`;
  const context = await browser.newContext({viewport, hasTouch: touch, isMobile: touch, colorScheme: theme});
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });

  await page.goto(url);
  await page.getByRole('button', {name: 'Create with example packs'}).click();
  await page.waitForFunction(() => location.hash.includes('/workspace/'));
  const workspace = await page.evaluate(() => location.hash.split('/')[2]);
  const npcHash = `#/workspace/${workspace}/npc/${encodeURIComponent(npc)}`;
  await page.goto(`${url}/${npcHash}`);
  const compact = await isCompact(page);

  // Keyboard only: the Pose dropdown.
  const pose = await reveal(page, '#animus-npc-pose');
  assert.equal(await pose.evaluate(el => el.tagName), 'BUTTON');
  assert.equal(await pose.getAttribute('role'), 'combobox');
  assert.equal(await pose.getAttribute('aria-haspopup'), 'listbox');
  assert.equal(await pose.getAttribute('aria-expanded'), 'false');
  assert.equal(await page.getByRole('combobox', {name: 'Pose', exact: true}).count(), 1, `${label}: the label names the dropdown`);
  const shape = await flat(pose);
  assert.equal(shape.radius, '0px');
  assert.equal(shape.shadow, 'none');
  assert.equal(Math.round(shape.height), compact ? 44 : 36, `${label}: the default control height`);
  await pose.focus();
  await page.keyboard.press('Shift+Tab');
  await page.keyboard.press('Tab');
  assert.equal(await focused(page), 'animus-npc-pose');
  const ring = await pose.evaluate(el => getComputedStyle(el).outlineStyle);
  assert.notEqual(ring, 'none', `${label}: a visible focus ring`);
  const before = await valueOf(pose);
  await page.keyboard.press('Enter');
  const poses = await listboxOf(pose);
  await poses.waitFor();
  assert.equal(await pose.getAttribute('aria-expanded'), 'true');
  assert.equal(await poses.getAttribute('role'), 'listbox');
  const firstActive = await pose.getAttribute('aria-activedescendant');
  assert.ok(firstActive, `${label}: the active option is named`);
  assert.equal(await page.locator(`[id="${firstActive}"]`).getAttribute('aria-selected'), 'true', 'it opens on the current value');
  await page.keyboard.press('End');
  const last = await pose.getAttribute('aria-activedescendant');
  await page.keyboard.press('Home');
  assert.notEqual(await pose.getAttribute('aria-activedescendant'), last, 'Home moves to the first option');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('ArrowDown');
  const active = page.locator(`[id="${await pose.getAttribute('aria-activedescendant')}"]`);
  const picked = await active.getAttribute('data-value');
  assert.notEqual(picked, before);
  // Escape leaves the value alone and returns focus.
  await page.keyboard.press('Escape');
  await poses.waitFor({state: 'detached'});
  assert.equal(await valueOf(pose), before);
  assert.equal(await focused(page), 'animus-npc-pose');
  // Space opens, arrows move, Enter picks and returns focus.
  await page.keyboard.press(' ');
  await poses.waitFor();
  if (compact) {
    const sheet = await page.locator('.animus-select-popup').evaluate(el => {
      const box = el.getBoundingClientRect();
      const rows = [...el.querySelectorAll('[role="option"]')].map(row => row.getBoundingClientRect().height);
      return {sheet: el.classList.contains('is-sheet'), width: box.width, bottom: box.bottom, rows};
    });
    assert.ok(sheet.sheet, `${label}: a sheet on phones`);
    assert.equal(Math.round(sheet.width), viewport.width, `${label}: the sheet is full width`);
    assert.equal(Math.round(sheet.bottom), viewport.height, `${label}: the sheet sits at the bottom`);
    assert.ok(sheet.rows.every(height => height >= 44), `${label}: 44 px rows, got ${sheet.rows}`);
  }
  const popupShape = await flat(page.locator('.animus-select-popup'));
  assert.equal(popupShape.radius, '0px');
  assert.equal(popupShape.shadow, 'none');
  assert.equal(popupShape.border, '1px');
  await page.keyboard.press('Home');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  await poses.waitFor({state: 'detached'});
  await page.waitForFunction(picked => document.getElementById('animus-npc-pose').getAttribute('data-value') === picked, picked);
  await page.waitForFunction(() => document.activeElement && document.activeElement.id === 'animus-npc-pose');
  step(label, `keyboard: Enter, End, Home, ArrowDown, Escape, Space, Enter -> pose ${picked}, focus back on the trigger`);

  // Outside press closes it.
  await pose.click();
  await poses.waitFor();
  if (compact) await page.locator('.animus-select-scrim').click({position: {x: 10, y: 10}});
  else await page.mouse.click(5, viewport.height - 5);
  await poses.waitFor({state: 'detached'});

  // Search: more than eight options get a filter field.
  const team = await reveal(page, '#animus-field--teamColor');
  await team.click();
  const teamList = await listboxOf(team);
  await teamList.waitFor();
  const search = page.locator('#animus-field--teamColor-search');
  await search.waitFor();
  await page.waitForFunction(() => document.activeElement && document.activeElement.id === 'animus-field--teamColor-search');
  assert.equal(await search.getAttribute('role'), 'combobox');
  assert.equal(await search.getAttribute('aria-controls'), await teamList.getAttribute('id'));
  const all = await teamList.locator('[role="option"]:not([data-action])').count();
  assert.ok(all > 8, `${label}: ${all} colours`);
  await page.keyboard.type('dark');
  const filtered = await teamList.locator('[role="option"]:not([data-action]) .animus-select-label').allTextContents();
  assert.ok(filtered.length > 0 && filtered.length < all, `${label}: the filter narrows ${all} to ${filtered.length}`);
  assert.ok(filtered.every(text => /dark/i.test(text)), `${label}: every match says dark: ${filtered}`);
  assert.ok(await search.getAttribute('aria-activedescendant'), 'the first match is active');
  await page.keyboard.press('ArrowDown');
  const chosenColour = await page.locator(`[id="${await search.getAttribute('aria-activedescendant')}"]`).getAttribute('data-value');
  await page.keyboard.press('Enter');
  await teamList.waitFor({state: 'detached'});
  await page.waitForFunction(colour => document.getElementById('animus-field--teamColor').getAttribute('data-value') === colour, chosenColour);
  await team.click();
  await teamList.waitFor();
  await page.waitForFunction(() => document.activeElement && document.activeElement.id === 'animus-field--teamColor-search');
  assert.equal(await search.inputValue(), '', 'the search starts empty');
  await page.keyboard.type('zzqx');
  await teamList.locator('.animus-select-empty', {hasText: 'Nothing matches'}).waitFor();
  await page.keyboard.press('Escape');
  await teamList.waitFor({state: 'detached'});
  await page.waitForFunction(() => document.activeElement && document.activeElement.id === 'animus-field--teamColor');
  step(label, `search: ${all} colours, "dark" leaves ${filtered.length}, ArrowDown and Enter pick ${chosenColour}, no match says so, Escape returns focus`);

  if (!compact) {
    // Near the bottom of the window the list opens upwards; a scroll closes it.
    const modules = await reveal(page, '#animus-npc-add-module');
    const moduleList = await listboxOf(modules);
    await modules.evaluate(el => {
      const scroller = document.scrollingElement;
      const box = el.getBoundingClientRect();
      scroller.scrollTop += box.bottom - window.innerHeight + 8;
    });
    await modules.click();
    await moduleList.waitFor();
    const placed = await page.evaluate(() => {
      const popup = document.querySelector('.animus-select-popup').getBoundingClientRect();
      const trigger = document.getElementById('animus-npc-add-module').getBoundingClientRect();
      return {up: document.querySelector('.animus-select-popup').classList.contains('is-up'), popupBottom: popup.bottom, triggerTop: trigger.top, popupTop: popup.top};
    });
    assert.ok(placed.up && placed.popupBottom <= placed.triggerTop + 1 && placed.popupTop >= 0, `${label}: opens above, got ${JSON.stringify(placed)}`);
    // A page scroll keeps it open and moves it with the control; the control
    // leaving the window closes it.
    const gap = () => page.evaluate(() => {
      const popup = document.querySelector('.animus-select-popup');
      if (!popup) return null;
      const box = popup.getBoundingClientRect();
      const trigger = document.getElementById('animus-npc-add-module').getBoundingClientRect();
      return {up: popup.classList.contains('is-up'), gap: popup.classList.contains('is-up') ? trigger.top - box.bottom : box.top - trigger.bottom, triggerTop: trigger.top};
    });
    await page.mouse.move(viewport.width / 2, 100);
    await page.mouse.wheel(0, -30);
    await page.waitForFunction(top => document.getElementById('animus-npc-add-module').getBoundingClientRect().top > top + 20, placed.triggerTop);
    await moduleList.waitFor();
    await page.waitForFunction(() => {
      const popup = document.querySelector('.animus-select-popup');
      const trigger = document.getElementById('animus-npc-add-module').getBoundingClientRect();
      return popup && Math.abs(trigger.top - popup.getBoundingClientRect().bottom) < 6;
    });
    const moved = await gap();
    assert.ok(moved && moved.up && moved.gap < 6, `${label}: stays open and follows the control, got ${JSON.stringify(moved)}`);
    assert.equal(await modules.getAttribute('aria-expanded'), 'true');
    await page.evaluate(() => { document.scrollingElement.scrollTop = 0; });
    await page.waitForFunction(() => document.getElementById('animus-npc-add-module').getBoundingClientRect().bottom < 0 || document.getElementById('animus-npc-add-module').getBoundingClientRect().top > innerHeight);
    await moduleList.waitFor({state: 'detached'});
    step(label, 'placement: above the control near the bottom; a page scroll keeps it open, follows the control, and closes it once the control is out of sight');
  }

  // "+ New rig" from the keyboard: the rig is made in the NPC's folder and picked.
  const rig = await reveal(page, '#animus-npc-rig');
  await rig.focus();
  await page.keyboard.press('Enter');
  const rigs = await listboxOf(rig);
  await rigs.waitFor();
  const footer = await rigs.locator('[data-action]').allTextContents();
  assert.deepEqual(footer, ['+ New rig', 'Manage rigs']);
  await page.keyboard.press('End');
  await page.keyboard.press('ArrowUp');
  assert.equal(await page.locator(`[id="${await rig.getAttribute('aria-activedescendant')}"]`).getAttribute('data-action'), 'new');
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => location.hash.includes('/new/rig'));
  assert.match(await page.evaluate(() => location.hash), /folder=examples/);
  await page.getByText('Create takes you back and picks it there.').waitFor();
  const id = page.getByLabel('Id', {exact: true});
  const made = await id.inputValue();
  assert.match(made, /^examples:/, `${label}: the rig goes in the NPC's folder, got ${made}`);
  await page.getByRole('button', {name: /^Create /}).click();
  await page.waitForFunction(hash => location.hash === hash, npcHash);
  const back = page.locator('#animus-npc-rig');
  await page.waitForFunction(made => document.getElementById('animus-npc-rig')?.getAttribute('data-value') === made, made);
  assert.equal(await back.locator('.animus-select-value').textContent(), made);
  await page.waitForFunction(() => document.activeElement && document.activeElement.id === 'animus-npc-rig');
  step(label, `+ New rig: ${made} made in the NPC's folder, back on the NPC with it picked and focused`);

  // Manage rigs lists them.
  await back.click();
  const again = await listboxOf(back);
  await again.waitFor();
  await again.locator('[data-action="manage"]').click();
  await page.waitForFunction(() => location.hash.endsWith('/list/rig'));
  await page.getByRole('heading', {level: 1, name: /Rigs/}).waitFor();
  await page.locator(`.animus-doc-list-items li[data-id="${made}"]`).waitFor();
  step(label, 'Manage rigs: the rig list names the new rig');

  // Sidebar: collapse a section and a folder, reload, still collapsed.
  let side = await sidebar(page);
  const behaviors = side.locator('.animus-tree-toggle[data-section="kind:behavior"]');
  assert.equal(await behaviors.getAttribute('aria-expanded'), 'true');
  assert.match(await behaviors.locator('.animus-count').textContent(), /^\d+$/);
  await behaviors.focus();
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => document.querySelector('.animus-tree-toggle[data-section="kind:behavior"]').getAttribute('aria-expanded') === 'false');
  assert.equal(await side.locator('[data-section-list="kind:behavior"]').count(), 0, 'the list is gone');
  const folder = side.locator('.animus-tree-toggle[data-section="folder:npc:examples"]');
  await folder.focus();
  await page.keyboard.press('ArrowLeft');
  await page.waitForFunction(() => document.querySelector('.animus-tree-toggle[data-section="folder:npc:examples"]').getAttribute('aria-expanded') === 'false');
  await page.reload();
  side = await sidebar(page);
  await side.locator('.animus-tree-toggle[data-section="kind:behavior"]').waitFor();
  assert.equal(await side.locator('.animus-tree-toggle[data-section="kind:behavior"]').getAttribute('aria-expanded'), 'false', `${label}: kept across a reload`);
  assert.equal(await side.locator('.animus-tree-toggle[data-section="folder:npc:examples"]').getAttribute('aria-expanded'), 'false');
  await side.locator('.animus-tree-toggle[data-section="folder:npc:examples"]').focus();
  await page.keyboard.press('ArrowRight');
  await page.waitForFunction(() => document.querySelector('.animus-tree-toggle[data-section="folder:npc:examples"]').getAttribute('aria-expanded') === 'true');
  await side.getByRole('button', {name: 'Collapse all'}).click();
  await page.waitForFunction(() => [...document.querySelectorAll('#animus-sidebar .animus-tree-toggle')].every(el => el.getAttribute('aria-expanded') === 'false'));
  await side.getByRole('button', {name: 'Expand all'}).click();
  await page.waitForFunction(() => [...document.querySelectorAll('#animus-sidebar .animus-tree-toggle')].every(el => el.getAttribute('aria-expanded') === 'true'));
  await closeDrawer(page);
  step(label, 'sidebar: Enter folds Behaviors, ArrowLeft folds the examples folder, both kept across a reload; Collapse all and Expand all');

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
    console.log('dropdowns_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exit(1);
});
