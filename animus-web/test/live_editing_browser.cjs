// Live editing against tool/fake_server.dart at desktop and phone widths, in
// the dark and light themes: pair from a link, land on Home with the whole
// server listed and no Open step, open a document, edit it, validate on the
// server, start a viewer-private preview (the live NPC it hides is named
// until End preview), apply, make a document change on the server too and
// merge it with Compare, drop the direct path (failover to the relay, then
// back to direct once it recovers) and then every path (reconnect and its
// resync line, once with a server edit made meanwhile), apply after recovery
// and undo an apply from the history behind a confirm. The second part runs
// the failure paths through the fake server's test controls: links that are
// not links or expired, the expiry note, a forced scope-missing, a lost apply
// answer recovered by its key, a relay error, a limit close, a preview for
// an offline player, Take server behind a confirm, two tabs with one session
// owner, revoked and expired tokens with the new-link field in Review, and
// Forget server from the start page. No console errors other than the
// browser's own network lines while a path is down.
// The live controls (connection, changes, Validate, Preview in game, Apply,
// the activity lines, documents changed on the server, the history) are in
// the Review sheet the save bar opens, in Advanced mode; edits go through
// the Code view.
//
//   python3 tool/serve_site.py --port <site port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<site port>
//   PLAYWRIGHT_MODULE=/opt/homebrew/lib/node_modules/@playwright/cli/node_modules/playwright \
//     ANIMUS_WEB_URL=http://127.0.0.1:<site port> ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> \
//     ANIMUS_SCREENSHOTS=/tmp/animus-shots node test/live_editing_browser.cjs
//
// ANIMUS_PART=main or failures runs one half.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {closeDrawer, saveAction, saveState, sidebar} = require('./support/shell.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');
const shots = process.env.ANIMUS_SCREENSHOTS;
const npc = 'examples/greeter';

const reviewBody = (page) => page.locator('#ui-review [data-testid="review"]');

/** The Review sheet when it is open, else the page. */
async function scope(page) {
  return (await reviewBody(page).isVisible().catch(() => false)) ? page.locator('#ui-review') : page;
}

async function click(page, name) {
  await (await scope(page)).getByRole('button', {name, exact: true}).first().click();
}

/** Opens the Review sheet from the save bar, unless it is open. */
async function review(page) {
  if (await reviewBody(page).isVisible().catch(() => false)) return;
  await saveAction(page, 'review').click();
  await reviewBody(page).waitFor();
}

/** Closes the Review sheet if it is open. */
async function leaveReview(page) {
  if (!(await reviewBody(page).isVisible().catch(() => false))) return;
  await page.locator('#ui-review').getByRole('button', {name: 'Close'}).first().click();
  await reviewBody(page).waitFor({state: 'hidden'});
}

/** The newest activity line's time, 0 when there is none. */
const newestAt = (page) => page.evaluate(() => Math.max(0, ...[...document.querySelectorAll('#ui-review .ui-review-log [data-at]')].map((li) => Number(li.getAttribute('data-at')))));

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

/// Runs [action] and waits until Review's activity gains a line with [text].
async function expectNotice(page, text, action) {
  await review(page);
  const before = await newestAt(page);
  await action();
  try {
    await page.waitForFunction(
      ([wanted, after]) => [...document.querySelectorAll('#ui-review .ui-review-log [data-at]')].some((li) => Number(li.getAttribute('data-at')) > after && li.textContent.includes(wanted)),
      [text, before],
      {timeout: 15000},
    );
  } catch (error) {
    const log = await page.locator('#ui-review .ui-review-log [data-at]').allTextContents().catch(() => []);
    throw new Error(`waiting for "${text}"; activity: ${JSON.stringify(log)}`);
  }
}

