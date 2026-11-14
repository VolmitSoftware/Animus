// No card inside a card, on every screen at 1600, 768 and 390 px. A box is
// a card when it draws a visible border on at least three sides, or a
// background that differs from the page's together with a corner radius or
// a full border. A screen fails for each card that sits inside another
// card. Not cards: form controls (inputs, text areas, dropdowns, buttons,
// toggles, tabs, the dot a library radio card draws), badges, key caps and
// colour swatches, table and list rows, tooltips, popovers, menus and
// toasts, the skeleton bars, the Minecraft windows, item tooltips and item
// tiles, and the 3D canvas with the labels drawn on it. A dialog or sheet
// is a surface of its own: what is inside it is judged up to it. Screens:
// test/support/screens.cjs with its frame pages (start, Home, New, the
// lists, Send to server, Import, dialogs, pair, Review) and every tab of the
// first document of each kind. The count per screen is printed. The walk
// is in Simple, so it also fails what Simple must never show
// (support/screens.cjs simpleProblems): raw ids as names, colour codes in
// idle text boxes, pink tooltips, Hide preview, doubled plurals. On every
// screen it hovers every titled element and fails one the arcane runtime
// moves out from under the page (titleProblems).
// ANIMUS_CARDS_WIDTHS (comma separated) limits the widths;
// ANIMUS_CARDS_SCREENS (a regular expression) checks only the screens whose
// name matches; ANIMUS_CARDS_LIST=1 prints every nested card;
// ANIMUS_SCREENSHOTS (outside the repository) receives a shot per screen.
//
//   python3 tool/serve_site.py --port <port>
//   dart run tool/fake_server.dart --port=<port> --origin=http://127.0.0.1:<site port> --data=test/fixtures/terrain_view
//   ANIMUS_WEB_URL=http://127.0.0.1:<port> ANIMUS_FAKE_URL=http://127.0.0.1:<port> \
//     PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/nested_cards_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {walkScreens, simpleProblems, titleProblems} = require('./support/screens.cjs');

const url = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;
const widths = (process.env.ANIMUS_CARDS_WIDTHS || '1600,768,390').split(',').map(Number);
const screens = process.env.ANIMUS_CARDS_SCREENS ? new RegExp(process.env.ANIMUS_CARDS_SCREENS) : null;

