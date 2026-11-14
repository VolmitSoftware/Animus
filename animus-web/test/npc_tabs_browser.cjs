// Opens test/fixtures/npc_tabs (court/king uses every Advanced setting,
// court/page is plain, groups/court is their trait group) at 1440x900 dark
// and 390x844 light and checks the NPC form's tabs: Simple shows Basics,
// Look, Movement, Talk and trade and Combat with no engine ids, names the
// Advanced tabs and fields in use with Show, keeps every Advanced key byte
// for byte through an edit made in Simple, and Advanced adds Attributes,
// Modules and Integrations. The trait group opens on Members, lists its
// members and adds one from Add members in one undo step. No console
// errors and no sideways scrolling. A second hologram line in Look shows in
// the style of the first.
//
//   python3 tool/serve_site.py --port <WEB_PORT> &
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<WEB_PORT> node test/npc_tabs_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {setMode, showView, topbarAction, workspaceOpen} = require('./support/shell.cjs');
const {npcTab} = require('./support/npc_tabs.cjs');

const folder = path.resolve(__dirname, 'fixtures/npc_tabs');
const site = process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site'); })();
const king = JSON.parse(fs.readFileSync(path.join(folder, 'npcs/court/king.json'), 'utf8'));

async function noSidewaysScroll(page, where) {
  const overflow = await page.evaluate(() => Math.max(
    document.documentElement.scrollWidth - window.innerWidth,
    (document.querySelector('#animus-main')?.scrollWidth || 0) - (document.querySelector('#animus-main')?.clientWidth || 0),
  ));
  assert.ok(overflow <= 1, `${where}: the page scrolls sideways by ${overflow}px`);
}

async function codeOf(page) {
  await showView(page, 'code');
  const text = await page.locator('.animus-code textarea').inputValue();
  await showView(page, 'visual');
  return JSON.parse(text);
}

async function open(page, workspace, kind, id) {
  await page.evaluate((hash) => { location.hash = hash; }, `#/workspace/${workspace}/${kind}/${encodeURIComponent(id)}`);
  await page.locator('.animus-npc-tabs').waitFor();
}

/** The NPC tabs' names: the strip's, or on a phone the dropdown it turned into. */
async function tabNames(page) {
  const tabs = page.locator('.animus-npc-tabs [role="tab"]');
  if (await tabs.count()) return (await tabs.allInnerTexts()).map((name) => name.trim());
  const trigger = page.locator('.animus-npc-tabs .ui-tabs.is-folded [role="combobox"]').first();
  await trigger.waitFor();
  const list = page.locator(`[id="${await trigger.getAttribute('aria-controls')}"]`);
  // Option text without the library's check glyph and spacing marks (tab names are plain ASCII).
  return list.locator('[role="option"]').evaluateAll((items) => items.map((item) => item.textContent.replace(/[^\x20-\x7e]/g, '').trim()));
}

