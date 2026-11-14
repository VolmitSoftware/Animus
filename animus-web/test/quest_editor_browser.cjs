// The quest page against tool/fake_server.dart (default --data) at 1440
// (dark and light), 768 and 390 px: pair, make a quest from New with a
// giver, fill it in Simple (one tab strip, no Advanced fields), set
// Advanced fields in Advanced and see Simple count them and keep them
// through Simple edits, build two stages (collect and kill, then bring to
// the baker), reorder stages from their "..." menus, catch a repeated
// objective id, add rewards, read the offer and every journal state in the
// preview, apply, read the quest back from the fake server with every
// Advanced key as written, then undo the apply on the server. No console
// errors, no native selects and no sideways scrolling.
//
//   python3 tool/serve_site.py --port <web port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<web port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<web port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/quest_editor_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {choose, valueOf} = require('./support/select.cjs');
const {setMode, waitOnline, saveAction, saveState, showPreview} = require('./support/shell.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

async function check(page, name) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  if (overflow > 1) {
    const wide = await page.evaluate(() => [...document.querySelectorAll('body *')]
      .filter(el => el.getBoundingClientRect().right > document.documentElement.clientWidth + 1)
      .slice(0, 6)
      .map(el => `${el.tagName.toLowerCase()}.${String(el.className).trim().split(/\s+/).slice(0, 2).join('.')}`));
    assert.fail(`${name}: ${overflow}px of sideways scrolling from ${wide.join(', ')}`);
  }
  assert.equal(await page.locator('#animus-main select').count(), 0, `${name}: a native select`);
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: true});
}

const settle = page => page.waitForTimeout(250);

/** The preview, shown first on phones where it waits behind the header's Preview. */
async function preview(page) {
  await showPreview(page);
  const frame = page.locator('.animus-quest-preview');
  await frame.waitFor();
  return frame;
}

/** Picks [label] in a row's "..." menu named [menu] inside [scope]. */
async function rowMenu(page, scope, menu, label) {
  await scope.getByRole('button', {name: menu, exact: true}).click();
  await page.getByRole('menuitem', {name: label, exact: true}).click();
}