/// Opens [id] in the server's workspace (the server page is [page]'s).
async function openFromCatalog(page, id) {
  const server = await page.evaluate(() => decodeURIComponent(location.hash.split('/')[2] || ''));
  const workspace = server.startsWith('server-') ? server : `server-${server}`;
  await page.evaluate((hash) => { location.hash = hash; }, `#/workspace/${encodeURIComponent(workspace)}/npc/${encodeURIComponent(id)}`);
  await page.waitForFunction((wanted) => decodeURIComponent(location.hash).includes(`/npc/${wanted}`), id);
  await page.locator('.ui-page-header').waitFor({timeout: 20000});
}

/// The connection badge in Review (Advanced).
const pill = (page) => reviewBody(page).locator('[role="status"][data-phase]').first();

async function status(page, phase) {
  await review(page);
  await reviewBody(page).locator(`[role="status"][data-phase="${phase}"]`).first().waitFor({timeout: 20000});
}

/// Waits until the save bar's line matches [text].
async function saveLine(page, text) {
  await saveState(page).filter({hasText: text}).waitFor({timeout: 20000});
}

/// Sets the open view through the address ([view] is visual, code or split).
async function view(page, name) {
  await page.evaluate((wanted) => {
    const [route] = location.hash.split('?');
    location.hash = `${route}?view=${wanted}`;
  }, name);
}

/// Replaces the NPC draft through the Code view.
async function editDraft(page, change) {
  await leaveReview(page);
  await view(page, 'code');
  const area = page.locator('.animus-code textarea').first();
  const json = JSON.parse(await area.inputValue());
  change(json);
  await area.fill(JSON.stringify(json, null, 2));
  await page.getByRole('button', {name: 'Use this code'}).click();
  // The save bar counts the draft before anything else is clicked: its
  // buttons move when Apply appears.
  await page.waitForFunction(() => /not applied|kept/.test(document.querySelector('.ui-save-bar .ui-save-message')?.textContent || ''));
  await view(page, 'visual');
}

