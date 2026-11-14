// The Animus brand on shadcn Midnight in the built site: the mark in the top
// bar, the boot splash and home, the icon set, Midnight's own surfaces
// (#0a0a0a dark, white light) with the pink only on the primary and the
// ring, the system theme when none is stored, AA text on the primary button,
// Geist on controls, a pink focus ring, the theme switch in the overflow
// menu and theme-color, no sideways scrolling, at desktop and phone widths.
// ANIMUS_SCREENSHOTS (optional, outside the repository) receives home and
// focus shots per theme and width.
//
//   python3 tool/serve_site.py --port <port>
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/brand_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {topbarAction, startAction} = require('./support/shell.cjs');

const shots = process.env.ANIMUS_SCREENSHOTS;
const url = process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site'); })();
const themes = {
  dark: {background: 'rgb(10, 10, 10)', primary: '#ff5fa8', primaryRgb: 'rgb(255, 95, 168)', ring: 'rgb(255, 179, 214)', themeColor: '#0a0a0a'},
  light: {background: 'rgb(255, 255, 255)', primary: '#c2185b', primaryRgb: 'rgb(194, 24, 91)', ring: 'rgb(102, 10, 51)', themeColor: '#ffffff'},
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
  await page.screenshot({path: path.join(shots, `brand-${name}.png`)});
}

async function noSideways(page, where) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 0, `${where} scrolls sideways by ${overflow}px`);
}

async function markShown(locator, where) {
  await locator.waitFor();
  const loaded = await locator.evaluate(img => img.complete && img.naturalWidth > 0 && img.getAttribute('alt') === '' && img.hasAttribute('width'));
  assert.ok(loaded, `${where}: the mark loads, is sized and is decorative`);
}

async function icons(page) {
  for (const [file, type] of [
    ['/favicon.svg', 'image/svg+xml'], ['/favicon.ico', 'image/'], ['/apple-touch-icon.png', 'image/png'],
    ['/icon-512.png', 'image/png'], ['/assets/brand/animus-mark.svg', 'image/svg+xml'], ['/arcane-runtime.js', 'text/javascript'],
  ]) {
    const response = await page.request.get(new URL(file, url).toString());
    assert.equal(response.status(), 200, `${file} is served`);
    assert.ok((response.headers()['content-type'] || '').startsWith(type), `${file} is ${type}: ${response.headers()['content-type']}`);
  }
  const html = await (await page.request.get(url)).text();
  assert.match(html, /<div class="animus-boot" id="loading" role="status" aria-label="Loading the Animus editor">/);
  assert.match(html, /<script defer data-arcane-runtime src="\/arcane-runtime.js"><\/script>/);
}

async function run(browser, viewport, label) {
  const fresh = await browser.newPage({viewport, colorScheme: 'light'});
  await fresh.goto(url);
  await fresh.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
  assert.equal(await fresh.evaluate(() => document.documentElement.className), 'dark', `${label}: Midnight on a first visit, whatever the system theme`);
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
    await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.className), name);
    assert.equal(await page.locator('#animus-theme-color').getAttribute('content'), theme.themeColor);
    const root = page.locator('#arcane-root');
    const tokens = await root.evaluate(el => ({
      background: getComputedStyle(el).backgroundColor,
      primary: getComputedStyle(el).getPropertyValue('--primary').trim(),
      radius: getComputedStyle(el).getPropertyValue('--radius').trim(),
    }));
    assert.equal(tokens.background, theme.background, `${name} Midnight surface`);
    assert.equal(tokens.primary, theme.primary, `${name} pink primary`);
    assert.notEqual(tokens.radius, '0px', `${name} keeps shadcn's radius`);
    await markShown(page.locator('.ui-brand img.animus-mark'), `${label} ${name} top bar`);
    await markShown(page.locator('.ui-start-mark'), `${label} ${name} home`);
    await noSideways(page, `${label} ${name} home`);
    await shot(page, `${label}-${name}-home`);

    const buttonFont = await page.locator('.arcane-button').first().evaluate(el => getComputedStyle(el).fontFamily);
    assert.match(buttonFont, /^Geist,/, `${name} button font ${buttonFont}`);

    // A returning start page has no primary; the pair page's field always does.
    if (!(await page.locator(".arcane-button[data-variant='primary']:visible").count())) {
      await page.goto(`${url}/#/pair`);
      await page.getByRole('textbox', {name: 'New link'}).waitFor();
    }
    const primary = page.locator(".arcane-button[data-variant='primary']:visible").first();
    await primary.waitFor();
    const fill = await primary.evaluate(el => getComputedStyle(el).backgroundColor);
    const text = await primary.evaluate(el => getComputedStyle(el).color);
    assert.equal(fill, theme.primaryRgb, `${name} primary button fill`);
    assert.ok(contrast(fill, text) >= 4.5, `${name} primary button text ${contrast(fill, text).toFixed(2)}:1`);
    await primary.focus();
    await page.keyboard.press('Shift+Tab');
    await page.keyboard.press('Tab');
    // The library's own ring: 3 px of the ring token at half strength,
    // easing in with the button's transition; the token is the brand pink.
    const ringed = await primary.evaluate(el => new Promise(resolve => {
      const started = performance.now();
      const check = () => {
        const shadow = getComputedStyle(el).boxShadow;
        if (/0px 0px 0px 3px/.test(shadow) || performance.now() - started > 1500) resolve(shadow);
        else requestAnimationFrame(check);
      };
      check();
    }));
    assert.match(ringed, /0px 0px 0px 3px/, `${name} focus ring keeps the library's geometry: ${ringed}`);
    const ringToken = await page.evaluate(() => {
      const probe = document.createElement('span');
      probe.style.color = 'var(--ring)';
      document.getElementById('arcane-root').appendChild(probe);
      const colour = getComputedStyle(probe).color;
      probe.remove();
      return colour;
    });
    assert.equal(ringToken, theme.ring, `${name} ring token is the brand pink`);
    await shot(page, `${label}-${name}-focus`);

    // The theme switch in the overflow menu flips the theme and theme-color in place.
    if (page.url().includes('#/pair')) {
      await page.goto(url);
      await page.getByRole('heading', {level: 1, name: 'Animus web editor'}).waitFor();
    }
    // A returning visit keeps Open examples in the start row's "..." menu.
    await startAction(page, 'Open examples');
    await page.waitForFunction(() => /#\/workspace\//.test(location.hash));
    await page.locator('.ui-save-bar').waitFor();
    const other = name === 'dark' ? 'light' : 'dark';
    await topbarAction(page, other === 'light' ? 'Light theme' : 'Dark theme');
    await page.waitForFunction(fill => getComputedStyle(document.getElementById('arcane-root')).backgroundColor === fill, themes[other].background);
    assert.equal(await page.locator('#animus-theme-color').getAttribute('content'), themes[other].themeColor);
    await noSideways(page, `${label} ${name} workspace`);
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
