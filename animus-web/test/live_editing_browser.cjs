// Live editing against tool/fake_server.dart at desktop and phone widths:
// pair from a link, open a document from the server catalog, edit it,
// validate on the server, start a viewer-private preview (the live NPC it
// hides is named until End preview), apply, force a conflict and merge it,
// drop the direct path (failover to the relay, then back to direct once it
// recovers) and then every path (reconnect), and apply after recovery, with
// no console
// errors other than the browser's own network lines while a path is down and
// its line for the forced conflict's 409.
//
//   python3 -m http.server 8098 --bind 127.0.0.1 --directory build/jaspr
//   dart run tool/fake_server.dart              # :9797, origin http://127.0.0.1:8098
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/live_editing_browser.cjs
//
// ANIMUS_WEB_URL and ANIMUS_FAKE_URL point it elsewhere.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const site = (process.env.ANIMUS_WEB_URL || 'http://127.0.0.1:8098').replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || 'http://127.0.0.1:9797').replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;
const npc = 'examples/greeter';

async function click(page, name) {
  await page.getByRole('button', {name}).click();
}

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined && route === 'state' ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

async function serverDocument(kind, id) {
  const state = await control('state');
  return state.documents.find((document) => document.kind === kind && document.id === id);
}

/// Fails when the page scrolls sideways, naming the widest elements.
async function noSidewaysScroll(page, where) {
  const overflow = await page.evaluate(() => {
    const width = document.documentElement.clientWidth;
    if (document.documentElement.scrollWidth <= width + 1) return null;
    return [...document.querySelectorAll('body *')]
      .filter((element) => element.getBoundingClientRect().right > width + 1)
      .slice(0, 6)
      .map((element) => `${element.tagName.toLowerCase()}.${[...element.classList].join('.')} ${Math.round(element.getBoundingClientRect().right)}`);
  });
  assert.equal(overflow, null, `${where} scrolls sideways: ${JSON.stringify(overflow)}`);
}

async function shot(page, name) {
  await noSidewaysScroll(page, name);
  if (!shots) return;
  fs.mkdirSync(shots, {recursive: true});
  await page.screenshot({path: path.join(shots, `${name}.png`), fullPage: true});
}

/// Runs [action] and waits until the live log gains an entry with [text].
async function expectNotice(page, text, action) {
  const entries = page.locator('.animus-live-log li', {hasText: text});
  const before = await entries.count();
  await action();
  try {
    await page.waitForFunction(
      ([wanted, count]) => [...document.querySelectorAll('.animus-live-log li')].filter((li) => li.textContent.includes(wanted)).length > count,
      [text, before],
      {timeout: 15000},
    );
  } catch (error) {
    const log = await page.locator('.animus-live-log li').allTextContents().catch(() => []);
    throw new Error(`waiting for "${text}"; live log: ${JSON.stringify(log)}`);
  }
}

async function status(page, phase) {
  await page.locator(`.animus-live .animus-pill[data-phase="${phase}"]`).waitFor({timeout: 20000});
}

/// Replaces the NPC draft through the Code view.
async function editDraft(page, change) {
  await page.getByRole('tab', {name: 'Code'}).click();
  const area = page.locator('.animus-code textarea').first();
  const json = JSON.parse(await area.inputValue());
  change(json);
  await area.fill(JSON.stringify(json, null, 2));
  await page.getByRole('button', {name: 'Apply to draft'}).click();
  await page.getByRole('tab', {name: 'Visual'}).click();
}

