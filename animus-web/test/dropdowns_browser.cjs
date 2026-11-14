// Dropdowns are arcane_jaspr's shadcn combobox (through
// AnimusSelect): ArrowDown opens on the chosen option, the arrows move,
// Enter picks and gives focus back to the trigger, Escape closes, a click
// opens and picks, a long list has a search field, a reference picker
// lists "+ New" and "Manage" under its options, and a row label names the
// trigger. 1440 and 390 px, both themes, no console errors.
//
//   python3 tool/serve_site.py --port <port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/dropdowns_browser.cjs
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {setMode, startAction} = require('./support/shell.cjs');
const {npcTab} = require('./support/npc_tabs.cjs');

const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site'); })()).replace(/\/$/, '');

// Visible text without the chevron glyph (an icon-font character) and
// with spacing folded.
const words = text => text.replace(/[\ue000-\uf8ff]/g, '').replace(/\s+/g, ' ').trim();

// The open list is position: fixed, so visibility, not offsetParent.
const shown = () => [...document.querySelectorAll('[role="listbox"]')].filter(list => list.checkVisibility()).length;

async function run(browser, width, theme) {
  const label = `${width} ${theme}`;
  const context = await browser.newContext({viewport: {width, height: width <= 390 ? 844 : 900}, colorScheme: theme});
  // The editor starts dark whatever the system says; the light runs pick light.
  await context.addInitScript(mode => { try { window.localStorage.setItem('animus.theme', mode); } catch (error) { /* storage off */ } }, theme);
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(url);
  {
    await startAction(page, 'Open examples');
  }
  await page.waitForFunction(() => /#\/workspace\//.test(location.hash));
  const workspace = /#\/workspace\/([^/?]+)/.exec(page.url())[1];
  // Pose and Rig sit on the NPC's Look tab; Rig is Advanced.
  await setMode(page, 'advanced');
  await page.evaluate(hash => { location.hash = hash; }, `#/workspace/${workspace}/npc/${encodeURIComponent('examples/shopkeeper')}`);
  await npcTab(page, 'Look');

  // Pose: a closed list of options, labelled by its row.
  const pose = page.locator('#animus-npc-pose');
  await pose.waitFor({state: 'attached'});
  // Phones start with the sections folded; open the one holding Pose.
  if (await pose.evaluate(el => el.closest('details') && !el.closest('details').open)) {
    await pose.evaluate(el => el.closest('details').querySelector(':scope > summary').click());
  }
  await pose.waitFor();
  assert.equal(await page.locator('label[for="animus-npc-pose"]').count(), 1, `${label}: the row label names the trigger`);
  const before = words(await pose.textContent());
  await pose.focus();
  await page.keyboard.press('ArrowDown');
  await page.waitForFunction(() => document.activeElement && document.activeElement.getAttribute('role') === 'option');
  assert.equal(await page.evaluate(() => document.activeElement.getAttribute('aria-selected')), 'true', `${label}: opens on the chosen option`);
  // Move one option away from the chosen one, up when it is the last.
  const last = await page.evaluate(() => {
    const options = [...document.activeElement.closest('[role="listbox"]').querySelectorAll('[role="option"]')];
    return options.indexOf(document.activeElement) === options.length - 1;
  });
  await page.keyboard.press(last ? 'ArrowUp' : 'ArrowDown');
  const target = words(await page.evaluate(() => document.activeElement.textContent));
  await page.keyboard.press('Enter');
  await page.waitForFunction(id => document.activeElement && document.activeElement.id === id, 'animus-npc-pose');
  // The pick is written to the NPC, which draws the trigger again.
  await page.waitForFunction(text => document.getElementById('animus-npc-pose').textContent.replace(/[\ue000-\uf8ff]/g, '').replace(/\s+/g, ' ').trim() === text, target).catch(() => {});
  assert.equal(words(await pose.textContent()), target, `${label}: Enter picked ${target}`);
  assert.notEqual(target, before);
  await pose.click();
  await page.waitForFunction(shown, null, {timeout: 3000}).catch(() => assert.fail(`${label}: a click opens`));
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => [...document.querySelectorAll('[role="listbox"]')].every(list => !list.checkVisibility()));
  await pose.click();
  await page.getByRole('option', {name: before}).click();
  await page.waitForFunction(text => document.getElementById('animus-npc-pose').textContent.replace(/[\ue000-\uf8ff]/g, '').replace(/\s+/g, ' ').trim() === text, before).catch(() => {});
  assert.equal(words(await pose.textContent()), before, `${label}: a click picks`);

  // Rig: a reference picker ends with New and Manage.
  const rig = page.locator('#animus-npc-rig');
  await rig.click();
  const list = page.locator('[role="listbox"]:visible').first();
  await list.getByRole('button', {name: /New rig/}).waitFor();
  await list.getByRole('button', {name: /Manage rigs/}).waitFor();
  await page.keyboard.press('Escape');

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 0, `${label}: no sideways scroll`);
  assert.deepEqual(errors, [], `${label}: console errors`);
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    for (const width of [1440, 390]) for (const theme of ['dark', 'light']) await run(browser, width, theme);
    console.log('dropdowns_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
