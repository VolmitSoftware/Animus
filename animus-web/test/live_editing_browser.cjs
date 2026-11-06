// Live editing against tool/fake_server.dart at desktop and phone widths, in
// the dark and light themes: pair from a link, open a document from the
// server catalog, edit it, validate on the server, start a viewer-private
// preview (the live NPC it hides is named until End preview), apply, force a
// conflict and merge it, drop the direct path (failover to the relay, then
// back to direct once it recovers) and then every path (reconnect), apply
// after recovery and undo through the inline confirm. The second part runs
// the failure paths through the fake server's test controls: pairing codes
// that are bad or expired, the expiry note, a forced scope-missing, a lost
// apply answer recovered by its key, a relay error, a limit close, a
// preview for an offline player, revoked and expired tokens with Pair again,
// every confirm (cancel and confirm), Forget server and two tabs with one
// session owner. No console errors other than the browser's own network
// lines while a path is down and its lines for answers the test forces.
//
//   python3 tool/serve_site.py --port 8098
//   dart run tool/fake_server.dart              # :9797, origin http://127.0.0.1:8098
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/live_editing_browser.cjs
//
// ANIMUS_WEB_URL and ANIMUS_FAKE_URL point it elsewhere.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {closeDrawer, sidebar} = require('./support/shell.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
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

/// A browser context in [theme], with console errors collected. While
/// [allow] returns true for a line it is the browser's own network noise.
async function themed(browser, width, height, theme) {
  const context = await browser.newContext({viewport: {width, height}});
  await context.addInitScript((mode) => window.localStorage.setItem('animus.theme', mode), theme);
  return context;
}

function watch(page, errors, allow) {
  page.on('console', (message) => {
    if (message.type() !== 'error') return;
    const text = message.text();
    if (allow(text)) return;
    errors.push(text);
  });
  page.on('pageerror', (error) => errors.push(String(error)));
}

async function pairWith(page, code, {remember = false} = {}) {
  await page.goto(`${site}/#/pair/${code}`);
  await page.locator('[data-testid="pair-fingerprint"]').waitFor();
  if (remember) await page.getByRole('switch', {name: 'Remember this browser'}).click();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
}

