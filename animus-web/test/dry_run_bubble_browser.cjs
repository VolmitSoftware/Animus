// A long Test line stays inside the stage: a behavior with a very long
// "say" (written in the Code view and put in with "Use this code") runs in
// the Test pane of the example packs workspace at 1440 and 390 px in both
// themes, and the speech bubble's box must sit within the stage's box
// (wrapped, not clipped at either edge), also after the stage is panned.
//
//   python3 tool/serve_site.py --port <port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/dry_run_bubble_browser.cjs
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {pickOption, startAction} = require('./support/shell.cjs');

const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to your own served site, never the shared :8098'); })()).replace(/\/$/, '');

/// Runs the Test in [pane] from [label]: the Starts dropdown picks it when
/// there is more than one start, then Run plays it from the start.
async function pickStart(page, pane, label) {
  const run = pane.getByRole('button', {name: 'Run', exact: true});
  await run.waitFor();
  const starts = pane.getByRole('combobox', {name: 'Starts'});
  if (await starts.count()) await pickOption(page, starts, label);
  await run.click();
}
const longLine = 'Listen closely, traveller, because this is a very long line that keeps going well past what any single row of the stage could ever hold, and then a little further for good measure: ' + 'supercalifragilisticexpialidocious '.repeat(4);
const pane = '[data-testid="test-pane"]';

async function bubble(page) {
  return page.evaluate(selector => {
    const stage = document.querySelector(`${selector} .animus-mc-stage`).getBoundingClientRect();
    const text = [...document.querySelectorAll(`${selector} .animus-mc-text`)].find(element => /Listen closely/.test(element.textContent));
    if (!text) return null;
    const box = text.getBoundingClientRect();
    return {stage: [stage.left, stage.top, stage.right, stage.bottom], box: [box.left, box.top, box.right, box.bottom]};
  }, pane);
}

function inside(found, where) {
  assert.ok(found, `${where}: the bubble is drawn`);
  const [sl, st, sr, sb] = found.stage;
  const [bl, bt, br, bb] = found.box;
  assert.ok(bl >= sl - 0.5 && br <= sr + 0.5, `${where}: the bubble spans ${bl.toFixed(1)}..${br.toFixed(1)}, the stage ${sl.toFixed(1)}..${sr.toFixed(1)}`);
  assert.ok(bt >= st - 0.5 && bb <= sb + 0.5, `${where}: the bubble spans ${bt.toFixed(1)}..${bb.toFixed(1)} vertically, the stage ${st.toFixed(1)}..${sb.toFixed(1)}`);
}

async function run(browser, viewport, theme) {
  const label = `${viewport.width}px ${theme}`;
  const context = await browser.newContext({viewport});
  await context.addInitScript(mode => window.localStorage.setItem('animus.theme', mode), theme);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(url);
  await startAction(page, 'Open examples');
  await page.waitForFunction(() => location.hash.includes('/workspace/'));
  const workspace = await page.evaluate(() => location.hash.split('/')[2]);
  // The Code view is Advanced; the mode is read when the page loads.
  await page.evaluate(() => window.localStorage.setItem('animus.mode', 'advanced'));
  const behavior = `${url}/#/workspace/${workspace}/behavior/${encodeURIComponent('greeter:greeter')}`;
  await page.goto(`${behavior}?view=code`);
  await page.reload();
  const area = page.locator('[data-testid="code-view"] textarea').first();
  await area.waitFor();
  const json = JSON.parse(await area.inputValue());
  json.routines.main.steps[1].then.arguments.text = longLine;
  json.routines.main.steps[1].else.steps[0].arguments.text = longLine;
  await area.fill(JSON.stringify(json, null, 2));
  const use = page.locator('[data-testid="code-use"]');
  await page.waitForFunction(() => !document.querySelector('[data-testid="code-use"]').disabled);
  await use.click();
  await page.waitForFunction(() => document.querySelector('[data-testid="code-use"]').disabled);
  await page.goto(behavior);
  // The Test sits beside the form; on phones it opens from the page header.
  const test = page.locator(pane);
  const reveal = page.locator('.ui-page-header').getByRole('button', {name: 'Test', exact: true});
  if (!(await test.isVisible().catch(() => false)) && await reveal.isVisible().catch(() => false)) await reveal.click();
  await test.locator('.animus-mc-stage[data-webgl="ready"]').waitFor();
  await pickStart(page, test, 'On click');
  await test.getByRole('list', {name: 'Chat'}).getByText(/^Listen closely/).first().waitFor();
  await test.locator('.animus-mc-text', {hasText: 'Listen closely'}).first().waitFor();
  inside(await bubble(page), `${label} default framing`);

  // Panning the stage moves the figure toward an edge; the bubble stays in.
  const stage = test.locator('.animus-mc-stage');
  await stage.scrollIntoViewIfNeeded();
  const box = await stage.boundingBox();
  for (const [dx, dy] of [[-0.45, 0], [0.45, 0], [-0.45, 0.2]]) {
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.keyboard.down('Shift');
    await page.mouse.down();
    await page.mouse.move(box.x + box.width / 2 + box.width * dx, box.y + box.height / 2 + box.height * dy, {steps: 6});
    await page.mouse.up();
    await page.keyboard.up('Shift');
    // The bubble follows the camera on the next frames.
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => requestAnimationFrame(resolve)))));
    inside(await bubble(page), `${label} panned ${dx},${dy}`);
  }
  assert.deepEqual(errors, [], `${label}: page errors`);
  await context.close();
  console.log(`ok ${label}`);
}

(async () => {
  const browser = await chromium.launch({args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
  try {
    for (const theme of ['dark', 'light']) {
      await run(browser, {width: 1440, height: 1000}, theme);
      await run(browser, {width: 390, height: 844}, theme);
    }
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exit(1);
});
