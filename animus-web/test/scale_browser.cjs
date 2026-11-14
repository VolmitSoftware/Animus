// Pairing and editing at a real server's scale: 1200 documents (about 500
// NPCs). Pair, and with no click every kind's list counts the server's
// documents and the NPC list shows plain names; open an NPC, edit and apply; a server-side
// edit of the open, unedited NPC shows without a reload; a 5 s restart
// reconnects within 2 s of the server's return; no document is lost to the
// rate limiter. Then the cost of one keystroke and of one undo with 1200
// documents stored, read from IndexedDB itself: the header's generation moves
// when the commit is stored. "Stored" is seen by a worker on its own thread,
// so it is the write alone; ANIMUS_WRITE_MS (default 30) bounds the keystroke.
// "Seen" is read by the page, after it has redrawn for the edit;
// ANIMUS_KEYSTROKE_MS (default 110) and ANIMUS_UNDO_MS (default 100) bound it.
// The store writes before the page redraws and the page redraws once per
// edit; what is left of "seen" is the NPC form and preview building again.
//
// Part "seeded" measures the same keystroke and undo in a local workspace an
// older editor stored as one record (IndexedDB v2), which this editor moves
// onto split records first. Run it against an older build to get the
// before numbers.
//
//   dart run tool/fake_server.dart --port=<fake> --origin=http://127.0.0.1:<web> --scale=1200
//   python3 tool/serve_site.py --port <web>
//   ANIMUS_WEB_URL=http://127.0.0.1:<web> ANIMUS_FAKE_URL=http://127.0.0.1:<fake> node test/scale_browser.cjs
//   ANIMUS_PART=seeded ... node test/scale_browser.cjs      # the seeded-workspace timing only
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server started with --scale=1200'); })()).replace(/\/$/, '');
const part = process.env.ANIMUS_PART || 'all';
const writeBudget = Number(process.env.ANIMUS_WRITE_MS || 30);
const keystrokeBudget = Number(process.env.ANIMUS_KEYSTROKE_MS || 110);
const undoBudget = Number(process.env.ANIMUS_UNDO_MS || 100);

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status}`);
  return response.json();
}

async function serverSource(kind, id) {
  return (await control(`document?kind=${kind}&id=${encodeURIComponent(id)}`)).source;
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
}

/// One edit (an input event, or an Undo click), timed two ways: `stored`,
/// milliseconds until a worker watching IndexedDB from its own thread sees
/// the workspace header's generation move (the write itself, whatever the
/// page draws meanwhile); `seen`, until the page itself reads the new
/// generation, which also waits for the page to finish redrawing.
async function storedIn(page, workspace, action) {
  return page.evaluate(async ({workspace, action}) => {
    const now = () => performance.timeOrigin + performance.now();
    const watcher = new Worker(URL.createObjectURL(new Blob([`
      onmessage = async ({data: workspace}) => {
        const db = await new Promise((resolve) => { const r = indexedDB.open('animus-editor'); r.onsuccess = () => resolve(r.result); });
        const generation = () => new Promise((resolve) => {
          const read = db.transaction('workspaces', 'readonly').objectStore('workspaces').get(workspace);
          read.onsuccess = () => resolve(read.result ? read.result.generation : -1);
          read.onerror = () => resolve(-1);
        });
        const before = await generation();
        postMessage({ready: true});
        const deadline = performance.now() + 20000;
        while ((await generation()) <= before) {
          if (performance.now() > deadline) { postMessage({at: -1}); return; }
        }
        postMessage({at: performance.timeOrigin + performance.now()});
        db.close();
      };
    `], {type: 'text/javascript'})));
    const stored = new Promise((resolve) => {
      watcher.onmessage = ({data}) => { if (data.at !== undefined) resolve(data.at); };
    });
    const ready = new Promise((resolve) => watcher.addEventListener('message', ({data}) => { if (data.ready) resolve(); }));
    watcher.postMessage(workspace);
    await ready;
    const db = await new Promise((resolve, reject) => {
      const request = indexedDB.open('animus-editor');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    const generation = () => new Promise((resolve) => {
      const read = db.transaction('workspaces', 'readonly').objectStore('workspaces').get(workspace);
      read.onsuccess = () => resolve(read.result ? read.result.generation : -1);
      read.onerror = () => resolve(-1);
    });
    const before = await generation();
    const started = now();
    if (action === 'type') {
      const input = document.activeElement;
      input.value = `${input.value}x`;
      input.dispatchEvent(new Event('input', {bubbles: true}));
    } else {
      [...document.querySelectorAll('[aria-label="Edit history"] button')].find((button) => (button.getAttribute('aria-label') || '').startsWith('Undo')).click();
    }
    let seen = -1;
    for (;;) {
      if ((await generation()) > before) {
        seen = now() - started;
        break;
      }
      if (now() - started > 20000) break;
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
    db.close();
    const at = await stored;
    watcher.terminate();
    return {stored: at < 0 ? -1 : at - started, seen};
  }, {workspace, action});
}

/// Times [keys] keystrokes into the focused field, then one undo.
async function timeEdits(page, workspace, label, keys = 12) {
  const typed = [];
  for (let index = 0; index < keys; index++) {
    typed.push(await storedIn(page, workspace, 'type'));
    await page.waitForTimeout(60);
  }
  await page.waitForTimeout(400);
  const undo = await storedIn(page, workspace, 'undo');
  assert.ok([...typed, undo].every((edit) => edit.stored >= 0 && edit.seen >= 0), `${label}: every edit was stored`);
  const result = {
    keystrokeStoredMedianMs: Math.round(median(typed.map((edit) => edit.stored))),
    keystrokeStoredMaxMs: Math.round(Math.max(...typed.map((edit) => edit.stored))),
    keystrokeSeenMedianMs: Math.round(median(typed.map((edit) => edit.seen))),
    undoStoredMs: Math.round(undo.stored),
    undoSeenMs: Math.round(undo.seen),
  };
  console.log(`${label}: ${JSON.stringify(result)}`);
  return result;
}

/// Fails when an edit's timing passes its budget.
function withinBudget(timing, label) {
  assert.ok(timing.keystrokeStoredMedianMs < writeBudget, `${label}: keystroke stored after ${timing.keystrokeStoredMedianMs} ms`);
  assert.ok(timing.keystrokeSeenMedianMs < keystrokeBudget, `${label}: keystroke seen after ${timing.keystrokeSeenMedianMs} ms`);
  assert.ok(timing.undoSeenMs < undoBudget, `${label}: undo seen after ${timing.undoSeenMs} ms`);
}

/** The count a kind's list page shows ("1,200 NPCs" reads 1200). */
async function listCount(page, workspace, kind) {
  await page.evaluate((hash) => { location.hash = hash; }, `#/workspace/${encodeURIComponent(workspace)}/list/${kind}`);
  await page.locator('.ui-library-count').first().waitFor();
  return page.evaluate(() => Number((document.querySelector('.ui-library-count')?.textContent || '').replace(/[^0-9 ]/g, '').trim().split(/\s+/).pop()));
}