/** Every card inside another card on the page. */
async function scan(page) {
  return page.evaluate(() => {
    const notCards = [
      'input', 'textarea', 'select', 'button', '[role="button"]', '[role="combobox"]', '[role="switch"]', '[role="checkbox"]', '[role="radio"]', '[role="slider"]',
      '[role="tablist"]', '[role="tab"]', '[role="group"]:has(> .arcane-toggle-group)', '.arcane-toggle-group',
      '[class*="badge"]', 'kbd', '.animus-chest-letter', '[class*="swatch"]',
      'tr', '[role="row"]', '[role="option"]', '[role="menuitem"]', '[role="treeitem"]',
      '[role="tooltip"]', '[role="menu"]', '[role="listbox"]', '[class*="popover"]', '[class*="hover-card"]', '[class*="dropdown-content"]', '[class*="toast"]', '[class*="sonner"]',
      '[class*="skeleton"]',
      '.animus-chest-screen', '.animus-merchant-screen', '.animus-quest-entry', '.animus-shop-icon', '.animus-item-picker-icon', '.animus-item-row-icon',
      'canvas', 'svg', '.animus-mc-text-layer', '.animus-mc-overlay',
      // A library control's own drawn shape (the dot of a radio card).
      '[data-arcane-intrinsic-shape]',
    ].join(', ');
    const layers = '[role="dialog"], [role="alertdialog"]';
    const parse = text => {
      const m = /rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?\)/.exec(text || '');
      return m ? [Number(m[1]), Number(m[2]), Number(m[3]), m[4] === undefined ? 1 : Number(m[4])] : [0, 0, 0, 0];
    };
    const opaque = el => {
      for (let node = el; node; node = node.parentElement) {
        const c = parse(getComputedStyle(node).backgroundColor);
        if (c[3] > 0.95) return c;
      }
      return [255, 255, 255, 1];
    };
    const page = opaque(document.querySelector('#animus-main') || document.body);
    const tinted = cs => {
      if (cs.backgroundImage && cs.backgroundImage !== 'none') return true;
      const c = parse(cs.backgroundColor);
      if (c[3] < 0.04) return false;
      return [0, 1, 2].some(i => Math.abs(c[i] * c[3] + page[i] * (1 - c[3]) - page[i]) > 2);
    };
    const isCard = el => {
      const cs = getComputedStyle(el);
      // A round dot no larger than a control, such as a radio card's own radio.
      const box = el.getBoundingClientRect();
      if (box.width <= 24 && box.height <= 24 && parseFloat(cs.borderTopLeftRadius) >= Math.min(box.width, box.height) / 2) return false;
      const sides = ['Top', 'Right', 'Bottom', 'Left'].filter(side =>
        parseFloat(cs[`border${side}Width`]) > 0 && !['none', 'hidden'].includes(cs[`border${side}Style`]) && parse(cs[`border${side}Color`])[3] > 0.04).length;
      const round = ['TopLeft', 'TopRight', 'BottomRight', 'BottomLeft'].some(corner => parseFloat(cs[`border${corner}Radius`]) > 0);
      return sides >= 3 || (tinted(cs) && (round || sides === 4));
    };
    const shown = el => el.checkVisibility({opacityProperty: true, visibilityProperty: true}) && el.getBoundingClientRect().width >= 4 && el.getBoundingClientRect().height >= 4;
    const name = el => {
      const classes = [...el.classList].filter(c => !/^is-|^has-/.test(c)).slice(0, 2).join('.');
      const r = el.getBoundingClientRect();
      return `${el.tagName.toLowerCase()}${el.id && !/\d/.test(el.id) ? '#' + el.id : ''}${classes ? '.' + classes : ''} ${Math.round(r.width)}x${Math.round(r.height)}`;
    };
    const cards = new Set([...document.body.querySelectorAll('*')].filter(el => !el.closest(notCards) && shown(el) && isCard(el)));
    const nested = [];
    for (const el of cards) {
      for (let up = el.parentElement; up && up !== document.body && !up.matches(layers); up = up.parentElement) {
        if (cards.has(up)) {
          nested.push(`${name(el)} in ${name(up)}`);
          break;
        }
      }
    }
    return nested;
  });
}

async function settle(page) {
  await page.waitForTimeout(150);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function run(browser, width, failures, counts) {
  const viewport = {width, height: width <= 390 ? 844 : 900};
  const context = await browser.newContext({viewport, colorScheme: 'dark', hasTouch: width <= 390});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  let count = 0;
  const check = async name => {
    await page.mouse.move(0, 0);
    await settle(page);
    const nested = await scan(page);
    counts.push(`${width} ${name}: ${nested.length}`);
    for (const each of nested) failures.push(`${width} ${name} | ${each}`);
    for (const each of await simpleProblems(page)) failures.push(`${width} ${name} | Simple: ${each}`);
    for (const each of await titleProblems(page)) failures.push(`${width} ${name} | ${each}`);
    if (shots) {
      fs.mkdirSync(shots, {recursive: true});
      await page.screenshot({path: path.join(shots, `cards-${width}-${String(++count).padStart(3, '0')}-${name.replace(/[^a-z0-9]+/gi, '-')}.png`)});
    }
  };
  await walkScreens(page, {url, fake, width, check, tabs: true, pages: true, wanted: name => !screens || screens.test(name)});
  await context.close();
}

(async () => {
  const browser = await chromium.launch({headless: true});
  const failures = [];
  const counts = [];
  try {
    for (const width of widths) {
      const started = Date.now();
      const before = failures.length;
      const seen = counts.length;
      await run(browser, width, failures, counts);
      console.log(`nested_cards_browser: ${width} ${counts.length - seen} screens, ${failures.length - before} nested cards, ${Math.round((Date.now() - started) / 1000)} s`);
    }
  } finally {
    await browser.close();
  }
  for (const line of counts) console.log(line);
  if (process.env.ANIMUS_CARDS_LIST) {
    for (const failure of failures) console.log(failure);
  }
  assert.deepEqual(failures, [], `${failures.length} nested cards:\n${failures.slice(0, 80).join('\n')}`);
  console.log('nested_cards_browser: ok');
})().catch(error => { console.error(error.message || error); process.exit(1); });