async function run(browser, width, height, label) {
  await control('reset', {});
  await control('restore', {});
  const {code} = await control('pair?paths=both', {});
  const context = await browser.newContext({viewport: {width, height}});
  const page = await context.newPage();
  const errors = [];
  let networkDown = false;
  let conflictExpected = false;
  page.on('console', (message) => {
    if (message.type() !== 'error') return;
    const text = message.text();
    // The browser itself logs failed fetches and sockets while a path is cut.
    if (networkDown && /Failed to load resource|WebSocket connection|net::ERR_/.test(text)) return;
    // The browser logs the forced conflict's 409 answer as a failed resource.
    if (conflictExpected && /status of 409/.test(text)) return;
    errors.push(text);
  });
  page.on('pageerror', (error) => errors.push(String(error)));

  // Pair.
  await page.goto(`${site}/#/pair/${code}`);
  await page.getByRole('heading', {name: /Pair with/}).waitFor();
  await shot(page, `${label}-01-pair`);
  await page.getByRole('button', {name: 'Pair'}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  assert.ok(!(await page.evaluate(() => location.href)).includes('ANM1.'), 'the pairing secret leaves the address');
  await status(page, 'online');
  const tokens = (await control('state')).tokens;
  assert.equal(tokens.length, 1, 'one token issued');

  // Open from the catalog.
  await page.getByText(/documents on the server/).waitFor();
  await page.getByRole('checkbox', {name: `npc ${npc}`}).check();
  await shot(page, `${label}-02-catalog`);
  await expectNotice(page, 'Opened 1 document', () => click(page, 'Open 1'));
  await page.waitForFunction((id) => decodeURIComponent(location.hash).includes(`/npc/${id}`), npc);

  // Edit, validate, preview.
  await editDraft(page, (json) => { json.name = 'Greeter Prime'; });
  await page.locator('[data-testid="draft-count"]', {hasText: '1 changed'}).waitFor();
  await page.locator('.animus-tree-item .animus-badge[data-draft="edited"]').first().waitFor();
  await expectNotice(page, 'The server accepts', () => click(page, 'Validate'));
  await page.getByRole('button', {name: 'Preview'}).click();
  await page.locator('[data-testid="preview-state"]', {hasText: 'for your player only'}).waitFor();
  assert.equal((await control('state')).previews.length, 1, 'the fake server runs one preview');
  await page.locator('[data-testid="preview-hidden"]', {hasText: `Live NPC hidden for you while previewing: ${npc}`}).waitFor();
  await shot(page, `${label}-03-preview`);

  // Apply.
  const before = await serverDocument('npc', npc);
  await expectNotice(page, 'Applied 1 document', () => click(page, 'Apply 1'));
  await page.locator('[data-testid="draft-count"]', {hasText: 'No changes'}).waitFor();
  const applied = await serverDocument('npc', npc);
  assert.equal(applied.revision, before.revision + 1, 'the server raised the revision');
  await expectNotice(page, 'Preview ended', () => click(page, 'End preview'));
  await page.locator('[data-testid="preview-hidden"]').waitFor({state: 'detached'});
  await shot(page, `${label}-03b-preview-ended`);

  // Forced conflict: the server changes another field, the draft the name.
  await editDraft(page, (json) => { json.name = 'Greeter Second'; });
  const edited = await sourceWith((json) => { json.spawned = !json.spawned; });
  const current = await control('edit', {kind: 'npc', id: npc, source: edited});
  assert.ok(current.revision > applied.revision);
  await page.locator('.animus-badge', {hasText: 'changed on server'}).waitFor({timeout: 10000});
  conflictExpected = true;
  await page.getByRole('button', {name: 'Apply 1'}).click();
  await page.locator('.animus-conflict').waitFor();
  conflictExpected = false;
  await page.getByText('merge cleanly').waitFor();
  await shot(page, `${label}-04-conflict`);
  await page.getByRole('button', {name: 'Use merge'}).click();
  await expectNotice(page, 'Applied 1 document', () => click(page, 'Apply 1'));
  const merged = await fetchSource(npc);
  assert.equal(merged.name, 'Greeter Second');
  assert.equal(merged.spawned, edited.spawned, 'the server side of the merge survived');

  // Drop the direct path: the editor fails over to the relay.
  networkDown = true;
  await control('drop', {path: 'direct', seconds: 8});
  await page.locator('.animus-live .animus-pill', {hasText: 'Online (relay)'}).waitFor({timeout: 20000});
  await editDraft(page, (json) => { json.name = 'Over The Relay'; });
  await expectNotice(page, 'Applied 1 document', () => click(page, 'Apply 1'));
  assert.equal((await fetchSource(npc)).name, 'Over The Relay');
  await shot(page, `${label}-05-relay`);

  // Once the drop ends, the relay session probes the direct path (15 s
  // after going online over the relay) and moves back to it without going
  // offline.
  const phases = [];
  await page.exposeFunction(`animusPhase_${label}`, (phase) => phases.push(phase));
  await page.evaluate((name) => {
    const pill = () => document.querySelector('.animus-live .animus-pill');
    new MutationObserver(() => window[name](pill()?.getAttribute('data-phase'))).observe(document.body, {subtree: true, attributes: true, childList: true});
  }, `animusPhase_${label}`);
  await page.locator('.animus-live .animus-pill', {hasText: 'Online (direct)'}).waitFor({timeout: 45000});
  assert.ok(phases.every((phase) => phase === 'online'), `${label}: the switch back stays online: ${JSON.stringify([...new Set(phases)])}`);
  await waitForStreams({direct: 1, relay: 0}, `${label}: one direct event stream after the switch`);
  await editDraft(page, (json) => { json.name = 'Direct Again'; });
  await expectNotice(page, 'Applied 1 document', () => click(page, 'Apply 1'));
  assert.equal((await fetchSource(npc)).name, 'Direct Again');
  await shot(page, `${label}-05b-direct-again`);

  // Drop every path: the editor waits and reconnects once the server is back.
  await control('drop', {path: 'all', seconds: 4});
  await status(page, 'reconnecting');
  await editDraft(page, (json) => { json.name = 'After Recovery'; });
  await shot(page, `${label}-06-reconnecting`);
  await control('restore', {});
  await page.getByRole('button', {name: 'Retry now'}).first().click().catch(() => {});
  await status(page, 'online');
  networkDown = false;
  await expectNotice(page, 'Applied 1 document', () => click(page, 'Apply 1'));
  assert.equal((await fetchSource(npc)).name, 'After Recovery');

  // Undo through the server.
  await expectNotice(page, 'Undid 1 document', () => click(page, 'Undo last apply'));
  assert.equal((await fetchSource(npc)).name, 'Direct Again');
  await shot(page, `${label}-07-undo`);

  assert.deepEqual(errors, [], `${label}: console errors`);
  await context.close();
}

/// Waits until the fake server holds [wanted] open event streams per path.
async function waitForStreams(wanted, message) {
  let streams;
  for (let tries = 0; tries < 50; tries++) {
    streams = (await control('state')).streams;
    if (streams.direct === wanted.direct && streams.relay === wanted.relay) return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  assert.deepEqual(streams, wanted, message);
}

/// The stored NPC source on the fake server, via the controls.
async function fetchSource(id) {
  const response = await fetch(`${fake}/__fake/document?kind=npc&id=${encodeURIComponent(id)}`);
  assert.ok(response.ok, `${id} on the server`);
  return (await response.json()).source;
}

async function sourceWith(change) {
  const source = await fetchSource(npc);
  change(source);
  return source;
}

(async () => {
  const browser = await chromium.launch();
  try {
    await run(browser, 1440, 900, 'desktop');
    await run(browser, 390, 844, 'phone');
  } finally {
    await browser.close();
  }
  console.log('live editing browser check passed');
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