/** The save bar's line. */
function saveLine(page) {
  return page.evaluate(() => (document.querySelector('.ui-save-bar .ui-save-message')?.textContent || '').trim());
}

/** The open server's state beside the switcher (Online, Reconnecting...). */
function linkState(page) {
  return page.evaluate(() => (document.querySelector('[data-testid="context-state"]')?.textContent || '').trim());
}

async function live(browser) {
  await control('reset', {});
  await control('restore', {});
  const state = await control('state');
  const expected = {};
  for (const document of state.documents) expected[document.kind] = (expected[document.kind] || 0) + 1;
  assert.equal(state.documents.length, 1200, 'the fake server runs with --scale=1200');
  const {code} = await control('pair?paths=both', {});

  const context = await browser.newContext({viewport: {width: 1600, height: 1000}});
  // Every kind's list: Advanced lists the kinds Simple leaves out.
  await context.addInitScript(() => window.localStorage.setItem('animus.mode', 'advanced'));
  const page = await context.newPage();
  page.setDefaultTimeout(30000);
  const errors = [];
  let restarting = false;
  page.on('console', (message) => {
    if (message.type() !== 'error') return;
    if (restarting && /Failed to load resource|WebSocket connection|net::ERR_/.test(message.text())) return;
    errors.push(message.text());
  });
  page.on('pageerror', (error) => errors.push(String(error)));
  // The generated skins name textures that do not exist on Mojang's texture
  // host; their 404s are the browser's own lines, every other 404 is a fault.
  const missing = [];
  page.on('response', (response) => {
    if (response.status() === 404) missing.push(response.url());
  });

  // Pair, then no click: every kind's list counts the server's documents.
  await page.goto(`${site}/#/pair/${code}`);
  await page.getByRole('button', {name: 'Pair', exact: true}).click();
  await page.waitForFunction(() => location.hash.startsWith('#/server/'));
  const paired = Date.now();
  const serverId = decodeURIComponent((await page.evaluate(() => location.hash)).split('/')[2]);
  const workspace = `server-${serverId}`;
  for (const [kind, count] of Object.entries(expected)) {
    const deadline = Date.now() + 90000;
    let seen = await listCount(page, workspace, kind);
    while (seen !== count && Date.now() < deadline) {
      await page.waitForTimeout(250);
      seen = await listCount(page, workspace, kind);
    }
    assert.equal(seen, count, `${kind} count`);
  }
  const filled = Date.now() - paired;
  const limited = (await control('state')).rateLimited;
  console.log(`all ${state.documents.length} documents listed ${filled} ms after pairing; rate-limited answers: ${JSON.stringify(limited)}`);

  // Named rows: plain names, the id where a name has nothing visible.
  await page.goto(`${site}/#/workspace/${encodeURIComponent(workspace)}/npcs`);
  const npcs = state.documents.filter((document) => document.kind === 'npc');
  const named = [];
  for (const document of npcs.slice(0, 40)) {
    const source = await serverSource('npc', document.id);
    named.push({id: document.id, name: source.name});
  }
  const plain = named.find((entry) => /^&[0-9a-f]\w/.test(entry.name || ''));
  await page.locator('#npc-list .ui-library-row').first().waitFor();
  if (plain) {
    // The list draws only the rows in sight: find the row first.
    const text = plain.name.replace(/&[0-9a-fk-or]/g, '');
    await page.getByLabel('Search npcs', {exact: true}).fill(text);
    await page.locator('#npc-list .ui-library-row').getByText(text, {exact: true}).first().waitFor();
  }
  const shown = await page.evaluate(() => document.body.innerText);
  assert.ok(!/&[0-9a-f][A-Za-z]/.test(shown), 'no colour codes in the NPC list');

  // Open an NPC, edit its name, apply.
  const target = npcs[7].id;
  await page.goto(`${site}/#/workspace/${encodeURIComponent(workspace)}/npc/${encodeURIComponent(target)}`);
  const name = page.getByLabel('Name', {exact: true}).first();
  await name.fill('Scale Tested');
  await page.waitForFunction(() => /^1 change not applied/.test(document.querySelector('.ui-save-bar .ui-save-message')?.textContent || ''));
  await page.locator('.ui-save-bar [data-save-action="apply"]').click();
  await page.waitForFunction(() => /^(Applied 1 change|Up to date)/.test(document.querySelector('.ui-save-bar .ui-save-message')?.textContent || ''));
  assert.equal((await serverSource('npc', target)).name, 'Scale Tested', 'the apply reached the server');

  // A server-side edit of the open, unedited NPC shows without a reload.
  const other = npcs[11].id;
  await page.goto(`${site}/#/workspace/${encodeURIComponent(workspace)}/npc/${encodeURIComponent(other)}`);
  await page.getByLabel('Name', {exact: true}).first().waitFor();
  const changed = await serverSource('npc', other);
  changed.name = 'Edited On The Server';
  await control('edit', {kind: 'npc', id: other, source: changed});
  await page.waitForFunction(() => [...document.querySelectorAll('input')].some((input) => input.value === 'Edited On The Server'), null, {timeout: 10000});

  // A 5 s restart: online again within 2 s of the server's return.
  restarting = true;
  const down = Date.now();
  await control('offline', {seconds: 5});
  await page.waitForFunction(() => (document.querySelector('[data-testid="context-state"]')?.textContent || '').trim() !== 'Online', null, {timeout: 10000});
  await page.waitForFunction(() => /Server restarting\. Reconnecting\./.test(document.querySelector('.ui-save-bar')?.innerText || ''), null, {timeout: 5000});
  await page.waitForFunction(() => (document.querySelector('[data-testid="context-state"]')?.textContent || '').trim() === 'Online', null, {timeout: 20000});
  const back = Date.now() - (down + 5000);
  console.log(`online ${back} ms after the server returned (${await linkState(page)}; ${await saveLine(page)})`);
  assert.ok(back <= 2000, `reconnected ${back} ms after the return`);
  restarting = false;

  // Keystroke and undo cost with 1200 documents stored.
  await page.goto(`${site}/#/workspace/${encodeURIComponent(workspace)}/npc/${encodeURIComponent(target)}`);
  await page.getByLabel('Name', {exact: true}).first().click();
  withinBudget(await timeEdits(page, workspace, 'server workspace, 1200 documents'), 'server workspace');

  const after = (await control('state')).rateLimited;
  console.log(`rate-limited answers over the run: ${JSON.stringify(after)}`);
  const foreign = missing.filter((url) => !url.startsWith('https://textures.minecraft.net/'));
  assert.deepEqual(foreign, [], 'no 404 but the generated skin textures');
  assert.deepEqual(errors.filter((text) => !/Failed to load resource: the server responded with a status of 404/.test(text)), [], 'console errors');
  await context.close();
}

