// The quest editor against tool/fake_server.dart (default --data) at 1440
// and 390 px, dark and light at 1440: pair, open two NPCs, make a quest
// from New with a giver, fill its settings, build two stages (collect and
// kill, then bring to the baker), reorder stages, catch a repeated
// objective id, add rewards, read the offer and every journal state in the
// preview, apply, read the quest back from the fake server, then undo the
// apply on the server. No console errors, no native selects and no
// sideways scrolling.
//
//   python3 tool/serve_site.py --port <web port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<web port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<web port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/quest_editor_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {choose} = require('./support/select.cjs');

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
  assert.equal(await page.locator('.animus-quest-editor select, .animus-new select').count(), 0, `${name}: a native select`);
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: true});
}

async function notice(page, text) {
  await page.locator('.animus-live-log li', {hasText: text}).first().waitFor({state: 'attached', timeout: 15000});
}

const settle = page => page.waitForTimeout(250);

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
  await page.locator('[data-testid="pair-fingerprint"]', {hasText: 'sha256:'}).waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await page.locator('.animus-live .animus-pill[data-phase="online"]').waitFor({timeout: 20000});
  await page.getByText(/documents on the server/).waitFor();
  await page.getByRole('checkbox', {name: 'examples/greeter', exact: true}).check();
  await page.getByRole('checkbox', {name: 'examples/shopkeeper', exact: true}).check();
  await page.getByRole('button', {name: 'Open 2'}).click();
  await notice(page, 'Opened 2 documents');

  // New quest with a giver.
  const workspace = await page.evaluate(() => location.hash.split('/').slice(0, 3).join('/'));
  await page.goto(`${site}/${workspace}/new/quest`);
  await page.getByLabel('Title', {exact: true}).fill('&6Bread for the baker');
  await choose(page.locator('#animus-new-quest-giver'), 'greeter');
  const id = await page.getByLabel('Id', {exact: true}).inputValue();
  assert.match(id, /^[a-z0-9_-]+:bread-for-the-baker$/, 'the id follows the title');
  await check(page, `${label}-new`);
  await page.getByRole('button', {name: /^Create /}).click();
  await page.waitForFunction(() => location.hash.includes('/quest/'));
  const editor = page.locator('.animus-quest-editor');
  await editor.waitFor();

  // Settings: description and the baker as turn in.
  const form = page.locator('.animus-quest-form');
  await form.getByLabel('Quest description', {exact: true}).fill('The baker ran out of flour.');
  await settle(page);
  await choose(form.locator('[id="animus-at--turnIn"]'), 'shopkeeper');
  await page.locator('.animus-quest-offer', {hasText: 'Greeter »'}).waitFor();
  await page.locator('.animus-quest-offer', {hasText: 'The baker ran out of flour.'}).waitFor();
  await check(page, `${label}-settings`);

  // Stage 1: the starting collect objective plus two zombies.
  await form.getByRole('tab', {name: /^Stages \(1\)/}).click();
  const stage1 = form.locator('[data-stage="0"]');
  await stage1.getByRole('button', {name: '+ Add objective'}).click();
  await page.getByRole('menuitem', {name: 'Kill mobs or an NPC'}).click();
  const kill = stage1.locator('[data-objective="0.1"]');
  await kill.getByLabel('Stage 1 objective 2 amount', {exact: true}).fill('2');
  await settle(page);
  assert.equal(await kill.getByLabel('Stage 1 objective 2 mob type', {exact: true}).inputValue(), 'minecraft:zombie');

  // Stage 2: a talk objective turned into bringing bread to the baker.
  await form.getByRole('button', {name: '+ Add stage'}).click();
  const stage2 = form.locator('[data-stage="1"]');
  await stage2.waitFor();
  await choose(stage2.locator('[id="animus-at--stages-1-objectives-0-type"]'), 'bring');
  await choose(stage2.locator('[id="animus-at--stages-1-objectives-0-npc"]'), 'shopkeeper');
  await stage2.getByLabel('Stage 2 objective 1 item', {exact: true}).fill('minecraft:bread');
  await settle(page);
  await stage2.getByLabel('Stage 2 objective 1 amount', {exact: true}).fill('3');
  await settle(page);
  await stage2.getByLabel('Stage 2 journal text', {exact: true}).fill('Deliver the bread');
  await settle(page);
  await form.getByRole('tab', {name: /^Stages \(2\)/}).waitFor();

  // Reorder: stage 2 up, then back down.
  await stage2.getByRole('button', {name: 'Move stage 2 earlier'}).click();
  await form.locator('[data-stage="0"] .animus-quest-stage-title', {hasText: 'Deliver the bread'}).waitFor();
  await form.locator('[data-stage="0"]').getByRole('button', {name: 'Move stage 1 later'}).click();
  await form.locator('[data-stage="1"] .animus-quest-stage-title', {hasText: 'Deliver the bread'}).waitFor();

  // A repeated objective id is an error at the field and on the tab.
  await stage1.getByRole('button', {name: '+ Add objective'}).click();
  await page.getByRole('menuitem', {name: 'Custom'}).click();
  await stage1.getByRole('button', {name: '+ Add objective'}).click();
  await page.getByRole('menuitem', {name: 'Custom'}).click();
  await stage1.getByText('another objective of this stage is already custom').waitFor();
  await form.getByRole('tab', {name: 'Stages (2) (!)'}).waitFor();
  await check(page, `${label}-stages`);
  await stage1.getByRole('button', {name: 'Remove stage 1 objective 4'}).click();
  await stage1.getByRole('button', {name: 'Remove stage 1 objective 3'}).click();
  await form.getByRole('tab', {name: 'Stages (2)', exact: true}).waitFor();

  // Rewards: emeralds and a command, which notes the allow-list.
  await form.getByRole('tab', {name: /^Rewards/}).click();
  await form.getByRole('button', {name: '+ Add item'}).click();
  await form.getByLabel('Reward item 1 amount', {exact: true}).fill('4');
  await settle(page);
  await form.getByRole('button', {name: '+ Add command'}).click();
  await form.getByLabel('Reward command 1', {exact: true}).fill('give {player} diamond 1');
  await settle(page);
  await form.getByText(/commands run only when the quest id or its namespace is on the command allow-list/).waitFor();

  // The journal at each state.
  const preview = page.locator('.animus-quest-preview');
  await preview.getByRole('tab', {name: 'Stage 1', exact: true}).click();
  let text = await journal(page);
  for (const line of ['Bread for the baker', 'The baker ran out of flour.', 'Stage 1/2', 'Gather wheat', '• Collect 10 wheat 0/10', '• Defeat 2 zombie 0/2']) {
    assert.ok(text.includes(line), `${label}: journal has "${line}" in ${JSON.stringify(text)}`);
  }
  await preview.getByRole('tab', {name: 'Stage 2', exact: true}).click();
  text = await journal(page);
  for (const line of ['Stage 2/2', 'Deliver the bread', '• Bring 3 bread to Baker 0/3']) {
    assert.ok(text.includes(line), `${label}: journal has "${line}" in ${JSON.stringify(text)}`);
  }
  await preview.getByRole('tab', {name: 'Finished', exact: true}).click();
  assert.ok((await journal(page)).includes('Finished'));
  await preview.getByText("quest('player', 'bread-for-the-baker')").waitFor();
  await check(page, `${label}-rewards`);

  // Apply: the server stores the quest as written.
  await page.getByRole('button', {name: 'Apply 1'}).click();
  await notice(page, 'Applied 1 document');
  const stored = (await control(`document?kind=quest&id=${encodeURIComponent(id)}`)).source;
  assert.equal(stored.title, '&6Bread for the baker');
  assert.deepEqual(stored.givers, ['greeter']);
  assert.equal(stored.turnIn, 'shopkeeper');
  assert.deepEqual(stored.stages.map(stage => stage.objectives.map(objective => objective.type)), [['collect', 'kill'], ['bring']]);
  assert.deepEqual(stored.stages[1].objectives[0], {type: 'bring', npc: 'shopkeeper', item: {id: 'minecraft:bread'}, amount: 3});
  assert.equal(stored.stages[0].objectives[1].amount, 2);
  assert.deepEqual(stored.rewards.items, [{item: {id: 'minecraft:emerald'}, amount: 4}]);
  assert.deepEqual(stored.rewards.commands, [{command: 'give {player} diamond 1'}]);
  assert.equal(stored.rewards.experience, 50);
  await check(page, `${label}-applied`);

  // Undo the apply on the server: the quest is gone again.
  await page.getByRole('button', {name: 'Undo last apply'}).click();
  await page.getByRole('alertdialog').getByText('Undo the last apply on the server?').waitFor();
  await page.getByRole('button', {name: 'Undo on the server'}).click();
  await notice(page, 'Undid 1 document');
  const state = await control('state');
  assert.equal(state.documents.some(document => document.kind === 'quest' && document.id === id), false, 'undo removes the quest');

  assert.deepEqual(errors, [], label);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    for (const [width, theme] of [[1440, 'dark'], [1440, 'light'], [390, 'dark']]) {
      await run(browser, {width, height: 900}, `quest-${width}-${theme}`, theme);
      console.log(`quest-${width}-${theme}: ok`);
    }
    console.log('quest_editor_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
