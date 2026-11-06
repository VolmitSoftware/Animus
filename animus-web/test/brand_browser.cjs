// Checks the Animus colours in the built site: the pink accent and grey
// surfaces in both themes, AA contrast on the primary button, a visible pink
// focus ring, the theme toggle, theme-color and no sideways scrolling, at
// desktop and phone widths. ANIMUS_SCREENSHOTS (optional, outside the
// repository) receives home, document and focus shots per theme and width.
//
//   python3 -m http.server 8098 --bind 127.0.0.1 --directory build/jaspr
//   PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/brand_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const exportFolder = path.resolve(__dirname, '../../schema/fixtures/export');
const shots = process.env.ANIMUS_SCREENSHOTS;
const url = process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })();
const themes = {
  dark: {background: 'rgb(13, 13, 15)', panel: 'rgb(22, 21, 24)', primary: 'rgb(255, 95, 168)', themeColor: '#0d0d0f'},
  light: {background: 'rgb(245, 243, 244)', panel: 'rgb(252, 250, 251)', primary: 'rgb(194, 24, 91)', themeColor: '#f5f3f4'},
};

function parse(colour) {
  const match = /rgba?\((\d+), (\d+), (\d+)/.exec(colour);
  assert.ok(match, `not an rgb colour: ${colour}`);
  return match.slice(1, 4).map(Number);
}

function contrast(a, b) {
  const luminance = rgb => rgb.map(value => {
    const c = value / 255;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }).reduce((sum, c, index) => sum + c * [0.2126, 0.7152, 0.0722][index], 0);
  const [la, lb] = [luminance(parse(a)), luminance(parse(b))];
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

async function shot(page, name) {
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: false});
}

async function noSideways(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 0, `${where} scrolls sideways by ${overflow}px`);
}

async function run(browser, viewport, label) {
  const page = await browser.newPage({viewport});
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  for (const name of ['dark', 'light']) {
    const theme = themes[name];
    await page.goto(url);
    await page.evaluate(mode => window.localStorage.setItem('animus.theme', mode), name);
    await page.reload();
    await page.getByRole('heading', {name: 'Open an Animus data folder'}).waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.className), name);
    assert.equal(await page.locator('#animus-theme-color').getAttribute('content'), theme.themeColor);
    const root = page.locator('#arcane-root');
    assert.equal(await root.evaluate(el => getComputedStyle(el).backgroundColor), theme.background, `${name} surface`);
    assert.equal(await root.evaluate(el => getComputedStyle(el).getPropertyValue('--primary').trim()),
        name === 'dark' ? '#ff5fa8' : '#c2185b');
    await noSideways(page, `${label} ${name} home`);
    await shot(page, `${label}-${name}-home`);

    // The first primary button: pink fill, AA text, and a pink focus ring from the keyboard.
    const primary = page.locator(".arcane-button[data-variant='primary']").first();
    await primary.waitFor();
    const fill = await primary.evaluate(el => getComputedStyle(el).backgroundColor);
    const text = await primary.evaluate(el => getComputedStyle(el).color);
    assert.equal(fill, theme.primary, `${name} primary button fill`);
    assert.ok(contrast(fill, text) >= 4.5, `${name} primary button text ${contrast(fill, text).toFixed(2)}:1`);
    await primary.focus();
    await page.keyboard.press('Shift+Tab');
    await page.keyboard.press('Tab');
    const ring = await primary.evaluate(el => `${getComputedStyle(el).boxShadow} ${getComputedStyle(el).outlineColor} ${getComputedStyle(el).outlineStyle}`);
    assert.ok(/rgb\(255, 179, 214\)|rgb\(194, 24, 91\)/.test(ring), `${name} focus ring is pink: ${ring}`);
    await shot(page, `${label}-${name}-focus`);

    // A document with its views and sidebar selection.
    const chooser = page.waitForEvent('filechooser');
    await page.getByRole('button', {name: 'Open folder'}).first().click();
    await (await chooser).setFiles(exportFolder);
    await page.getByRole('heading', {name: '1 documents need repair'}).waitFor();
    await page.locator('.animus-sidebar').getByText('Greeter', {exact: true}).first().click();
    await page.getByRole('tab', {name: 'Code'}).click();
    await page.locator('.animus-code textarea').waitFor();
    assert.equal(await page.locator('.animus-panel.animus-code').evaluate(el => getComputedStyle(el).backgroundColor), theme.panel, `${name} panels are grey, not pink-tinted`);
    // Off the tab, once its colour transition settles, the selected view is solid pink.
    await page.mouse.move(1, viewport.height - 1);
    await page.getByRole('tab', {name: 'Code'}).evaluate((el, fill) => new Promise((resolve, reject) => {
      const started = performance.now();
      const poll = () => getComputedStyle(el).backgroundColor === fill ? resolve()
          : performance.now() - started > 3000 ? reject(new Error(`selected view tab is ${getComputedStyle(el).backgroundColor}`)) : setTimeout(poll, 10);
      poll();
    }), theme.primary);
    await noSideways(page, `${label} ${name} document`);
    await shot(page, `${label}-${name}-document`);

    // The toggle flips the theme and its theme-color in place.
    const other = name === 'dark' ? 'light' : 'dark';
    await page.getByRole('button', {name: `Use ${other} theme`}).click();
    await page.waitForFunction(fill => getComputedStyle(document.getElementById('arcane-root')).backgroundColor === fill, themes[other].background);
    assert.equal(await page.locator('#animus-theme-color').getAttribute('content'), themes[other].themeColor);
  }
  assert.deepEqual(errors, []);
  await page.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    await run(browser, {width: 1440, height: 1000}, 'desktop');
    await run(browser, {width: 390, height: 844}, 'phone');
    console.log('brand_browser: ok');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
