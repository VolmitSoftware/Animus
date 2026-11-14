// Simple names what it shows. Pairs the fake server with real-shaped data
// (support/real_shape.cjs) and documents that hold every kind of id Simple
// used to print: a modded body, a zombie and an item frame and a painting
// with enchanted equipment, a hologram with block and entity lines, a
// behavior and a conversation that open a GUI and a shop, give an item and
// show a particle, and an NPC without a readable name. Every tab of each
// page, its disclosures and item slots are opened; no namespaced id
// (`minecraft:zombie`, `town:bakery`) or document id may show in visible
// text, field values, placeholders, titles or (i) tips. 1440 px, no console
// errors.
//
//   python3 tool/serve_site.py --port <web port>
//   dart run tool/fake_server.dart --port=<fake port> --origin=http://127.0.0.1:<web port>
//   PLAYWRIGHT_MODULE=/path/to/node_modules/playwright ANIMUS_WEB_URL=http://127.0.0.1:<web port> \
//     ANIMUS_FAKE_URL=http://127.0.0.1:<fake port> node test/simple_names_browser.cjs
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const realShape = require('./support/real_shape.cjs');

const site = (process.env.ANIMUS_WEB_URL || (() => { throw new Error('Set ANIMUS_WEB_URL to the served site, never the shared :8098'); })()).replace(/\/$/, '');
const fake = (process.env.ANIMUS_FAKE_URL || (() => { throw new Error('Set ANIMUS_FAKE_URL to your own fake server, never :9797'); })()).replace(/\/$/, '');

async function control(route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert.ok(response.ok, `${route}: ${response.status} ${await response.clone().text()}`);
  return response.json();
}

function npc(id, name, type, extra = {}) {
  return {
    schemaVersion: 2, revision: 1, uuid: `0a0b0c0d-0e0f-4000-8000-${String(id.length).padStart(4, '0')}${id.replace(/[^a-f0-9]/g, '').padEnd(8, '0').slice(0, 8)}`,
    id: id.split('/').pop(), group: id.split('/').slice(0, -1).join('/'), name, type, spawned: true,
    position: {world: 'world', x: 20.5, y: 64, z: 20.5, yaw: 0, pitch: 0}, ...extra,
  };
}

const enchanted = '[enchantments={"minecraft:sharpness":5},custom_name="Oathkeeper"]';
const conversation = {
  schemaVersion: 1, revision: 1, id: 'names:talk', entry: 'main',
  routines: {main: {type: 'sequence', steps: [
    {type: 'action', capability: 'animus:say', arguments: {text: 'Take this.'}},
    {type: 'action', capability: 'animus:give-item', arguments: {item: 'minecraft:diamond_sword', amount: 1}},
    {type: 'action', capability: 'animus:spawn-particle', arguments: {particle: 'minecraft:happy_villager'}},
    {type: 'action', capability: 'animus:open-shop', arguments: {shop: 'shop:bakery'}},
  ]}},
  triggers: [{id: 'click', event: 'interaction', cooldownTicks: 40}],
};
const behavior = {
  schemaVersion: 1, revision: 1, id: 'names:counter', entry: 'main', server: false,
  routines: {main: {type: 'sequence', steps: [
    {type: 'action', capability: 'animus:open-gui', arguments: {gui: 'shop:counter'}},
    {type: 'action', capability: 'animus:play-sound', arguments: {sound: 'minecraft:entity.villager.ambient'}},
    {type: 'action', capability: 'animus:give-item', arguments: {item: 'mymod:ruby'}},
  ]}},
  triggers: [{id: 'click', event: 'interaction'}],
};

/** The documents this check visits, as [kind, id]. */
const visits = [
  ['npc', 'names/dragon'], ['npc', 'names/knight'], ['npc', 'names/frame'], ['npc', 'names/painting'], ['npc', realShape.blankNpc],
  ['npc', realShape.talkerNpc], ['npc', realShape.guardNpc], ['hologram', 'names:blocks'], ['behavior', 'names:counter'],
  ['behavior', 'names:talk'], ['shop', 'shop:bakery'], ['shop', realShape.rawShop],
];

async function seed() {
  await control('reset', {});
  await realShape.addRealShape(fake);
  const edit = (kind, id, source) => control('edit', {kind, id, source});
  await edit('npc', 'names/dragon', npc('names/dragon', 'Wyrm', 'mymod:dragon'));
  await edit('npc', 'names/knight', npc('names/knight', 'Knight', 'minecraft:zombie', {equipment: {main_hand: {id: 'minecraft:diamond_sword', components: enchanted}}, behaviors: ['names:counter']}));
  await edit('npc', 'names/frame', npc('names/frame', 'Frame', 'minecraft:item_frame', {itemFrame: {item: {id: 'minecraft:iron_sword'}}}));
  await edit('npc', 'names/painting', npc('names/painting', 'Art', 'minecraft:painting', {painting: {variant: 'minecraft:kebab'}}));
  await edit('hologram', 'names:blocks', {schemaVersion: 3, revision: 1, anchor: {world: 'world', position: [8.5, 65, 8.5]},
    lines: ['&bSign', {block: 'minecraft:oak_log'}, {entity: 'minecraft:pig'}, {item: {type: 'item', item: 'minecraft:diamond_sword'}}]});
  await edit('behavior', 'names:counter', behavior);
  await edit('behavior', 'names:talk', conversation);
}