async function run(browser, width, height, label, theme) {
  await control('reset', {});
  await control('restore', {});
  const {code} = await control('pair?paths=both', {});
  const context = await themed(browser, width, height, theme);
  const page = await context.newPage();
  const errors = [];
  let networkDown = false;
  let conflictExpected = false;
  watch(page, errors, (text) =>
    // The browser itself logs failed fetches and sockets while a path is cut,
    (networkDown && /Failed to load resource|WebSocket connection|net::ERR_/.test(text)) ||
    // and the forced conflict's 409 answer as a failed resource.
    (conflictExpected && /status of 409/.test(text)));

  // Pair.
  await page.goto(`${site}/#/pair/${code}`);
  await page.getByRole('heading', {level: 1, name: 'Animus fake server'}).waitFor();
  await page.locator('[data-testid="pair-fingerprint"]', {hasText: 'sha256:'}).waitFor();
  assert.equal(await page.getByRole('switch', {name: 'Remember this browser'}).count(), 1, 'remember is a labelled switch');
  await shot(page, `${label}-01-pair`);
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  assert.ok(!(await page.evaluate(() => location.href)).includes('ANM1.'), 'the pairing secret leaves the address');
  await status(page, 'online');
  const tokens = (await control('state')).tokens;
  assert.equal(tokens.length, 1, 'one token issued');

  // Open from the catalog.
  await page.getByText(/documents on the server/).waitFor();
  await page.getByRole('heading', {name: /^NPCs \(/}).waitFor();
  await page.getByText('revision 1').first().waitFor();
  await page.getByRole('checkbox', {name: npc, exact: true}).check();
  await shot(page, `${label}-02-catalog`);
  await expectNotice(page, 'Opened 1 document', () => click(page, 'Open 1'));
  await page.waitForFunction((id) => decodeURIComponent(location.hash).includes(`/npc/${id}`), npc);

  // Edit, validate, preview.
  await editDraft(page, (json) => { json.name = 'Greeter Prime'; });
  await page.locator('[data-testid="draft-count"]', {hasText: '1 changed draft'}).waitFor();
  await (await sidebar(page)).locator('.animus-tree-item .animus-badge[data-draft="edited"]').first().waitFor();
  await closeDrawer(page);
  await expectNotice(page, 'The server accepts', () => click(page, 'Validate'));
  await page.getByRole('button', {name: 'Preview'}).click();
  await page.locator('[data-testid="preview-state"]', {hasText: 'for your player only'}).waitFor();
  assert.equal((await control('state')).previews.length, 1, 'the fake server runs one preview');
  await page.locator('[data-testid="preview-hidden"]', {hasText: `Live NPC hidden for you while previewing: ${npc}`}).waitFor();
  await shot(page, `${label}-03-preview`);

  // Apply.
  const before = await serverDocument('npc', npc);
  await expectNotice(page, 'Applied 1 document', () => click(page, 'Apply 1'));
  await page.locator('[data-testid="draft-count"]', {hasText: 'No changed drafts'}).waitFor();
  const applied = await serverDocument('npc', npc);
  assert.equal(applied.revision, before.revision + 1, 'the server raised the revision');
  await expectNotice(page, 'The preview ended.', () => click(page, 'End preview'));
  await page.locator('[data-testid="preview-state"]', {hasText: 'The preview ended.'}).waitFor();
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
  await expectNotice(page, 'Applied 1 document', () => click(page, 'Apply 1'));
  assert.equal((await fetchSource(npc)).name, 'After Recovery');
  // Sockets the browser pooled before the cut may still reset once.
  networkDown = false;

  // Undo through the server, behind an inline confirm: Escape cancels and
  // gives focus back to the button, then confirm.
  await click(page, 'Undo last apply');
  await page.getByRole('alertdialog').getByText('Undo the last apply on the server?').waitFor();
  await shot(page, `${label}-07-undo-confirm`);
  await page.keyboard.press('Escape');
  await page.getByRole('alertdialog').waitFor({state: 'detached'});
  await page.waitForFunction(() => document.activeElement?.id === 'animus-live-undo');
  assert.equal((await fetchSource(npc)).name, 'After Recovery', 'cancel wrote nothing');
  await click(page, 'Undo last apply');
  await expectNotice(page, 'Undid 1 document', () => click(page, 'Undo on the server'));
  assert.equal((await fetchSource(npc)).name, 'Direct Again');
  await shot(page, `${label}-07-undo`);

  assert.deepEqual(errors, [], `${label}: console errors`);
  await context.close();
}

/// A pairing code whose expiry is in the past, as a code minted long ago reads.
function expiredCode(code) {
  const payload = JSON.parse(Buffer.from(code.slice(5), 'base64url').toString('utf8'));
  payload.expiresAt = '2020-01-01T00:00:00Z';
  return `ANM1.${Buffer.from(JSON.stringify(payload)).toString('base64url')}`;
}

/// Counts live log entries holding [text].
async function logCount(page, text) {
  return page.locator('.animus-live-log li', {hasText: text}).count();
}

/// Waits until the focused element's id starts with [prefix].
async function focused(page, prefix) {
  await page.waitForFunction((wanted) => (document.activeElement?.id || '').startsWith(wanted), prefix, {timeout: 5000});
}

/// The failure paths, through the fake server's test controls.
async function failures(browser, width, height, label, theme) {
  await control('reset', {});
  await control('restore', {});
  const context = await themed(browser, width, height, theme);
  let page = await context.newPage();
  const errors = [];
  // Every failure here is forced; the browser logs its refused answers and
  // cut connections itself.
  const forced = (text) => /Failed to load resource|WebSocket connection|net::ERR_/.test(text);
  watch(page, errors, forced);

  // A code that is not one: the cause, the next step and the paste field.
  await page.goto(`${site}/#/pair/ANM1.not-json`);
  await page.getByRole('heading', {level: 1, name: 'This pairing link cannot be used'}).waitFor();
  await page.getByRole('alert').getByText('Run /animus editor pair on the server again').waitFor();
  await shot(page, `${label}-f01-bad-code`);

  // An expired code: no Pair, the next step and the paste field.
  const {code: fresh} = await control('pair?paths=both', {});
  await page.getByRole('textbox', {name: 'Pairing link or code'}).fill(expiredCode(fresh));
  await click(page, 'Use this code');
  await page.locator('[data-testid="pair-expired"]').waitFor();
  assert.equal(await page.getByRole('button', {name: 'Pair', exact: true}).count(), 0, 'an expired code offers no Pair');
  await shot(page, `${label}-f02-expired-code`);

  // A token with a day left: the live bar says so.
  const {code} = await control('pair?paths=both&days=1', {});
  await pairWith(page, code, {remember: true});
  await status(page, 'online');
  await page.locator('[data-testid="live-expiry"]', {hasText: 'expires in'}).waitFor();
  await page.getByRole('checkbox', {name: npc, exact: true}).check();
  await expectNotice(page, 'Opened 1 document', () => click(page, 'Open 1'));
  await page.waitForFunction((id) => decodeURIComponent(location.hash).includes(`/npc/${id}`), npc);
  await shot(page, `${label}-f03-expiry-note`);

  // A forced scope-missing: its copy, and the draft and note stay.
  await editDraft(page, (json) => { json.name = 'Scoped'; });
  await page.getByRole('textbox', {name: 'Change note'}).fill('keep me');
  await control('fail?code=scope-missing&times=1&route=/v1/changes', {});
  await expectNotice(page, 'This pairing does not allow that action.', () => click(page, 'Apply 1'));
  await page.locator('[data-testid="draft-count"]', {hasText: '1 changed draft'}).waitFor();
  assert.equal(await page.getByRole('textbox', {name: 'Change note'}).inputValue(), 'keep me', 'a refused apply keeps the note');

  // A lost apply answer: the editor reconnects and asks by the key.
  await control('drop-answer', {});
  await expectNotice(page, 'Applied 1 document', () => click(page, 'Apply 1'));
  assert.equal((await fetchSource(npc)).name, 'Scoped');
  await status(page, 'online');

  // A preview for a player who is not on the server.
  await control('player?online=false', {});
  await expectNotice(page, 'who is not on the server', () => click(page, 'Preview'));
  await control('player?online=true', {});
  await shot(page, `${label}-f04-player-offline`);

  // The server closes the stream at its limit: one notice, then online.
  await expectNotice(page, 'fell too far behind', () => control('limit', {}));
  await status(page, 'online');
  assert.equal(await logCount(page, 'fell too far behind'), 1, 'one notice for one close');

  // A relay error, with the direct path down so the relay carries the session.
  await control('drop', {path: 'direct', seconds: 60});
  await page.locator('.animus-live .animus-pill', {hasText: 'Online (relay)'}).waitFor({timeout: 20000});
  await expectNotice(page, 'The relay is full right now.', () => control('relay-error?code=capacity', {}));
  await page.locator('.animus-live .animus-pill', {hasText: 'Online (relay)'}).waitFor({timeout: 20000});
  await control('restore', {});

  // Take server version: confirm, Escape gives focus back, then confirm.
  await editDraft(page, (json) => { json.name = 'Mine'; });
  const theirs = await sourceWith((json) => { json.name = 'Theirs'; });
  await control('edit', {kind: 'npc', id: npc, source: theirs});
  await page.locator('.animus-badge', {hasText: 'changed on server'}).waitFor({timeout: 10000});
  await click(page, 'Apply 1');
  await page.locator('.animus-conflict').waitFor();
  await click(page, 'Take server version');
  await page.getByRole('alertdialog').getByText(`Take the server version of ${npc}?`).waitFor();
  await shot(page, `${label}-f05-take-confirm`);
  await page.keyboard.press('Escape');
  await page.getByRole('alertdialog').waitFor({state: 'detached'});
  await focused(page, 'animus-take-');
  await click(page, 'Take server version');
  await click(page, 'Replace my draft');
  await page.locator('.animus-conflict').waitFor({state: 'detached'});
  await page.locator('[data-testid="draft-count"]', {hasText: 'No changed drafts'}).waitFor();

  // Two tabs on one server: the second waits, then takes over.
  const second = await context.newPage();
  watch(second, errors, forced);
  await second.goto(page.url());
  await second.locator('[data-testid="live-cause"]', {hasText: 'runs in another tab'}).waitFor({timeout: 20000});
  assert.ok(await second.getByRole('button', {name: 'Validate'}).isDisabled(), 'the waiting tab cannot validate');
  await shot(second, `${label}-f06-second-tab`);
  await page.close();
  page = second;
  await page.locator('.animus-live-log li', {hasText: 'This tab now runs live editing'}).waitFor({state: 'attached', timeout: 20000});
  await page.locator('[data-testid="live-cause"]').waitFor({state: 'detached'});

  // Revoked: one notice, a Pair again button that opens the field.
  await expectNotice(page, 'was revoked on the server', () => control('revoke', {tokenId: 'all'}));
  await page.locator('.animus-live .animus-pill[data-phase="needsPairing"]').waitFor();
  assert.equal(await logCount(page, 'revoked'), 1, 'one notice for a revoked token');
  await click(page, 'Pair again');
  await page.getByRole('textbox', {name: 'New pairing link'}).waitFor();
  await shot(page, `${label}-f07-pair-again`);
  const {link} = await control('pair?paths=both', {});
  await page.getByRole('textbox', {name: 'New pairing link'}).fill(link);
  await page.locator('.animus-live-pair').getByRole('button', {name: 'Pair', exact: true}).click();
  await page.locator('[data-testid="pair-fingerprint"]').waitFor();
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await status(page, 'online');

  // Expired on the server: the same Pair again.
  await expectNotice(page, 'The pairing for this browser expired.', () => control('expire', {}));
  await page.getByRole('button', {name: 'Pair again'}).waitFor();

  // Forget: cancel gives focus back, confirm removes the server and its workspace.
  await page.goto(`${site}/#/`);
  await click(page, 'Forget Animus fake server');
  await page.getByRole('alertdialog').getByText('Forget Animus fake server?').waitFor();
  await shot(page, `${label}-f08-forget-confirm`);
  await page.getByRole('alertdialog').getByRole('button', {name: 'Cancel'}).click();
  await focused(page, 'animus-forget-');
  await click(page, 'Forget Animus fake server');
  await click(page, 'Forget server');
  await page.getByText('No server is paired yet').waitFor();
  assert.equal(await page.getByRole('link', {name: 'Animus fake server'}).count(), 0, 'the live workspace is gone too');
  await shot(page, `${label}-f09-forgotten`);

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
  const only = process.env.ANIMUS_PART;
  try {
    if (only !== 'failures') {
      await run(browser, 1440, 900, 'desktop-dark', 'dark');
      await run(browser, 390, 844, 'phone-light', 'light');
    }
    if (only !== 'main') {
      await failures(browser, 1440, 900, 'desktop-light', 'light');
      await failures(browser, 390, 844, 'phone-dark', 'dark');
    }
  } finally {
    await browser.close();
  }
  console.log('live editing browser check passed');
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