/// A local workspace of the fake server's 1200 documents, stored the way an
/// older editor did (IndexedDB v2, one record of JSON text).
async function seeded(browser) {
  const state = await control('state');
  const documents = [];
  for (const version of state.documents) {
    const source = await serverSource(version.kind, version.id);
    documents.push({kind: version.kind, id: version.id, path: version.path, source: JSON.stringify(source, null, 2), diagnostics: []});
  }
  const text = JSON.stringify({schemaVersion: 3, id: 'ws-seeded', name: 'Seeded 1200', generation: 1, documents});
  const context = await browser.newContext({viewport: {width: 1600, height: 1000}});
  const page = await context.newPage();
  page.setDefaultTimeout(60000);
  await page.goto(`${site}/favicon.svg`);
  await page.evaluate(async (record) => {
    const db = await new Promise((resolve, reject) => {
      const request = indexedDB.open('animus-editor', 2);
      request.onupgradeneeded = () => {
        request.result.createObjectStore('workspaces', {keyPath: 'id'});
        request.result.createObjectStore('undo', {keyPath: 'key'});
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    await new Promise((resolve, reject) => {
      const write = db.transaction('workspaces', 'readwrite');
      write.objectStore('workspaces').put(record);
      write.oncomplete = resolve;
      write.onerror = () => reject(write.error);
    });
    db.close();
  }, {id: 'ws-seeded', generation: 1, text});
  const target = state.documents.find((document) => document.kind === 'npc');
  const opened = Date.now();
  await page.goto(`${site}/#/workspace/ws-seeded/npc/${encodeURIComponent(target.id)}`);
  await page.getByLabel('Name', {exact: true}).first().click();
  console.log(`seeded workspace opened ${Date.now() - opened} ms after navigation`);
  await page.waitForTimeout(1500);
  const timing = await timeEdits(page, 'ws-seeded', 'seeded local workspace, 1200 documents');
  await context.close();
  return timing;
}

(async () => {
  const browser = await chromium.launch({args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
  try {
    if (part === 'all' || part === 'live') await live(browser);
    if (part === 'all' || part === 'seeded') {
      const timing = await seeded(browser);
      if (process.env.ANIMUS_EXPECT_FAST !== '0') withinBudget(timing, 'seeded workspace');
    }
  } finally {
    await browser.close();
  }
  console.log('scale browser check passed');
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