/** Ids and document ids the page shows. */
function scan(page, ids) {
  return page.evaluate(ids => {
    const pattern = /\b[a-z0-9_.-]+:[a-z0-9_./-]+/g;
    const found = [];
    const visible = element => element.offsetParent !== null || element.getClientRects().length > 0;
    const look = (where, text) => {
      if (!text) return;
      for (const match of text.match(pattern) || []) if (!/^(https?|wss?):/.test(match) && !/^\d+:\d+/.test(match)) found.push(`${where}: ${match}`);
      for (const id of ids) {
        const at = text.indexOf(id);
        if (at < 0) continue;
        const before = text[at - 1] || ' ', after = text[at + id.length] || ' ';
        if (!/[a-z0-9_/:.-]/i.test(before) && !/[a-z0-9_/-]/i.test(after)) found.push(`${where}: id ${id}`);
      }
    };
    const main = document.querySelector('#animus-main');
    look('text', main.innerText);
    for (const field of main.querySelectorAll('input, textarea')) {
      if (!visible(field)) continue;
      const name = field.getAttribute('aria-label') || field.id;
      look(`value of ${name}`, field.value);
      look(`placeholder of ${name}`, field.placeholder);
    }
    for (const titled of main.querySelectorAll('[title]')) if (visible(titled)) look('title', titled.getAttribute('title'));
    for (const tip of document.querySelectorAll('.animus-info-tip')) look('tip', tip.textContent);
    return found;
  }, ids);
}

(async () => {
  await seed();
  const state = await control('state');
  const ids = state.documents.map(document => document.id).filter(id => /[/:]/.test(id));
  const {code} = await control('pair?paths=both', {});
  const browser = await chromium.launch({headless: true});
  const context = await browser.newContext({viewport: {width: 1440, height: 1000}});
  await context.addInitScript(() => localStorage.setItem('animus.mode', 'simple'));
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', error => errors.push(String(error)));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  const found = new Map();
  const note = (where, list) => { for (const item of list) found.set(item, [...(found.get(item) || []), where]); };
  try {
    await page.goto(`${site}/#/pair/${code}`);
    await page.getByRole('button', {name: 'Pair', exact: true}).click();
    await page.waitForFunction(() => location.hash.startsWith('#/server/'));
    await page.waitForFunction(() => /Up to date/.test(document.querySelector('.ui-save-bar')?.innerText || ''), null, {timeout: 60000});
    const workspace = await page.evaluate(() => `server-${location.hash.split('/')[2]}`);
    const go = async hash => {
      await page.evaluate(next => { location.hash = next; }, hash);
      await page.waitForTimeout(1200);
    };
    for (const list of ['npcs', 'list/behavior', 'list/shop', 'list/hologram', 'list/route']) {
      await go(`#/workspace/${workspace}/${list}`);
      note(list, await scan(page, ids));
    }
    for (const [kind, id] of visits) {
      await go(`#/workspace/${workspace}/${kind}/${encodeURIComponent(id)}`);
      const tabs = await page.locator('#animus-main [role="tablist"]').first().locator('[role="tab"]').all();
      for (let index = 0; index < Math.max(1, tabs.length); index++) {
        if (tabs[index]) {
          await tabs[index].click();
          await page.waitForTimeout(400);
        }
        for (const summary of await page.locator('#animus-main details:not([open]) > summary').all()) await summary.click().catch(() => {});
        for (const offer of await page.locator('#animus-main li[data-offer]:not(.is-open) summary').all()) await offer.click().catch(() => {});
        await page.waitForTimeout(300);
        note(`${kind} ${id} tab ${index + 1}`, await scan(page, ids));
        for (const slot of await page.locator('#animus-main .animus-item-slot.is-filled').all()) {
          await slot.click().catch(() => {});
          await page.waitForTimeout(150);
          note(`${kind} ${id} slot`, await scan(page, ids));
        }
      }
    }
    // A command is typed on purpose and keeps its text.
    for (const key of [...found.keys()]) if (/^value of Command \d+:/.test(key)) found.delete(key);
    const report = [...found.entries()].map(([item, where]) => `${item} (${[...new Set(where)].slice(0, 3).join('; ')})`);
    assert.deepEqual(report, [], `Simple shows ids:\n${report.join('\n')}`);
    assert.deepEqual(errors, [], 'no console errors');
    console.log(`simple names: ok, ${visits.length} documents and 5 lists`);
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exit(1);
});