async function run(browser, viewport, theme, tag) {
  const context = await browser.newContext({viewport, colorScheme: theme});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  page.on('console', (message) => { if (message.type() === 'error' && !/404|Failed to load resource/.test(message.text())) errors.push(message.text()); });

  await page.goto(site);
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', {name: 'Open folder'}).first().click();
  await (await chooser).setFiles(folder);
  await workspaceOpen(page);
  const workspace = /#\/workspace\/([^/?]+)/.exec(page.url())[1];

  // Simple: five tabs, no engine ids, one line counting what Simple hides
  // (the Basics fields and the Advanced-only tabs) with Show.
  await open(page, workspace, 'npc', 'court/king');
  assert.deepEqual(await tabNames(page), ['Basics', 'Look', 'Movement', 'Talk and trade', 'Combat'], `${tag}: Simple tabs`);
  const basics = await npcTab(page, 'Basics');
  assert.match(await page.locator('#animus-npc-body').innerText(), /^\s*Player\b/, `${tag}: the body reads as a word`);
  assert.doesNotMatch(await basics.innerText(), /minecraft:|[0-9a-f]{8}-[0-9a-f]{4}-/, `${tag}: no engine ids or UUIDs in Simple`);
  const hidden = basics.locator('[data-advanced-in-use]');
  assert.equal(await page.locator('.animus-npc-form [data-advanced-in-use]').count(), 1, `${tag}: one line on the tab`);
  assert.match(await hidden.innerText(), /^7 advanced settings\s+Show$/, `${tag}: five Basics fields, one attribute and one module, as a count`);
  await noSidewaysScroll(page, `${tag} Simple basics`);

  // An edit in Simple keeps every key it does not show.
  await page.locator('#animus-npc-name').fill('&6King Edmund');
  await page.locator('#animus-npc-yaw').fill('180');
  await page.locator('#animus-npc-yaw').blur();
  await page.waitForTimeout(300);
  const edited = await codeOf(page);
  assert.deepEqual(edited, {...king, name: '&6King Edmund', position: {...king.position, yaw: 180}}, `${tag}: Simple keeps every Advanced key`);
  await setMode(page, 'simple');
  await open(page, workspace, 'npc', 'court/king');

  // Show opens the Basics fields and the tabs in use in Simple, for this
  // form only.
  await page.locator('[data-npc-tab="basics"] [data-advanced-in-use]').getByRole('button', {name: 'Show'}).click();
  assert.deepEqual(await tabNames(page), ['Basics', 'Look', 'Movement', 'Talk and trade', 'Combat', 'Attributes', 'Modules'], `${tag}: Show adds the tabs in use`);
  await npcTab(page, 'Basics');
  await page.locator('#animus-npc-pitch').waitFor();
  assert.equal(await page.locator('#animus-npc-pitch').inputValue(), '-20', `${tag}: the head tilt shows`);

  // Each Simple tab draws without sideways scrolling.
  for (const name of ['Look', 'Movement', 'Talk and trade', 'Combat']) {
    const panel = await npcTab(page, name);
    if (name === 'Look' && tag === 'wide') {
      // Hologram lines share one display: a plain line takes the style of the line above.
      await panel.getByRole('button', {name: 'Add text', exact: true}).click();
      await panel.locator('input[aria-label="Hologram line 1"]').fill('&cA&lB');
      // From the keyboard: the pointer rests on a row's tooltip once line 1 turns into its plate.
      await panel.getByRole('button', {name: 'Add line', exact: true}).focus();
      await page.keyboard.press('Enter');
      // A line's box fills its row, up to the Move and Remove buttons.
      const fill = await panel.locator('input[aria-label="Hologram line 2"]').evaluate(input => {
        const row = input.closest('.ui-actions');
        return input.getBoundingClientRect().width / row.getBoundingClientRect().width;
      });
      assert.ok(fill > 0.6, `${tag}: a hologram line's box takes ${Math.round(fill * 100)}% of its row`);
      await panel.locator('input[aria-label="Hologram line 2"]').fill('C');
      const carried = panel.locator('[data-hologram-line="2"] span[style]').first();
      await carried.waitFor();
      assert.deepEqual(await carried.evaluate(span => [span.textContent, span.style.color, span.style.fontWeight]), ['C', 'rgb(255, 85, 85)', '700'],
        `${tag}: the second hologram line carries the first line's style`);
    }
    await noSidewaysScroll(page, `${tag} ${name}`);
  }

  // Advanced adds its tabs.
  await setMode(page, 'advanced');
  await open(page, workspace, 'npc', 'court/page');
  assert.deepEqual(await tabNames(page), ['Basics', 'Look', 'Movement', 'Talk and trade', 'Combat', 'Attributes', 'Modules', 'Integrations'], `${tag}: Advanced tabs`);
  for (const name of ['Attributes', 'Modules', 'Integrations']) {
    await npcTab(page, name);
    await noSidewaysScroll(page, `${tag} ${name}`);
  }

  // The trait group: Members first, add one in one undo step.
  await open(page, workspace, 'group', 'court');
  assert.equal((await tabNames(page))[0], 'Members', `${tag}: a group starts on Members`);
  const members = await npcTab(page, 'Members');
  await members.locator('[data-group="members"]').getByText('King Edmund', {exact: true}).waitFor();
  await members.locator('[data-group="add-members"]').click();
  const adding = page.locator('#animus-group-add-members');
  await adding.getByText('Page', {exact: true}).click();
  await page.getByText('Assigned court: 1 changed', {exact: false}).first().waitFor();
  await members.locator('[data-group="members"]').getByText('Page', {exact: true}).waitFor();
  await noSidewaysScroll(page, `${tag} group members`);
  await open(page, workspace, 'npc', 'court/page');
  assert.deepEqual((await codeOf(page)).groups, ['court'], `${tag}: the member lists the group`);
  await topbarAction(page, 'Undo');
  await page.waitForTimeout(300);
  assert.equal((await codeOf(page)).groups, undefined, `${tag}: one undo takes it back`);

  assert.deepEqual(errors, [], `${tag}: console errors`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch();
  try {
    await run(browser, {width: 1440, height: 900}, 'dark', 'wide');
    await run(browser, {width: 390, height: 844}, 'light', 'phone');
  } finally {
    await browser.close();
  }
  console.log('npc_tabs_browser: ok');
})().catch((error) => { console.error(error); process.exit(1); });