/** The journal entry's lines as shown. */
const journal = page => page.locator('[data-quest="journal-entry"]').innerText();

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
  await page.getByTestId('pair-submit').waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await waitOnline(page);
  const workspace = await page.evaluate(() => `#/workspace/server-${location.hash.split('/')[2]}`);
  await page.locator('[data-testid="home-count"]').first().waitFor();

  // New quest with a giver; the workspace fills from the catalog by itself.
  await page.goto(`${site}/${workspace}/new/quest`);
  await page.getByLabel('Title', {exact: true}).fill('&6Bread for the baker');
  await choose(page.locator('#animus-new-quest-giver'), 'greeter');
  await check(page, `${label}-new`);
  await page.getByRole('button', {name: 'Create', exact: true}).click();
  await page.waitForFunction(() => location.hash.includes('/quest/'));
  // Simple shows no id field: the new address carries it.
  const id = decodeURIComponent((await page.evaluate(() => location.hash)).split('/quest/')[1].split('?')[0]);
  assert.match(id, /^[a-z0-9_-]+:bread-for-the-baker$/, 'the id follows the title');
  const form = page.locator('.animus-gui-form');
  await form.getByRole('tab', {name: 'Quest', exact: true}).waitFor();

  // One tab strip on the page; Simple shows the common fields only.
  assert.equal(await page.locator('#animus-main [role="tablist"]').count(), 1, `${label}: one tab strip`);
  assert.deepEqual(await form.getByRole('tab').allInnerTexts(), ['Quest', 'Stages', 'Rewards']);
  assert.equal(await form.getByText('Turn in', {exact: true}).count(), 0, `${label}: Turn in is Advanced`);
  await form.getByLabel('Quest description', {exact: true}).fill('The baker ran out of flour.');
  await settle(page);
  let shown = await preview(page);
  await shown.locator('.animus-quest-offer', {hasText: 'Greeter »'}).waitFor();
  await shown.locator('.animus-quest-offer', {hasText: 'The baker ran out of flour.'}).waitFor();
  assert.equal(await shown.getByText("quest('player'").count(), 0, `${label}: no expression under the preview`);
  await check(page, `${label}-simple`);

  // Advanced fields set in Advanced.
  await setMode(page, 'advanced');
  await choose(form.locator('[id="animus-at--turnIn"]'), 'shopkeeper');
  await form.getByLabel('Required permission', {exact: true}).fill('town.quests.bread');
  await settle(page);
  await choose(form.locator('[id="animus-at--repeat"]'), 'daily');
  await form.locator('summary', {hasText: 'Messages'}).click();
  await form.getByLabel('Offer message', {exact: true}).fill('Hi {player}, about {quest}');
  await settle(page);
  await check(page, `${label}-advanced`);

  // Back in Simple they are counted, kept, and Show brings them back.
  await setMode(page, 'simple');
  await form.getByText('4 advanced settings', {exact: true}).waitFor();
  assert.equal(await form.getByText('Turn in', {exact: true}).count(), 0);
  await form.getByLabel('Quest description', {exact: true}).fill('The baker ran out of flour again.');
  await settle(page);
  await form.getByLabel('Quest description', {exact: true}).fill('The baker ran out of flour.');
  await settle(page);

  // Stage 1: the starting collect objective plus two zombies.
  await form.getByRole('tab', {name: 'Stages', exact: true}).click();
  const stage1 = form.locator('[data-stage="0"]');
  assert.equal(await stage1.getByText('Order', {exact: true}).count(), 0, `${label}: stage order is Advanced`);
  await stage1.getByRole('button', {name: 'Add objective'}).click();
  await page.getByRole('menuitem', {name: /^Kill mobs or an NPC/}).click();
  const kill = stage1.locator('[data-objective="0.1"]');
  await kill.getByLabel('Stage 1 objective 2 amount', {exact: true}).fill('2');
  await settle(page);
  assert.equal(await kill.getByLabel('Stage 1 objective 2 mob type', {exact: true}).inputValue(), 'minecraft:zombie');

  // Stage 2: a talk objective turned into bringing bread to the baker.
  await form.getByRole('button', {name: 'Add stage'}).click();
  const stage2 = form.locator('[data-stage="1"]');
  await stage2.waitFor();
  await stage2.locator('[data-objective="1.0"] summary').first().click();
  await choose(stage2.locator('[id="animus-at--stages-1-objectives-0-type"]'), 'bring');
  await choose(stage2.locator('[id="animus-at--stages-1-objectives-0-npc"]'), 'shopkeeper');
  await stage2.getByLabel('Stage 2 objective 1 item', {exact: true}).fill('minecraft:bread');
  await settle(page);
  await stage2.getByLabel('Stage 2 objective 1 item', {exact: true}).blur();
  await settle(page);
  assert.equal(await stage2.getByLabel('Stage 2 objective 1 item', {exact: true}).inputValue(), 'Bread', `${label}: Simple names the item to bring`);
  await stage2.getByLabel('Stage 2 objective 1 amount', {exact: true}).fill('3');
  await settle(page);
  await stage2.getByLabel('Stage 2 journal text', {exact: true}).fill('Deliver the bread');
  await settle(page);
  await form.getByText('2 stages', {exact: true}).waitFor();

  // Reorder from the rows' menus: stage 2 up, then back down.
  await rowMenu(page, stage2, 'Actions for stage 2', 'Move up');
  await form.locator('[data-stage="0"] > .animus-rows-line .animus-quest-toggle-detail', {hasText: 'Deliver the bread'}).waitFor();
  await rowMenu(page, form.locator('[data-stage="0"]'), 'Actions for stage 1', 'Move down');
  await form.locator('[data-stage="1"] > .animus-rows-line .animus-quest-toggle-detail', {hasText: 'Deliver the bread'}).waitFor();

  // A repeated objective id is an error at the field and on the tab.
  for (let i = 0; i < 2; i++) {
    await stage1.getByRole('button', {name: 'Add objective'}).click();
    await page.getByRole('menuitem', {name: /^Custom/}).click();
  }
  await stage1.getByText('another objective of this stage is already custom').waitFor();
  await form.getByRole('tab', {name: 'Stages (1)', exact: true}).waitFor();
  await check(page, `${label}-stages`);
  for (const number of [4, 3]) {
    await rowMenu(page, stage1, `Actions for objective ${number}`, 'Delete');
    await page.getByRole('alertdialog').getByRole('button', {name: 'Delete', exact: true}).click();
    await stage1.locator(`[data-objective="0.${number - 1}"]`).waitFor({state: 'detached'});
  }
  await form.getByRole('tab', {name: 'Stages', exact: true}).waitFor();

  // Rewards: emeralds and, behind More, a command that notes the allow-list.
  await form.getByRole('tab', {name: 'Rewards', exact: true}).click();
  await form.getByRole('button', {name: 'Add item'}).click();
  assert.equal(await form.getByLabel('Reward item 1', {exact: true}).inputValue(), 'Emerald', `${label}: Simple names the reward item`);
  await form.getByLabel('Reward item 1 amount', {exact: true}).fill('4');
  await settle(page);
  await form.locator('summary', {hasText: 'More'}).click();
  await form.getByRole('button', {name: 'Add command'}).click();
  await form.getByLabel('Reward command 1', {exact: true}).fill('give {player} diamond 1');
  await settle(page);
  await form.getByText('Commands: run only when the quest or its namespace is on the command allow-list in config.toml').waitFor();
  assert.equal(await form.getByText('Flags', {exact: true}).count(), 0, `${label}: flags are Advanced`);
  await setMode(page, 'advanced');
  await form.getByRole('button', {name: 'Add flag'}).click();
  await form.getByLabel('Reward flag 1 name', {exact: true}).waitFor();
  await setMode(page, 'simple');
  await form.getByText('1 advanced setting', {exact: true}).waitFor();
  await check(page, `${label}-rewards`);

  // The journal at each state, picked in the preview's header.
  shown = await preview(page);
  const state = page.locator('#animus-quest-journal-state');
  assert.equal(await valueOf(state), '0');
  let text = await journal(page);
  for (const line of ['Bread for the baker', 'The baker ran out of flour.', 'Stage 1/2', 'Gather wheat', '• Collect 10 wheat 0/10', '• Defeat 2 zombie 0/2']) {
    assert.ok(text.includes(line), `${label}: journal has "${line}" in ${JSON.stringify(text)}`);
  }
  await choose(state, '1');
  text = await journal(page);
  for (const line of ['Stage 2/2', 'Deliver the bread', '• Bring 3 bread to Baker 0/3']) {
    assert.ok(text.includes(line), `${label}: journal has "${line}" in ${JSON.stringify(text)}`);
  }
  await choose(state, '2');
  assert.ok((await journal(page)).includes('Finished'));
  await shown.locator('.animus-quest-offer', {hasText: 'Hi Steve, about Bread for the baker'}).waitFor();

  // Show reveals the Advanced fields on this page while the mode stays Simple.
  await form.getByRole('tab', {name: 'Quest', exact: true}).click();
  await form.getByRole('button', {name: 'Show', exact: true}).click();
  await form.getByText('Turn in', {exact: true}).waitFor();
  assert.equal(await valueOf(form.locator('[id="animus-at--turnIn"]')), 'shopkeeper');
  assert.equal(await page.evaluate(() => window.localStorage.getItem('animus.mode')), 'simple');

  // Apply: the server stores the quest as written, Advanced keys included.
  await saveAction(page, 'apply').click();
  await page.waitForFunction(() => /^Applied 1 change/.test(document.querySelector('.ui-save-bar .ui-save-message')?.textContent || ''), null, {timeout: 15000});
  assert.match(await saveState(page).innerText(), /^Applied 1 change/);
  const stored = (await control(`document?kind=quest&id=${encodeURIComponent(id)}`)).source;
  assert.equal(stored.title, '&6Bread for the baker');
  assert.equal(stored.description, 'The baker ran out of flour.');
  assert.deepEqual(stored.givers, ['greeter']);
  assert.equal(stored.turnIn, 'shopkeeper');
  assert.deepEqual(stored.requirements, {permission: 'town.quests.bread'});
  assert.deepEqual(stored.repeat, {mode: 'daily'});
  assert.deepEqual(stored.messages, {offer: 'Hi {player}, about {quest}'});
  assert.deepEqual(stored.stages.map(stage => stage.objectives.map(objective => objective.type)), [['collect', 'kill'], ['bring']]);
  assert.deepEqual(stored.stages[1].objectives[0], {type: 'bring', npc: 'shopkeeper', item: {id: 'minecraft:bread'}, amount: 3});
  assert.equal(stored.stages[0].objectives[1].amount, 2);
  assert.deepEqual(stored.rewards.items, [{item: {id: 'minecraft:emerald'}, amount: 4}]);
  assert.deepEqual(stored.rewards.commands, [{command: 'give {player} diamond 1'}]);
  assert.deepEqual(stored.rewards.flags, [{name: 'done'}]);
  assert.equal(stored.rewards.experience, 50);
  await check(page, `${label}-applied`);

  // Undo the apply on the server: the quest is gone again.
  // Right after an apply the save bar offers its Undo.
  await page.locator('.ui-save-bar [data-save-action="undoApply"]').click();
  await page.getByRole('alertdialog').getByText('Undo the last apply?').waitFor();
  await page.getByRole('alertdialog').getByRole('button', {name: 'Undo on the server'}).click();
  let gone = false;
  for (let tries = 0; tries < 50 && !gone; tries++) {
    gone = !(await control('state')).documents.some(document => document.kind === 'quest' && document.id === id);
    if (!gone) await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(gone, 'undo removes the quest');

  assert.deepEqual(errors, [], label);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    for (const [width, theme] of [[1440, 'dark'], [1440, 'light'], [768, 'dark'], [390, 'dark']]) {
      await run(browser, {width, height: 900}, `quest-${width}-${theme}`, theme);
      console.log(`quest-${width}-${theme}: ok`);
    }
    console.log('quest_editor_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