/// A browser context in [theme], with console errors collected. While
/// [allow] returns true for a line it is the browser's own network noise.
async function themed(browser, width, height, theme) {
  const context = await browser.newContext({viewport: {width, height}});
  await context.addInitScript((mode) => {
    window.localStorage.setItem('animus.theme', mode);
    window.localStorage.setItem('animus.mode', 'advanced');
  }, theme);
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

async function pairWith(page, code, {remember = true} = {}) {
  await page.goto(`${site}/#/pair/${code}`);
  await page.getByTestId('pair-submit').waitFor();
  const toggle = page.getByRole('switch', {name: 'Stay paired'});
  if ((await toggle.isChecked()) !== remember) await toggle.click();
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

  // Pair: the server's name, what the editor may do, the link out of the address.
  await page.goto(`${site}/#/pair/${code}`);
  await page.getByRole('heading', {level: 1, name: 'Animus fake server'}).waitFor();
  await page.getByText('Edit NPCs, behaviors, shops and GUIs').waitFor();
  assert.match(await page.getByTestId('pair-fingerprint').textContent(), /^sha256:/, 'Advanced keeps the fingerprint under Details');
  assert.equal(await page.evaluate(() => location.hash), '#/pair', 'the link code leaves the address once read');
  assert.equal(await page.getByRole('switch', {name: 'Stay paired'}).count(), 1, 'remember is a labelled switch');
  assert.equal(await page.getByRole('switch', {name: 'Stay paired'}).isChecked(), true, 'remember starts on');
  await shot(page, `${label}-01-pair`);
  await page.getByTestId('pair-submit').click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  assert.ok(!(await page.evaluate(() => location.href)).includes('ANM1.'), 'the pairing secret leaves the address');
  await status(page, 'online');
  const tokens = (await control('state')).tokens;
  assert.equal(tokens.length, 1, 'one token issued');
  await leaveReview(page);

  // Home lists the whole server with no Open step.
  const npcs = (await control('state')).documents.filter((document) => document.kind === 'npc').length;
  await page.locator('.ui-home-card[data-job="npcs"] [data-testid="home-count"]', {hasText: new RegExp(`^${npcs}$`)}).waitFor({timeout: 20000});
  await shot(page, `${label}-02-home`);
  await openFromCatalog(page, npc);

  // Edit, validate, preview.
  await editDraft(page, (json) => { json.name = 'Greeter Prime'; });
  await saveLine(page, /^1 change not applied/);
  await (await sidebar(page)).locator('.arcane-sidebar-group-label', {hasText: 'Changed'}).waitFor();
  await closeDrawer(page);
  await review(page);
  await reviewBody(page).getByTestId('review-changes').getByText('Changed name', {exact: false}).waitFor();
  await expectNotice(page, 'The server accepts', () => click(page, 'Validate'));
  await click(page, 'Preview in game');
  await page.locator('[data-testid="preview-state"]', {hasText: 'for your player only'}).waitFor();
  assert.equal((await control('state')).previews.length, 1, 'the fake server runs one preview');
  await page.locator('[data-testid="preview-hidden"]', {hasText: `Hidden while previewing: ${npc}`}).waitFor();
  await shot(page, `${label}-03-preview`);

  // Apply.
  const before = await serverDocument('npc', npc);
  await expectNotice(page, 'Applied 1 document', () => click(page, 'Apply 1'));
  await saveLine(page, /Applied 1 change|Up to date/);
  const applied = await serverDocument('npc', npc);
  assert.equal(applied.revision, before.revision + 1, 'the server raised the revision');
  await expectNotice(page, 'The preview ended.', () => click(page, 'End preview'));
  await page.locator('[data-testid="preview-state"]', {hasText: 'The preview ended.'}).waitFor();
  await page.locator('[data-testid="preview-hidden"]').waitFor({state: 'detached'});
  await shot(page, `${label}-03b-preview-ended`);

  // Changed here and on the server: Review lists it, Compare merges it.
  await editDraft(page, (json) => { json.name = 'Greeter Second'; });
  const edited = await sourceWith((json) => { json.spawned = !json.spawned; });
  const current = await control('edit', {kind: 'npc', id: npc, source: edited});
  assert.ok(current.revision > applied.revision);
  await review(page);
  const serverChanged = reviewBody(page).getByTestId('review-server-changed');
  await serverChanged.getByRole('button', {name: 'Compare'}).waitFor({timeout: 10000});
  assert.equal(await serverChanged.getByText('Changed on server').count(), 0, 'the section heading says it once, with no badge per row');
  conflictExpected = true;
  await serverChanged.getByRole('button', {name: 'Compare'}).click();
  const compare = page.getByRole('dialog', {name: /^Compare /});
  await compare.getByText('so both can be kept', {exact: false}).waitFor();
  await shot(page, `${label}-04-compare`);
  await compare.getByRole('button', {name: 'Keep both'}).click();
  await compare.waitFor({state: 'hidden'});
  conflictExpected = false;
  await serverChanged.waitFor({state: 'detached'});
  await expectNotice(page, 'Applied 1 document', () => click(page, 'Apply 1'));
  const merged = await fetchSource(npc);
  assert.equal(merged.name, 'Greeter Second');
  assert.equal(merged.spawned, edited.spawned, 'the server side of the merge survived');
  await reviewBody(page).getByText('No changes to apply.').waitFor();
  assert.equal(await page.getByRole('textbox', {name: 'Change note'}).count(), 0, 'no change note while nothing waits');
  const history = reviewBody(page).getByTestId('review-history');
  await history.getByText('Applied Greeter Second', {exact: true}).first().waitFor();
  assert.equal(await history.getByText(/^Applied \d+ documents?$/).count(), 0, 'each apply names its documents');

  // Drop the direct path: the editor fails over to the relay.
  networkDown = true;
  await control('drop', {path: 'direct', seconds: 8});
  await review(page);
  await pill(page).filter({hasText: 'Online (relay)'}).waitFor({timeout: 20000});
  await editDraft(page, (json) => { json.name = 'Over The Relay'; });
  await expectNotice(page, 'Applied 1 document', () => click(page, 'Apply 1'));
  assert.equal((await fetchSource(npc)).name, 'Over The Relay');
  await shot(page, `${label}-05-relay`);

  // Once the drop ends, the relay session probes the direct path (15 s
  // after going online over the relay) and moves back to it without going
  // offline.
  const phases = [];
  await review(page);
  await page.exposeFunction(`animusPhase_${label}`, (phase) => phases.push(phase));
  await page.evaluate((name) => {
    const badge = () => document.querySelector('#ui-review [data-testid="review"] [role="status"][data-phase]');
    window.animusPhases = new MutationObserver(() => window[name](badge()?.getAttribute('data-phase')));
    window.animusPhases.observe(document.body, {subtree: true, attributes: true, childList: true});
  }, `animusPhase_${label}`);
  await pill(page).filter({hasText: 'Online (direct)'}).waitFor({timeout: 45000});
  await page.evaluate(() => window.animusPhases.disconnect());
  assert.ok(phases.every((phase) => phase === 'online'), `${label}: the switch back stays online: ${JSON.stringify([...new Set(phases)])}`);
  await waitForStreams({direct: 1, relay: 0}, `${label}: one direct event stream after the switch`);
  await editDraft(page, (json) => { json.name = 'Direct Again'; });
  await expectNotice(page, 'Applied 1 document', () => click(page, 'Apply 1'));
  assert.equal((await fetchSource(npc)).name, 'Direct Again');
  await shot(page, `${label}-05b-direct-again`);

  // Drop every path: the editor waits and reconnects once the server is back
  // (long enough for the edit below; restore ends it sooner).
  await control('drop', {path: 'all', seconds: 15});
  await status(page, 'reconnecting');
  await editDraft(page, (json) => { json.name = 'After Recovery'; });
  await shot(page, `${label}-06-reconnecting`);
  await expectNotice(page, 'Resynced with the server; nothing changed while disconnected.', async () => {
    await control('restore', {});
    await (await scope(page)).getByRole('button', {name: 'Retry now'}).first().click().catch(() => {});
  });
  await status(page, 'online');
  await expectNotice(page, 'Applied 1 document', () => click(page, 'Apply 1'));
  assert.equal((await fetchSource(npc)).name, 'After Recovery');
  // Sockets the browser pooled before the cut may still reset once.
  networkDown = false;

  // Undo an apply from the history, behind a confirmation: Escape cancels
  // (Review stays) and gives focus back to the button, then confirm.
  await review(page);
  const undo = reviewBody(page).getByTestId('review-history').getByRole('button', {name: 'Undo', exact: true}).first();
  await undo.click();
  await page.getByRole('alertdialog').getByText('Undo this apply on the server?').waitFor();
  await shot(page, `${label}-07-undo-confirm`);
  await page.keyboard.press('Escape');
  await page.getByRole('alertdialog').waitFor({state: 'hidden'});
  await reviewBody(page).waitFor();
  await focused(page, 'review-undo-');
  assert.equal((await fetchSource(npc)).name, 'After Recovery', 'cancel wrote nothing');
  await undo.click();
  const undoneAt = await newestAt(page);
  await expectNotice(page, 'Undid 1 document', () => page.getByRole('alertdialog').getByRole('button', {name: 'Undo on the server'}).click());
  assert.equal((await fetchSource(npc)).name, 'Direct Again');
  // The undo's own catalog event follows within moments; it is not news.
  await page.waitForTimeout(1000);
  const refreshed = await page.evaluate((after) => [...document.querySelectorAll('#ui-review .ui-review-log [data-at]')]
    .filter((li) => Number(li.getAttribute('data-at')) > after && li.textContent.includes('changed on the server')).length, undoneAt);
  assert.equal(refreshed, 0, `${label}: the editor's own undo is not reported as a server change`);
  await shot(page, `${label}-07-undo`);

  // An edit made on the server while this editor is cut off: the reconnect
  // resyncs, takes the server copy of the clean document and says so in one line.
  networkDown = true;
  await control('drop', {path: 'all', seconds: 15});
  await status(page, 'reconnecting');
  await control('edit', {kind: 'npc', id: npc, source: await sourceWith((json) => { json.name = 'Changed While Away'; })});
  await expectNotice(page, 'Resynced with the server: 1 document refreshed.', async () => {
    await control('restore', {});
    await (await scope(page)).getByRole('button', {name: 'Retry now'}).first().click().catch(() => {});
  });
  await status(page, 'online');
  await reviewBody(page).getByText('No changes to apply.', {exact: true}).waitFor();
  await shot(page, `${label}-07b-resynced`);
  networkDown = false;

  assert.deepEqual(errors, [], `${label}: console errors`);
  await context.close();
}

/// A pairing code whose expiry is in the past, as a code minted long ago reads.
function expiredCode(code) {
  const payload = JSON.parse(Buffer.from(code.slice(5), 'base64url').toString('utf8'));
  payload.expiresAt = '2020-01-01T00:00:00Z';
  return `ANM1.${Buffer.from(JSON.stringify(payload)).toString('base64url')}`;
}

/// Counts Review's activity lines holding [text].
async function logCount(page, text) {
  return page.locator('#ui-review .ui-review-log [data-at]', {hasText: text}).count();
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
  await page.getByRole('heading', {level: 1, name: 'Pair a server'}).waitFor();
  const problem = page.getByTestId('pair-problem');
  await problem.getByText('This is not a pairing link.').waitFor();
  await problem.getByText('run /animus editor pair for a new one', {exact: false}).waitFor();
  await page.getByRole('textbox', {name: 'New link'}).waitFor();
  await shot(page, `${label}-f01-bad-code`);
  assert.equal(await page.evaluate(() => location.hash), '#/pair', 'a bad code leaves the address too');

  // A pasted link stays out of the address and history: back goes to the
  // start page, and forward finds the code-free pair page.
  const {code: pasted} = await control('pair?paths=both', {});
  await page.goto(`${site}/#/`);
  await page.getByRole('textbox', {name: 'Pairing link'}).fill(`${site}/#/pair/${pasted}`);
  await page.getByTestId('start-pair-link-submit').click();
  await page.getByTestId('pair-submit').waitFor();
  assert.equal(await page.evaluate(() => location.hash), '#/pair', 'the pasted code leaves the address');
  await page.goBack();
  await page.waitForFunction(() => !location.hash.startsWith('#/pair'));
  await page.goForward();
  await problem.getByText('No pairing link here.').waitFor();
  assert.ok(!(await page.evaluate(() => location.href)).includes('ANM1.'), 'history keeps no pairing code');

  // An expired link: no Pair, the next step and the paste field.
  const {code: fresh} = await control('pair?paths=both', {});
  await page.getByRole('textbox', {name: 'New link'}).fill(expiredCode(fresh));
  await page.getByTestId('pair-new-link-submit').click();
  await problem.getByText('This link expired.').waitFor();
  assert.equal(await page.evaluate(() => location.hash), '#/pair', 'a pasted link keeps the code out of the address');
  assert.equal(await page.getByTestId('pair-submit').count(), 0, 'an expired link offers no Pair');
  await shot(page, `${label}-f02-expired-code`);

  // A token with a day left: Review says so.
  const {code} = await control('pair?paths=both&days=1', {});
  await pairWith(page, code, {remember: true});
  await status(page, 'online');
  await page.locator('#ui-review [data-testid="live-expiry"]', {hasText: 'Pairing ends in'}).waitFor();
  await openFromCatalog(page, npc);
  await shot(page, `${label}-f03-expiry-note`);

  // A forced scope-missing names the permission and the pair command that
  // keeps the token's own scopes, offers no Retry, and keeps the change and note.
  await editDraft(page, (json) => { json.name = 'Scoped'; });
  await review(page);
  await page.getByRole('textbox', {name: 'Change note'}).fill('keep me');
  await control('fail?code=scope-missing&times=1&route=/v1/changes&scope=apply:commands', {});
  await expectNotice(page, 'This pairing lacks the Run commands permission. Run /animus editor pair scopes=read,validate,preview,apply:npcs,apply:behaviors,apply:gui,apply:rigs,import,spawn,terrain,apply:commands and open the new link.',
    () => click(page, 'Apply 1'));
  await saveLine(page, /^Apply failed: This pairing lacks the Run commands permission\.$/);
  assert.equal(await saveAction(page, 'retry').count(), 0, 'no Retry for a missing permission');
  assert.equal(await page.getByRole('textbox', {name: 'Change note'}).inputValue(), 'keep me', 'a refused apply keeps the note');

  // A lost apply answer: the editor reconnects and asks by the key.
  await control('drop-answer', {});
  await expectNotice(page, 'Applied 1 document', () => click(page, 'Apply 1'));
  assert.equal((await fetchSource(npc)).name, 'Scoped');
  await status(page, 'online');

  // A preview for a player who is not on the server.
  await control('player?online=false', {});
  await expectNotice(page, 'Your player is not on the server.', () => click(page, 'Preview in game'));
  await control('player?online=true', {});
  await shot(page, `${label}-f04-player-offline`);

  // The server closes the stream at its limit: one notice, then online. The
  // close must reach the stream the editor listens on, not one the
  // reconnect above is still replacing.
  await waitForStreams({direct: 1, relay: 0}, `${label}: one event stream before the limit close`);
  await page.waitForTimeout(300);
  await expectNotice(page, 'Fell behind on server events.', () => control('limit', {}));
  await status(page, 'online');
  assert.equal(await logCount(page, 'Fell behind on server events.'), 1, 'one notice for one close');

  // A relay error, with the direct path down so the relay carries the session.
  await control('drop', {path: 'direct', seconds: 60});
  await review(page);
  await pill(page).filter({hasText: 'Online (relay)'}).waitFor({timeout: 20000});
  await expectNotice(page, 'Relay is full.', () => control('relay-error?code=capacity', {}));
  await review(page);
  await pill(page).filter({hasText: 'Online (relay)'}).waitFor({timeout: 20000});
  await control('restore', {});

  // Compare reads the server copy; one field changed on both sides is a
  // choice between Take server and Keep mine. A newer server change drops
  // that copy, so Take server takes the newest. Take server asks first:
  // Escape gives focus back, then confirm.
  await editDraft(page, (json) => { json.name = 'Mine'; });
  const theirs = await sourceWith((json) => { json.name = 'Theirs'; });
  await control('edit', {kind: 'npc', id: npc, source: theirs});
  await review(page);
  const serverChanged = reviewBody(page).getByTestId('review-server-changed');
  await serverChanged.getByRole('button', {name: 'Compare'}).waitFor({timeout: 10000});
  await serverChanged.getByRole('button', {name: 'Compare'}).click();
  const compare = page.getByRole('dialog', {name: /^Compare /});
  await compare.getByText('Name was changed on both sides.').waitFor();
  assert.equal(await compare.getByRole('button', {name: 'Keep mine'}).count(), 1);
  assert.equal(await compare.getByRole('group', {name: /^Keep for/}).count(), 0, 'one field needs no switch');
  await page.keyboard.press('Escape');
  await compare.waitFor({state: 'hidden'});
  const newest = await sourceWith((json) => { json.name = 'Theirs Newest'; });
  await control('edit', {kind: 'npc', id: npc, source: newest});
  // The change event has no mark of its own on screen; give it the loopback's moment.
  await page.waitForTimeout(400);
  await serverChanged.getByRole('button', {name: 'Take server'}).click();
  await page.getByRole('alertdialog').getByText(/^Take the server version of .+\?$/).waitFor();
  await shot(page, `${label}-f05-take-confirm`);
  await page.keyboard.press('Escape');
  await page.getByRole('alertdialog').waitFor({state: 'hidden'});
  await focused(page, 'review-take-');
  await serverChanged.getByRole('button', {name: 'Take server'}).click();
  await page.getByRole('alertdialog').getByRole('button', {name: 'Take server version'}).click();
  await serverChanged.waitFor({state: 'detached'});
  await saveLine(page, /Up to date|Applied/);
  assert.equal((await fetchSource(npc)).name, 'Theirs Newest', 'taking the server copy writes nothing');
  assert.equal((await draftJson(page)).name, 'Theirs Newest', 'Take server took the newest server copy');

  // Two tabs on one server: the second waits, then takes over.
  const second = await context.newPage();
  watch(second, errors, forced);
  await second.goto(page.url());
  await second.waitForFunction(() => /^Open in another tab/.test(document.querySelector('.ui-save-bar .ui-save-message')?.textContent || ''), null, {timeout: 20000});
  assert.equal(await second.locator('.ui-save-bar [data-save-action="takeOver"]').count(), 1, 'the waiting tab offers Take over');
  await shot(second, `${label}-f06-second-tab`);
  // Taking over opens a fresh connection (hello, then the event socket); the revoke below must reach a session that finished it.
  const events = second.waitForEvent('websocket', {predicate: (socket) => socket.url().endsWith('/events'), timeout: 20000});
  await page.close();
  page = second;
  await page.waitForFunction(() => !/^Open in another tab/.test(document.querySelector('.ui-save-bar .ui-save-message')?.textContent || ''), null, {timeout: 20000});
  await review(page);
  await page.locator('#ui-review .ui-review-log [data-at]', {hasText: 'This tab now runs live editing'}).waitFor({state: 'attached', timeout: 20000});
  await events;
  await status(page, 'online');

  // Revoked: one notice, and Review asks for a new link.
  await expectNotice(page, 'Pairing revoked on the server.', () => control('revoke', {tokenId: 'all'}));
  await status(page, 'needsPairing');
  assert.equal(await logCount(page, 'revoked'), 1, 'one notice for a revoked token');
  const renew = reviewBody(page).getByTestId('review-pair');
  await renew.getByRole('textbox', {name: 'New link'}).waitFor();
  await shot(page, `${label}-f07-pair-again`);
  const {link} = await control('pair?paths=both', {});
  await renew.getByRole('textbox', {name: 'New link'}).fill(link);
  await renew.getByTestId('review-pair-link-submit').click();
  await page.getByTestId('pair-submit').click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  await status(page, 'online');

  // Expired on the server: the same field.
  await expectNotice(page, 'Pairing expired.', () => control('expire', {}));
  await reviewBody(page).getByTestId('review-pair').getByRole('textbox', {name: 'New link'}).waitFor();

  // Forget from the start page: cancel gives focus back, confirm removes the server and its workspace.
  await page.goto(`${site}/#/`);
  const serverRow = page.locator('.ui-continue-row[data-kind="server"]');
  await serverRow.getByRole('button', {name: 'More actions'}).click();
  await page.getByRole('menuitem', {name: 'Forget server'}).click();
  await page.getByRole('alertdialog').getByText('Forget Animus fake server?').waitFor();
  await shot(page, `${label}-f08-forget-confirm`);
  await page.getByRole('alertdialog').getByRole('button', {name: 'Cancel'}).click();
  await page.getByRole('alertdialog').waitFor({state: 'hidden'});
  await focused(page, 'continue-more-');
  await serverRow.getByRole('button', {name: 'More actions'}).click();
  await page.getByRole('menuitem', {name: 'Forget server'}).click();
  await page.getByRole('alertdialog').getByRole('button', {name: 'Forget server'}).click();
  await serverRow.waitFor({state: 'detached'});
  assert.equal(await page.locator('.ui-continue-name', {hasText: 'Animus fake server'}).count(), 0, 'the live workspace is gone too');
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

/// The open document as the editor holds it, read from the Code view.
async function draftJson(page) {
  await leaveReview(page);
  await view(page, 'code');
  const json = JSON.parse(await page.locator('.animus-code textarea').first().inputValue());
  await view(page, 'visual');
  return json;
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
