// A step's values keep a usable width in a narrow column. Opens
// test/fixtures/nested_steps (an Explode two levels down whose X, Y and Z
// come from expressions) at 390 px in Advanced, opens the steps down to it
// and fails when an expression box is narrower than 240 px or a nesting
// level indents more than 8 px. The same page at 1600 px keeps the source
// picker and the expression on one line. It needs no fake server.
//
//   python3 tool/serve_site.py --port <port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> \
//     PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/operand_width_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {startAction} = require('./support/shell.cjs');

const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;
const fixture = path.resolve(__dirname, 'fixtures', 'nested_steps');

async function explodeFields(browser, width) {
  const context = await browser.newContext({viewport: {width, height: width <= 390 ? 844 : 900}, colorScheme: 'dark', hasTouch: width <= 390});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  await page.goto(url);
  await page.evaluate(() => window.localStorage.setItem('animus.mode', 'advanced'));
  await page.reload();
  await page.getByRole('heading', {name: 'Continue'}).or(page.getByRole('heading', {name: 'Animus web editor'})).first().waitFor();
  const chooser = page.waitForEvent('filechooser');
  await startAction(page, 'Open folder');
  await (await chooser).setFiles(fixture);
  await page.waitForFunction(() => /#\/workspace\//.test(location.hash));
  const workspace = /#\/workspace\/([^/?]+)/.exec(page.url())[1];
  await page.evaluate(hash => { location.hash = hash; }, `#/workspace/${workspace}/behavior/${encodeURIComponent('shop:gate-guard')}`);
  await page.locator('#animus-main [data-argument]').first().waitFor({state: 'attached'});
  // Open every step down to the Explode.
  for (let round = 0; round < 6; round++) {
    const closed = page.locator('#animus-main details:not([open]) > summary');
    if (!(await closed.count())) break;
    for (const summary of await closed.all()) await summary.click().catch(() => {});
  }
  const x = page.locator('#animus-main [data-argument="x"]').first();
  await x.waitFor();
  await x.scrollIntoViewIfNeeded();
  if (shots) {
    fs.mkdirSync(shots, {recursive: true});
    await page.screenshot({path: path.join(shots, `operand-width-${width}.png`)});
  }
  const found = await page.evaluate(() => {
    const field = document.querySelector('#animus-main [data-argument="x"]');
    const box = field.querySelector('textarea, input');
    const source = field.querySelector('[role="combobox"]');
    const rails = [...document.querySelectorAll('#animus-main .animus-node-children')].map(el => parseFloat(getComputedStyle(el).paddingInlineStart));
    return {
      expression: Math.round(box.getBoundingClientRect().width),
      sameLine: Math.abs(source.getBoundingClientRect().top - box.getBoundingClientRect().top) < 2,
      indent: Math.max(...rails),
    };
  });
  await context.close();
  return found;
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    const phone = await explodeFields(browser, 390);
    console.log(`operand_width_browser: 390 expression ${phone.expression} px, indent ${phone.indent} px, same line ${phone.sameLine}`);
    assert.ok(phone.expression >= 240, `the X expression is ${phone.expression} px wide at 390 px`);
    assert.ok(phone.indent <= 8, `a nesting level indents ${phone.indent} px at 390 px`);
    const wide = await explodeFields(browser, 1600);
    console.log(`operand_width_browser: 1600 expression ${wide.expression} px, same line ${wide.sameLine}`);
    assert.ok(wide.sameLine, 'the source picker and the expression share a line at 1600 px');
  } finally {
    await browser.close();
  }
  console.log('operand_width_browser: ok');
})().catch(error => { console.error(error.message || error); process.exit(1); });
