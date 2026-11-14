// The conversation builder against tool/fake_server.dart at 1440 and 390 px
// in both themes: pair, make a conversation from the Behaviors list's New
// behavior dialog (the id takes the namespace most documents use), pick
// how it starts, delete the starter question and the parts it led to
// through the step and part "..." menus, then build a question with three
// answers and their follow-ups by keyboard (a placeholder from the step
// menu in Advanced, Enter for the next line, inline [answers], Ctrl+Enter
// for what follows, arrows between fields, /if for a permission check with
// a line under Yes), add a Do
// step from "Add step", check every step menu opens fully visible with
// keys named per platform and is never drawn over the top bar once its
// line scrolls away, duplicate and delete a step, read the written JSON,
// play it in the Test pane with an answer, the assumed permission flipped
// and run again, attach it to the NPC from the whole NPC library, apply both
// documents, read them back from the fake server and reopen the behavior
// in Simple. No console errors, no native selects and no sideways
// scrolling.
//
//   python3 tool/serve_site.py --port <web port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<web port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<web port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/conversations_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {assertNothingCut} = require('./support/cut_text.cjs');
const {isCompact, openFromSidebar, openJob, pickOption, saveAction, saveState, setMode, showView, waitOnline, hidePreview} = require('./support/shell.cjs');
const {choose, chooseText} = require('./support/select.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;
const npc = 'examples/greeter';

/// Runs the Test in [pane] from [label]: the Starts dropdown picks it when
/// there is more than one start, then Run plays it from the start.
async function pickStart(page, pane, label) {
  const run = pane.getByRole('button', {name: 'Run', exact: true});
  await run.waitFor();
  const starts = pane.getByRole('combobox', {name: 'Starts'});
  if (await starts.count()) await pickOption(page, starts, label);
  await run.click();
}

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

async function shot(page, name) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 1, `${name}: ${overflow}px of sideways scrolling`);
  assert.equal(await page.locator('.animus-conv select').count(), 0, `${name}: a native select in the conversation`);
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`)});
}

/// The id of the field focused now.
const focused = page => page.evaluate(() => document.activeElement && document.activeElement.id);

const waitFocus = (page, id) => page.waitForFunction(wanted => document.activeElement && document.activeElement.id === wanted, id);

/// Waits until the page has not changed for 250 ms: the rebuilds a burst
/// of typed keys starts have landed. A rebuild closes an open menu.
async function settle(page) {
  await page.evaluate(() => new Promise(resolve => {
    let last = performance.now();
    const observer = new MutationObserver(() => { last = performance.now(); });
    observer.observe(document.body, {subtree: true, attributes: true, childList: true, characterData: true});
    const check = () => {
      if (performance.now() - last >= 250) {
        observer.disconnect();
        resolve();
      } else {
        setTimeout(check, 50);
      }
    };
    setTimeout(check, 50);
  }));
}

/// The open menu, once its box has stopped moving (the runtime places it
/// after it opens).
async function openMenu(page, button) {
  await settle(page);
  await button.scrollIntoViewIfNeeded();
  await button.click();
  const menu = page.locator('[role="menu"][data-arcane-state="open"]');
  await menu.waitFor();
  await page.waitForFunction(() => new Promise(resolve => {
    const box = () => JSON.stringify(document.querySelector('[role="menu"][data-arcane-state="open"]')?.getBoundingClientRect());
    const first = box();
    requestAnimationFrame(() => requestAnimationFrame(() => resolve(first === box())));
  }));
  return menu;
}

async function closeMenu(page, menu) {
  await page.keyboard.press('Escape');
  await menu.waitFor({state: 'hidden'});
}

/// Picks [item] from the "..." menu of the step or answer [key]
/// (`main:1`, `main:1/0`), answering a confirmation with [confirm].
async function stepAction(page, key, item, confirm) {
  const menu = await openMenu(page, page.locator(`[id="conv-menu:${key}-button"]`));
  await menu.getByRole('menuitem', {name: item}).click();
  if (confirm) await confirmWith(page, confirm);
}

async function confirmWith(page, label) {
  const dialog = page.getByRole('alertdialog');
  await dialog.waitFor();
  await dialog.getByRole('button', {name: label, exact: true}).click();
  await dialog.waitFor({state: 'detached'});
}

/// Runs a page header action: its button, or its entry in the header's "..."
/// menu when the row folded it there (a phone in Advanced, beside the view
/// switch).
async function headerAction(page, name) {
  const button = page.locator('.ui-page-header').getByRole('button', {name, exact: true});
  if (await button.isVisible().catch(() => false)) return button.click();
  await page.locator('#ui-page-actions-button').click();
  await page.getByRole('menuitem', {name, exact: true}).click();
}

/// The Test pane, shown first on phones where it hides behind the page
/// header's Test button.
async function testPane(page) {
  const pane = page.locator('[data-testid="test-pane"]');
  if (!(await pane.isVisible().catch(() => false))) {
    await headerAction(page, 'Test');
    await pane.waitFor();
  }
  await pane.scrollIntoViewIfNeeded();
  return pane;
}

async function run(browser, viewport, label, theme) {
  await control('reset', {});
  await control('restore', {});
  const {code} = await control('pair?paths=both', {});
  const context = await browser.newContext({viewport});
  await context.addInitScript(mode => window.localStorage.setItem('animus.theme', mode), theme);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  page.on('pageerror', error => errors.push(String(error)));
  const mac = await page.evaluate(() => /Mac/.test(navigator.platform));

  await page.goto(`${site}/#/pair/${code}`);
  await page.getByTestId('pair-submit').waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await waitOnline(page);
  // The workspace fills from the catalog by itself.
  await page.locator('[data-testid="home-count"]').first().waitFor();

  // New behavior from the Behaviors list: Conversation is the default kind,
  // and the behavior opens in its Conversation view (Simple has no switch).
  await openJob(page, 'Behaviors');
  await page.locator('[data-testid="list-new"]').click();
  const dialog = page.getByRole('dialog', {name: 'New behavior'});
  await dialog.waitFor();
  assert.equal(await dialog.getByRole('radio', {name: /Conversation/}).isChecked(), true, `${label}: Conversation is the default kind`);
  assert.equal(await dialog.getByRole('radio', {name: /Server behavior/}).count(), 0, `${label}: Server behavior is Advanced only`);
  await page.locator('#new-behavior-name').fill('Harbor master');
  await dialog.getByRole('button', {name: 'Create', exact: true}).click();
  await page.waitForFunction(() => location.hash.includes('/behavior/') && location.hash.includes('view=conversation'));
  const behavior = decodeURIComponent(/\/behavior\/([^?]+)/.exec(page.url())[1]);
  assert.equal(behavior.split(':')[1], 'harbor-master', `${label}: new id ${behavior}`);
  // The id takes the namespace most of the workspace's documents use (the
  // whole server catalog is read in), not the server's name.
  const counts = {};
  for (const document of (await control('state')).documents) {
    const match = /^([a-z0-9_.-]+)[:/]/.exec(document.id);
    if (match) counts[match[1]] = (counts[match[1]] || 0) + 1;
  }
  const most = Object.entries(counts).reduce((best, next) => (next[1] > best[1] ? next : best))[0];
  assert.equal(behavior.split(':')[0], most, `${label}: new id ${behavior}`);
  const tab = page.locator('.animus-conv');
  await tab.locator('[data-block="main"]').waitFor();
  assert.equal(await page.locator('#animus-main-heading').textContent(), 'Harbor master', `${label}: the title is the plain name`);
  assert.equal(await tab.locator('.animus-conv-id').count(), 0, `${label}: no part ids in Simple`);

  // The start picker offers every click and key; a key start is written as its action.
  const start = page.locator('button[role="combobox"][id="conv-start"]');
  const startList = page.locator(`[id="${await start.getAttribute('aria-controls')}"]`);
  await start.click();
  await startList.waitFor({state: 'visible'});
  const starts = await startList.getByRole('option').allTextContents();
  for (const name of ['On click', 'On sneak click', 'On left-click', 'On sneak left-click', 'On drop key', 'On sneak drop key', 'On swap hands', 'On sneak swap hands', 'Player nearby', 'By command'])
    assert(starts.some(text => text.includes(name)), `${label}: start picker lacks ${name}: ${starts.join(', ')}`);
  await page.keyboard.press('Escape');
  await chooseText(start, 'On sneak drop key');
  await showView(page, 'code');
  assert.equal(JSON.parse(await page.locator('[data-testid="code-view"] textarea').inputValue()).triggers[0].action, 'sneak_drop');
  // Advanced from here: the view switch, part ids and the placeholder entries.
  await showView(page, 'conversation');
  await tab.locator('[data-block="main"]').waitFor();
  await chooseText(page.locator('button[role="combobox"][id="conv-start"]'), 'On click');

  // Start over: delete the starter question, then the parts it led to,
  // which nothing reaches now.
  await stepAction(page, 'main:1', 'Delete', 'Delete');
  await tab.locator('li[data-step="main:1"]').waitFor({state: 'detached'});
  for (const part of ['just-looking', 'goodbye']) {
    const section = tab.locator(`section[data-block="${part}"]`);
    await section.getByText('Nothing leads here.').waitFor();
    const menu = await openMenu(page, section.locator('[data-action-slot="more"]').first());
    await menu.getByRole('menuitem', {name: 'Delete part'}).click();
    await confirmWith(page, 'Delete part');
    await section.waitFor({state: 'detached'});
  }

  // Keyboard from here on. The placeholder comes from the step's menu.
  const first = page.locator('[id="conv-line:main:0"]');
  await first.focus();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type('Welcome to the harbor, ');
  await page.keyboard.press('Tab');
  assert.equal(await focused(page), 'conv-menu:main:0-button', `${label}: Tab reaches the step's menu`);
  await settle(page);
  await page.keyboard.press('Enter');
  const placeholders = page.locator('[role="menu"][data-arcane-state="open"]');
  await placeholders.getByRole('menuitem', {name: 'Insert player name'}).waitFor();
  await placeholders.getByRole('menuitem', {name: 'Insert player name'}).click();
  await waitFocus(page, 'conv-line:main:0');
  await page.keyboard.type('.');
  assert.equal(await first.inputValue(), 'Welcome to the harbor, {player_name}.');
  await page.keyboard.press('Enter');
  await waitFocus(page, 'conv-line:main:1');
  await page.keyboard.type('Where to? [Ferry] [Market] [Leave]');
  await page.keyboard.press('Enter');
  await waitFocus(page, 'conv-option:main:1:0');
  assert.equal(await page.locator('[id="conv-prompt:main:1"]').inputValue(), 'Where to?');
  const follow = {Ferry: 'The ferry leaves at dusk.', Market: 'The market is north.', Leave: 'Safe travels.'};
  for (const [index, answer] of ['Ferry', 'Market', 'Leave'].entries()) {
    if (index > 0) {
      await page.keyboard.press('ArrowDown');
      assert.equal(await focused(page), `conv-option:main:1:${index}`);
    }
    await page.keyboard.press('ControlOrMeta+Enter');
    await waitFocus(page, `conv-line:${answer.toLowerCase()}:0`);
    await page.keyboard.type(follow[answer]);
  }
  // A condition on the ferry: only players with the ticket permission
  // (a check only a server answers, so the Test can flip it) hear a line.
  await page.locator('[id="conv-line:ferry:0"]').focus();
  await page.keyboard.press('Enter');
  await waitFocus(page, 'conv-line:ferry:1');
  await page.keyboard.type('/if');
  await page.keyboard.press('Enter');
  const condition = tab.locator('li[data-step="ferry:1"]');
  await condition.waitFor();
  await chooseText(condition.locator('button[role="combobox"]').first(), 'Permission');
  await page.locator('[id="conv-if:ferry:1:permission"]').fill('town.ticket');
  const yes = await openMenu(page, condition.locator('[data-branch="then"] button[id^="conv-add:"]').first());
  await yes.getByRole('menuitem', {name: /^Say/}).click();
  await waitFocus(page, 'conv-line:ferry:1.then.0');
  await page.keyboard.type('Show your ticket.');
  // "Add step" lists every kind with a few words; a new Do step leaves the
  // document valid before anything is picked in it.
  const add = await openMenu(page, page.locator('[id="conv-add:market:1-button"]'));
  const kinds = await add.getByRole('menuitem').allTextContents();
  for (const kind of ['Say', 'Ask', 'If', 'Do', 'Go to', 'End']) assert(kinds.some(text => text.startsWith(kind)), `${label}: Add step lacks ${kind}: ${kinds.join(' | ')}`);
  await add.getByRole('menuitem', {name: /^Do/}).click();
  await tab.locator('[data-block="market"] li[data-kind="action"]').waitFor();
  await page.locator('.ui-page-header').getByText('Valid', {exact: true}).waitFor();
  await shot(page, `${label}-built`);

  // Every step and answer menu opens fully visible: each item is the
  // topmost thing at its own centre (a disabled item takes no pointer, so
  // it is checked by its box inside the menu's instead).
  const buttons = tab.locator('button[id^="conv-menu:"][id$="-button"]');
  const buttonCount = await buttons.count();
  assert.ok(buttonCount >= 10, `${label}: ${buttonCount} step menus`);
  for (let index = 0; index < buttonCount; index++) {
    const menu = await openMenu(page, buttons.nth(index));
    const hidden = await menu.evaluate(element => {
      const frame = element.getBoundingClientRect();
      const view = {right: window.innerWidth, bottom: window.innerHeight};
      return [...element.querySelectorAll('[role="menuitem"]')].filter(item => {
        const box = item.getBoundingClientRect();
        if (box.top < frame.top || box.bottom > frame.bottom + 0.5 || box.left < 0 || box.right > view.right || box.bottom > view.bottom) return true;
        if (item.getAttribute('data-disabled') === 'true') return false;
        const top = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
        return !top || !item.contains(top);
      }).map(item => item.textContent.trim());
    });
    assert.deepEqual(hidden, [], `${label}: menu ${index} items covered or clipped`);
    await closeMenu(page, menu);
  }

  // Duplicate, then delete the copy (it holds text, so Delete asks first).
  const before = await tab.locator('[data-block="market"] li[data-kind]').count();
  await stepAction(page, 'market:0', /^Duplicate/);
  await page.waitForFunction(([count]) => document.querySelectorAll('.animus-conv [data-block="market"] li[data-kind]').length === count, [before + 1]);
  assert.equal(await page.locator('[id="conv-line:market:1"]').inputValue(), follow.Market);
  await stepAction(page, 'market:1', 'Delete', 'Delete');
  await page.waitForFunction(([count]) => document.querySelectorAll('.animus-conv [data-block="market"] li[data-kind]').length === count, [before]);

  // A step menu names its keys as the shortcut sheet does, cuts no label,
  // and is never drawn over the top bar once its line scrolls away.
  const scroller = page.locator('#animus-main');
  await scroller.evaluate(element => { element.scrollTop = 0; });
  const stepMenu = await openMenu(page, page.locator('[id="conv-menu:main:0-button"]'));
  // Phones have no keys to press, so a phone's menus name none.
  const phoneMenu = await isCompact(page);
  assert.equal((await stepMenu.getByRole('menuitem', {name: /Move down/}).textContent()).includes(mac ? 'Option+Down' : 'Alt+Down'), !phoneMenu, `${label}: menu key names`);
  assert.equal((await stepMenu.getByRole('menuitem', {name: /Duplicate/}).textContent()).includes(mac ? 'Cmd+D' : 'Ctrl+D'), !phoneMenu, `${label}: menu key names`);
  await assertNothingCut(stepMenu, '[role="menuitem"] span', `${label} step menu`);
  await scroller.evaluate(element => { element.scrollTop = element.scrollHeight; });
  await page.waitForFunction(() => {
    const menu = document.querySelector('[role="menu"][data-arcane-state="open"]');
    const bar = document.querySelector('.ui-topbar');
    if (!menu || !bar) return true;
    const box = menu.getBoundingClientRect();
    return box.height === 0 || box.top >= bar.getBoundingClientRect().bottom;
  }, null, {timeout: 2000}).catch(() => assert.fail(`${label}: an open step menu is drawn over the top bar after its line scrolled away`));
  if (await stepMenu.isVisible()) await closeMenu(page, stepMenu);
  await scroller.evaluate(element => { element.scrollTop = 0; });

  // The written behavior: the start, one trigger per answer, ids kept.
  await showView(page, 'code');
  const json = JSON.parse(await page.locator('[data-testid="code-view"] textarea').inputValue());
  assert.deepEqual(json.triggers.map(trigger => [trigger.event, trigger.choice || '', trigger.routine || '']), [
    ['interaction', '', ''],
    ['dialogue_choice', 'ferry', 'ferry'],
    ['dialogue_choice', 'market', 'market'],
    ['dialogue_choice', 'leave', 'leave'],
  ]);
  assert.equal(json.routines.main.steps[1].arguments.prompt, 'Where to?');
  assert.equal(json.routines.ferry.steps[1].capability, 'animus:has-permission');
  assert.equal(json.routines.ferry.steps[1].arguments.permission, 'town.ticket');
  assert.equal(json.routines.ferry.steps[1].then.arguments?.text ?? json.routines.ferry.steps[1].then.steps?.[0]?.arguments?.text, 'Show your ticket.');
  await showView(page, 'conversation');
  await tab.locator('[data-block="main"]').waitFor();

  // Test: Run it, answer, flip the condition, run it again.
  let pane = await testPane(page);
  // One Run button; no start chips that also start a run.
  assert.equal(await pane.getByRole('group', {name: 'Pick how it starts'}).count(), 0, `${label}: no start chips`);
  await pickStart(page, pane, 'On click');
  const chat = pane.getByRole('list', {name: 'Chat'});
  await chat.getByText('Welcome to the harbor, Player.').waitFor();
  await chat.locator('[data-answer="ferry"]').click();
  await chat.getByText('The ferry leaves at dusk.').waitFor();
  // The permission is assumed true; flipping it skips the ticket line.
  const ticket = chat.locator('.animus-test-assume', {hasText: 'town.ticket'}).getByRole('switch');
  await chat.getByText('Show your ticket.').waitFor();
  assert.equal(await ticket.getAttribute('aria-checked'), 'true', `${label}: the permission is assumed`);
  await ticket.click();
  await chat.getByText('Show your ticket.').waitFor({state: 'detached'});
  assert.equal(await chat.locator('.animus-test-assume', {hasText: 'town.ticket'}).getByRole('switch').getAttribute('aria-checked'), 'false');
  await shot(page, `${label}-test`);
  // Run again: the run starts over, the question open again.
  await pickStart(page, pane, 'On click');
  await chat.getByText('The ferry leaves at dusk.').waitFor({state: 'detached'});
  await chat.locator('[data-answer="leave"]').click();
  await chat.getByText('Safe travels.').waitFor();
  await chat.getByText('End', {exact: true}).waitFor();
  pane = null;
  if (await isCompact(page)) await hidePreview(page);

  // Attach to the NPC: one searchable picker over every NPC; the pick
  // saves at once and the NPC is listed with Remove.
  const attach = tab.locator('[data-testid="conv-attach"]');
  await choose(page.locator('button[role="combobox"][id="conv-attach"]'), `npc:${npc}`);
  await attach.locator(`[data-target="npc:${npc}"]`).waitFor();
  await attach.locator(`[data-target="npc:${npc}"]`).getByRole('button', {name: /^Remove /}).waitFor();

  // Apply both documents from the save bar.
  await saveState(page).filter({hasText: '2 changes not applied'}).waitFor();
  await saveAction(page, 'apply').click();
  await saveState(page).filter({hasText: /Applied 2 changes|Up to date/}).waitFor({timeout: 20000});
  const stored = await control(`document?kind=behavior&id=${encodeURIComponent(behavior)}`);
  assert.equal(stored.source.routines.leave.steps[0].arguments.text, 'Safe travels.');
  const greeter = await control(`document?kind=npc&id=${encodeURIComponent(npc)}`);
  assert.ok(greeter.source.behaviors.includes(behavior), 'the NPC lists the conversation');

  // Reopen in Simple: a fresh load shows the conversation again.
  await setMode(page, 'simple');
  await page.reload();
  await waitOnline(page);
  await openFromSidebar(page, behavior);
  await page.waitForFunction(() => location.hash.includes('/behavior/'));
  await tab.locator('[data-block="main"]').waitFor();
  assert.equal(await page.locator('[id="conv-option:main:1:2"]').inputValue(), 'Leave');
  assert.equal(await page.locator('[id="conv-line:market:0"]').inputValue(), 'The market is north.');
  assert.ok(await tab.locator(`[data-target="npc:${npc}"]`).isVisible(), `${label}: the NPC is still listed`);
  await shot(page, `${label}-reopened`);
  assert.deepEqual(errors, [], label);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  const only = process.env.ANIMUS_ONLY;
  try {
    for (const theme of ['dark', 'light']) {
      for (const [viewport, form] of [[{width: 1440, height: 900}, 'desktop'], [{width: 390, height: 844}, 'phone']]) {
        const label = `conversation-${form}-${theme}`;
        if (only && !label.includes(only)) continue;
        await run(browser, viewport, label, theme);
      }
    }
    console.log('conversations_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
