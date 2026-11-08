// The Conversation tab against tool/fake_server.dart at 1440 and 390 px in
// both themes: pair, open the greeter NPC, make a conversation from the
// sidebar, build a question with three answers and their follow-ups by
// keyboard (a placeholder from the picker, Enter for the next line, inline
// [answers], Ctrl+Enter for what follows, arrows between fields), check that
// a step menu names keys per platform and closes when its line scrolls away
// and that the new id takes the opened NPC's namespace, play it in
// the preview with a condition flipped, attach it to the NPC, apply both
// documents, read them back from the fake server and reopen the behavior.
// No console errors, no native selects and no sideways scrolling.
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
const {openFromSidebar, sidebar} = require('./support/shell.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;
const npc = 'examples/greeter';

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
  assert.equal(await page.locator('.animus-conv select').count(), 0, `${name}: a native select in the conversation tab`);
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: true});
}

async function notice(page, text) {
  await page.locator('.animus-live-log li', {hasText: text}).first().waitFor({state: 'attached', timeout: 15000});
}

/// The id of the field focused now.
const focused = page => page.evaluate(() => document.activeElement && document.activeElement.id);

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

  await page.goto(`${site}/#/pair/${code}`);
  await page.locator('[data-testid="pair-fingerprint"]', {hasText: 'sha256:'}).waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await page.locator('.animus-live .animus-pill[data-phase="online"]').waitFor({timeout: 20000});
  await page.getByText(/documents on the server/).waitFor();
  await page.getByRole('checkbox', {name: npc, exact: true}).check();
  await page.getByRole('button', {name: 'Open 1'}).click();
  await notice(page, 'Opened 1 document');

  // New conversation from the sidebar: the Conversation template is picked
  // and the behavior opens on its Conversation tab.
  await (await sidebar(page)).getByRole('link', {name: 'New conversation'}).click();
  await page.getByRole('heading', {name: 'New behavior'}).waitFor();
  await page.getByRole('button', {name: 'Create behavior'}).click();
  await page.waitForFunction(() => location.hash.includes('/behavior/') && location.hash.includes('view=conversation'));
  const behavior = decodeURIComponent(/\/behavior\/([^?]+)/.exec(page.url())[1]);
  // The new id takes the namespace the opened documents use, not the server's name.
  assert.equal(behavior.split(':')[0], npc.split('/')[0], `${label}: new id ${behavior}`);
  const tab = page.locator('.animus-conv');
  await tab.locator('[data-block="main"]').waitFor();

  // The start picker offers every click and key; a key start is written as its action.
  await page.locator('[id="conv-start"]').click();
  const listbox = page.locator('[id="conv-start-listbox"]');
  await listbox.getByRole('option', {name: 'Walk near'}).waitFor();
  const starts = await listbox.getByRole('option').allTextContents();
  for (const label of ['Right-click', 'Sneak + right-click', 'Left-click', 'Sneak + left-click', 'Drop key (Q)', 'Sneak + drop key', 'Swap hands (F)', 'Sneak + swap hands', 'Walk near', 'Command'])
    assert(starts.some(text => text.includes(label)), `start picker lacks ${label}: ${starts.join(', ')}`);
  await listbox.getByRole('option', {name: 'Sneak + drop key'}).click();
  await page.getByRole('tab', {name: 'Code'}).click();
  assert.equal(JSON.parse(await page.locator('.animus-code textarea').inputValue()).triggers[0].action, 'sneak_drop');
  await page.getByRole('tab', {name: 'Conversation'}).click();
  await page.locator('[id="conv-start"]').click();
  await listbox.getByRole('option', {name: 'Right-click', exact: true}).click();

  // Start over: drop the starter question and the blocks it led to.
  const question = tab.locator('[data-block="main"] > .animus-conv-list > .animus-conv-steps > li[data-kind="choices"]');
  await question.locator('.animus-conv-row').first().getByRole('button', {name: 'Step options'}).click();
  await page.getByRole('menuitem', {name: 'Delete'}).click();
  await tab.getByRole('button', {name: 'Delete block just-looking'}).click();
  await tab.getByRole('button', {name: 'Delete block goodbye'}).click();
  await tab.locator('[data-block="goodbye"]').waitFor({state: 'detached'});

  // Keyboard from here on.
  const first = page.locator('[id="conv-line:main:0"]');
  await first.focus();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type('Welcome to the harbor, ');
  await page.keyboard.press('Tab');
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => document.activeElement && document.activeElement.textContent.startsWith('Player name'));
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => document.activeElement && document.activeElement.id === 'conv-line:main:0');
  await page.keyboard.type('.');
  assert.equal(await first.inputValue(), 'Welcome to the harbor, {player_name}.');
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => document.activeElement && document.activeElement.id === 'conv-line:main:1');
  await page.keyboard.type('Where to? [Ferry] [Market] [Leave]');
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => document.activeElement && document.activeElement.id === 'conv-option:main:1:0');
  assert.equal(await page.locator('[id="conv-prompt:main:1"]').inputValue(), 'Where to?');
  const follow = {Ferry: 'The ferry leaves at dusk.', Market: 'The market is north.', Leave: 'Safe travels.'};
  for (const [index, answer] of ['Ferry', 'Market', 'Leave'].entries()) {
    if (index > 0) {
      await page.keyboard.press('ArrowDown');
      assert.equal(await focused(page), `conv-option:main:1:${index}`);
    }
    await page.keyboard.press('ControlOrMeta+Enter');
    const block = answer.toLowerCase();
    await page.waitForFunction(id => document.activeElement && document.activeElement.id === id, `conv-line:${block}:0`);
    await page.keyboard.type(follow[answer]);
  }
  // A condition on the ferry: only with a ticket.
  await page.locator('[id="conv-line:ferry:0"]').focus();
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => document.activeElement && document.activeElement.id === 'conv-line:ferry:1');
  await page.keyboard.type('/if');
  await page.keyboard.press('Enter');
  const condition = tab.locator('[data-block="ferry"] li[data-kind="branch"]');
  await condition.waitFor();
  await page.locator('[id="conv-if:ferry:1:name"]').fill('ticket');
  // A new action step is valid as added: no document error before a pick.
  await tab.getByRole('button', {name: 'Add a step to market'}).click();
  await page.getByRole('menuitem', {name: 'Action'}).click();
  await tab.locator('[data-block="market"] li[data-kind="action"]').waitFor();
  await page.waitForFunction(() => !document.querySelector('[data-conv-errors]'));
  assert.equal(await page.locator('.animus-diagnostics li.is-error').count(), 0, 'a new action step leaves the document valid');
  await shot(page, `${label}-built`);

  // A step menu names its keys as the shortcut sheet does, and closes once
  // its line scrolls away instead of floating over the top bar.
  const mac = await page.evaluate(() => /Mac/.test(navigator.platform));
  await page.evaluate(() => window.scrollTo(0, 0));
  await tab.locator('[data-block="main"] li[data-kind="line"]').first().locator('.animus-conv-row').first().getByRole('button', {name: 'Step options'}).click();
  const stepMenu = page.getByRole('menu');
  await stepMenu.waitFor();
  assert.equal(await stepMenu.getByRole('menuitem', {name: /Move down/}).locator('.animus-menu-hint').textContent(), mac ? 'Option+Down' : 'Alt+Down', `${label}: menu key names`);
  await assertNothingCut(stepMenu, '.animus-menu-item > span', `${label} step menu`);
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
  await stepMenu.waitFor({state: 'detached', timeout: 2000});
  await page.evaluate(() => window.scrollTo(0, 0));

  // The written behavior: the start, one trigger per answer, ids kept.
  await page.waitForFunction(() => !document.querySelector('[data-conv-errors]'));
  await page.getByRole('tab', {name: 'Code'}).click();
  const json = JSON.parse(await page.locator('.animus-code textarea').inputValue());
  assert.deepEqual(json.triggers.map(trigger => [trigger.event, trigger.choice || '', trigger.routine || '']), [
    ['interaction', '', ''],
    ['dialogue_choice', 'ferry', 'ferry'],
    ['dialogue_choice', 'market', 'market'],
    ['dialogue_choice', 'leave', 'leave'],
  ]);
  assert.equal(json.routines.main.steps[1].arguments.prompt, 'Where to?');
  assert.equal(json.routines.ferry.steps[1].capability, 'animus:flag-state');
  await page.getByRole('tab', {name: 'Conversation'}).click();

  // Preview: answer, flip the condition, restart.
  const chat = tab.locator('.animus-conv-chat');
  await chat.getByText('Welcome to the harbor, Steve.').waitFor();
  await chat.getByRole('button', {name: 'Ferry'}).click();
  await chat.getByText('The ferry leaves at dusk.').waitFor();
  await chat.getByRole('checkbox', {name: /If flag ticket/}).waitFor();
  await tab.getByRole('button', {name: 'Restart'}).click();
  await chat.getByRole('button', {name: 'Leave'}).click();
  await chat.getByText('Safe travels.').waitFor();
  await chat.getByText('End of conversation').waitFor();
  await shot(page, `${label}-preview`);

  // Attach to the NPC and apply both documents.
  const attach = tab.locator('.animus-conv-attach');
  await attach.getByRole('checkbox', {name: new RegExp(npc)}).check();
  await attach.getByRole('button', {name: 'Attach (1)'}).click();
  await attach.getByRole('heading', {name: 'NPCs (1)'}).waitFor();
  await page.getByRole('button', {name: 'Apply 2'}).click();
  await notice(page, 'Applied 2 documents');
  const stored = await control(`document?kind=behavior&id=${encodeURIComponent(behavior)}`);
  assert.equal(stored.source.routines.leave.steps[0].arguments.text, 'Safe travels.');
  const greeter = await control(`document?kind=npc&id=${encodeURIComponent(npc)}`);
  assert.ok(greeter.source.behaviors.includes(behavior), 'the NPC lists the conversation');

  // Reopen: a fresh load shows the conversation again.
  await page.reload();
  await page.locator('.animus-live .animus-pill[data-phase="online"]').waitFor({timeout: 20000});
  await openFromSidebar(page, behavior);
  await page.waitForFunction(() => location.hash.includes('view=conversation'));
  assert.equal(await page.locator('[id="conv-option:main:1:2"]').inputValue(), 'Leave');
  assert.equal(await page.locator('[id="conv-line:market:0"]').inputValue(), 'The market is north.');
  await shot(page, `${label}-reopened`);
  assert.deepEqual(errors, [], label);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    for (const theme of ['dark', 'light']) {
      await run(browser, {width: 1440, height: 900}, `conversation-desktop-${theme}`, theme);
      await run(browser, {width: 390, height: 844}, `conversation-phone-${theme}`, theme);
    }
    console.log('conversations_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
