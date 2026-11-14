// Every NPC row shows a face, as the stage draws the NPC: the example packs'
// player NPCs (no skin) show the stand-in skin the stage gives each of them,
// never a blank square. Paired with the fake server loaded with
// test/fixtures/npc_browser, a player wearing a skin document shows its
// texture's face (textures.minecraft.net answered here from a placeholder
// PNG), a villager its spawn egg and an armor stand its item; when the
// texture cannot be fetched the row falls back to the stand-in. No failed
// requests on the way, no console errors. 1440 px.
//
//   python3 tool/serve_site.py --port <web port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<web port> --data=test/fixtures/npc_browser
//   PLAYWRIGHT_MODULE=/path/to/node_modules/playwright ANIMUS_WEB_URL=http://127.0.0.1:<web port> \
//     ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/npc_faces_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const texture = fs.readFileSync(path.resolve(__dirname, '../web/assets/skins/placeholder/guard.png'));

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

/** Each listed NPC row's face: how it is drawn and whether its picture loaded. */
async function faces(page) {
  return page.evaluate(async () => {
    const loads = url => new Promise(resolve => {
      const image = new Image();
      image.onload = () => resolve(image.naturalWidth > 0);
      image.onerror = () => resolve(false);
      image.src = url;
    });
    const out = {};
    for (const row of document.querySelectorAll('#animus-main .ui-library-row[data-key^="npc:"]')) {
      const face = row.querySelector('.animus-skin-face');
      const id = row.getAttribute('data-key').slice(4);
      if (!face) { out[id] = {kind: 'none'}; continue; }
      const background = getComputedStyle(face).backgroundImage.match(/url\("?(.*?)"?\)/);
      const image = face.querySelector('img');
      const url = image ? image.src : background ? background[1] : null;
      out[id] = {kind: face.getAttribute('data-face') || (face.classList.contains('is-blank') ? 'blank' : 'unknown'), loaded: url ? await loads(url) : false};
    }
    return out;
  });
}

/** Waits until no row is still loading its face. */
async function settled(page) {
  await page.locator('#animus-main .ui-library-row[data-key^="npc:"]').first().waitFor();
  await page.waitForFunction(() => !document.querySelector('#animus-main .animus-skin-face[data-face="loading"]'), null, {timeout: 20000});
  await page.waitForTimeout(300);
}

async function context(browser, textures) {
  const made = await browser.newContext({viewport: {width: 1440, height: 1000}});
  await made.addInitScript(() => localStorage.setItem('animus.mode', 'simple'));
  await made.route('https://textures.minecraft.net/texture/**', route => textures(route));
  const page = await made.newPage();
  page.setDefaultTimeout(15000);
  const problems = [];
  page.on('pageerror', error => problems.push(String(error)));
  page.on('console', message => { if (message.type() === 'error' && !/textures\.minecraft\.net|net::ERR_FAILED/.test(message.text())) problems.push(message.text()); });
  page.on('response', response => { if (response.status() >= 400) problems.push(`${response.status()} ${response.url()}`); });
  return {made, page, problems};
}

async function pair(page) {
  await control('reset', {});
  const {code} = await control('pair?paths=both', {});
  await page.goto(`${site}/#/pair/${code}`);
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  const workspace = await page.evaluate(() => `server-${location.hash.split('/')[2]}`);
  await page.evaluate(w => { location.hash = `#/workspace/${w}/npcs`; }, workspace);
  await settled(page);
}

(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    // The example packs: eight player NPCs without a skin.
    const examples = await context(browser, route => route.abort());
    await examples.page.goto(site);
    await examples.page.getByRole('button', {name: 'Open examples', exact: true}).click();
    await examples.page.waitForFunction(() => /#\/workspace\/[^/]+$/.test(location.hash));
    await examples.page.evaluate(() => { location.hash = `${location.hash}/npcs`; });
    await settled(examples.page);
    const shown = await faces(examples.page);
    assert.ok(Object.keys(shown).length >= 8, `the example NPCs are listed: ${Object.keys(shown).length}`);
    for (const [id, face] of Object.entries(shown)) {
      assert.equal(face.kind, 'placeholder', `${id} wears the stage's stand-in, not ${face.kind}`);
      assert.ok(face.loaded, `${id}'s stand-in face loads`);
    }
    assert.deepEqual(examples.problems, [], 'no failed requests or console errors on the examples');
    await examples.made.close();
    console.log(`ok examples: ${Object.keys(shown).length} NPC rows show their stand-in faces`);

    // Paired: a worn skin, a villager, armor stands and players without a skin.
    const paired = await context(browser, route => route.fulfill({status: 200, contentType: 'image/png', body: texture, headers: {'access-control-allow-origin': '*'}}));
    await pair(paired.page);
    const live = await faces(paired.page);
    assert.equal(live['harbor/ferryman'].kind, 'loaded', `the skin's texture face: ${JSON.stringify(live['harbor/ferryman'])}`);
    assert.ok(live['harbor/ferryman'].loaded);
    for (const id of ['harbor/lamplighter', 'harbor/dockhand', 'wild/golem-keeper']) {
      assert.equal(live[id].kind, 'body', `${id} shows its body's icon, not ${live[id].kind}`);
      assert.ok(live[id].loaded, `${id}'s icon is drawn`);
    }
    for (const id of ['crier', 'town/baker', 'town/guard-north']) assert.equal(live[id].kind, 'placeholder', id);
    assert.equal(Object.values(live).filter(face => face.kind === 'blank' || face.kind === 'none').length, 0, 'no blank faces');
    assert.deepEqual(paired.problems, [], 'no failed requests or console errors when paired');
    await paired.made.close();
    console.log('ok paired: texture face, body icons and stand-ins');

    // A texture that cannot be fetched falls back to the stand-in.
    const offline = await context(browser, route => route.abort());
    await pair(offline.page);
    const fallback = await faces(offline.page);
    assert.equal(fallback['harbor/ferryman'].kind, 'placeholder', 'a skin that cannot load shows the stand-in');
    await offline.made.close();
    console.log('npc faces: ok');
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exit(1);
});
