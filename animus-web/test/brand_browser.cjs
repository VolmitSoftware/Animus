// Checks the Animus brand in the built site: the mark in the top bar, the
// boot splash and the home page, the icon set, the pink accent on grey
// surfaces in both themes, the system theme when none is stored, AA
// contrast on the primary button, a neutral disabled button, Geist on
// controls, a quiet selected view tab, a pink focus ring, the theme toggle,
// theme-color and no sideways scrolling, at desktop and phone widths.
// ANIMUS_SCREENSHOTS (optional, outside the repository) receives home,
// document and focus shots per theme and width.
//
//   python3 tool/serve_site.py --port <port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/brand_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {openFromSidebar, topbarAction} = require('./support/shell.cjs');

const exportFolder = path.resolve(__dirname, '../../schema/fixtures/export');
const shots = process.env.ANIMUS_SCREENSHOTS;
const url = process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })();
const themes = {
  dark: {background: 'rgb(13, 13, 15)', panel: 'rgb(22, 21, 24)', primary: 'rgb(255, 95, 168)', raised: 'rgb(34, 32, 36)', themeColor: '#0d0d0f'},
  light: {background: 'rgb(245, 243, 244)', panel: 'rgb(252, 250, 251)', primary: 'rgb(194, 24, 91)', raised: 'rgb(239, 235, 237)', themeColor: '#f5f3f4'},
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

/** An <img> that loaded the brand mark. */
async function markShown(locator, where) {
  await locator.waitFor();
  const loaded = await locator.evaluate(img => img.complete && img.naturalWidth > 0 && img.getAttribute('alt') === '' && img.hasAttribute('width'));
  assert.ok(loaded, `${where}: the mark loads, is sized and is decorative`);
}

async function icons(page) {
  for (const [file, type] of [
    ['/favicon.svg', 'image/svg+xml'],
    ['/favicon.ico', 'image/'],
    ['/apple-touch-icon.png', 'image/png'],
    ['/icon-512.png', 'image/png'],
    ['/assets/brand/animus-mark.svg', 'image/svg+xml'],
  ]) {
    const response = await page.request.get(new URL(file, url).toString());
    assert.equal(response.status(), 200, `${file} is served`);
    assert.ok((response.headers()['content-type'] || '').startsWith(type), `${file} is ${type}: ${response.headers()['content-type']}`);
  }
  const html = await (await page.request.get(url)).text();
  assert.match(html, /<div class="animus-boot" id="loading" role="status" aria-label="Loading the Animus editor">/);
  assert.match(html, /<img class="animus-boot-mark" src="\/assets\/brand\/animus-mark.svg" width="72" height="72" alt=""/);
}

async function run(browser, viewport, label) {
  // Nothing stored: the system's light preference wins before and after the bundle starts.
  const fresh = await browser.newPage({viewport, colorScheme: 'light'});
  await fresh.goto(url);
  await fresh.getByRole('heading', {name: 'Start a workspace'}).waitFor();
  assert.equal(await fresh.evaluate(() => document.documentElement.className), 'light', `${label}: system light theme`);
  await icons(fresh);
  await fresh.close();

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
    await page.getByRole('heading', {name: 'Start a workspace'}).waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.className), name);
    assert.equal(await page.locator('#animus-theme-color').getAttribute('content'), theme.themeColor);
    const root = page.locator('#arcane-root');
    assert.equal(await root.evaluate(el => getComputedStyle(el).backgroundColor), theme.background, `${name} surface`);
    assert.equal(await root.evaluate(el => getComputedStyle(el).getPropertyValue('--primary').trim()),
        name === 'dark' ? '#ff5fa8' : '#c2185b');
    await markShown(page.locator('.animus-brand img.animus-mark'), `${label} ${name} top bar`);
    await markShown(page.locator('.animus-start-mark'), `${label} ${name} home`);
    await noSideways(page, `${label} ${name} home`);
    await shot(page, `${label}-${name}-home`);

    // Controls read in Geist; code keeps Geist Mono.
    const buttonFont = await page.locator('.arcane-button').first().evaluate(el => getComputedStyle(el).fontFamily);
    assert.match(buttonFont, /^Geist,/, `${name} button font ${buttonFont}`);

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
    await page.getByRole('heading', {name: '1 document needs repair'}).waitFor();
    // Plain links take the accent.
    const link = page.locator('.animus-try-list a').first();
    assert.equal(await link.evaluate(el => getComputedStyle(el).color), theme.primary, `${name} links are pink`);
    // With nothing to undo, Undo is disabled, and a disabled filled button is grey, not pale pink.
    if (label === 'desktop') {
      assert.equal(await page.locator('.animus-topbar').getByRole('button', {name: 'Undo'}).isDisabled(), true);
    }
    await openFromSidebar(page, 'Greeter');
    await page.getByRole('tab', {name: 'Code'}).click();
    await page.locator('.animus-code textarea').waitFor();
    assert.equal(await page.locator('.animus-panel.animus-code').evaluate(el => getComputedStyle(el).backgroundColor), theme.panel, `${name} panels are grey, not pink-tinted`);
    const apply = page.getByRole('button', {name: 'Apply to draft'});
    assert.equal(await apply.isDisabled(), true);
    const applyFill = await apply.evaluate(el => getComputedStyle(el).backgroundColor);
    assert.notEqual(applyFill, theme.primary, `${name} disabled primary is not pink`);
    assert.ok(contrast(applyFill, await apply.evaluate(el => getComputedStyle(el).color)) >= 4.5, `${name} disabled text stays readable`);
    // The selected view tab is a raised grey with a pink underline, not a pink fill.
    await page.mouse.move(1, viewport.height - 1);
    const tab = page.getByRole('tab', {name: 'Code'});
    assert.equal(await tab.getAttribute('aria-selected'), 'true');
    await tab.evaluate((el, expected) => new Promise((resolve, reject) => {
      const started = performance.now();
      const poll = () => {
        const style = getComputedStyle(el);
        if (style.backgroundColor === expected.raised && style.boxShadow.includes(expected.primary)) return resolve();
        if (performance.now() - started > 3000) return reject(new Error(`selected view tab is ${style.backgroundColor} ${style.boxShadow}`));
        setTimeout(poll, 10);
      };
      poll();
    }), theme);
    await markShown(page.locator('.animus-brand img.animus-mark'), `${label} ${name} document top bar`);
    await noSideways(page, `${label} ${name} document`);
    await shot(page, `${label}-${name}-document`);

    // The toggle flips the theme and its theme-color in place.
    const other = name === 'dark' ? 'light' : 'dark';
    await topbarAction(page, `Use ${other} theme`);
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
