// The in-game toolkit on a real server, driven through the native client's container clicks: the wand selects, the
// palette opens every tool, and each screen writes the file it says it writes. A text prompt is answered through the
// anvil's rename packet. Driven by live-features.mjs as the `toolkit` scenario.
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { delay } from '../protocol-client/client.mjs';

const plainName = text => String(text ?? '').replace(/§./g, '');

/** `t` carries live-features' server, join, check and data helpers. */
export async function toolkit(t) {
  const { server, join, check, data, OWNER } = t;
  const file = (...parts) => path.join(data, ...parts);
  const json = (...parts) => JSON.parse(readFileSync(file(...parts), 'utf8'));
  const npcFile = id => json('npcs', 'live', `${id}.json`);

  // Content the screens list: a trait group, a behavior and a shop.
  mkdirSync(file('groups'), { recursive: true });
  writeFileSync(file('groups', 'watch.json'), JSON.stringify({ schemaVersion: 2, revision: 1, id: 'watch', description: 'Gate watch', values: { tags: ['watch'] } }, null, 1) + '\n');
  mkdirSync(file('shops', 'live'), { recursive: true });
  writeFileSync(file('shops', 'live', 'stall.json'), JSON.stringify({ schemaVersion: 1, revision: 1, id: 'live:stall', title: '&bStall', modes: ['trade'], pages: [{ name: 'Main', rows: 3, trade: {} }] }, null, 1) + '\n');
  server.behavior('live:greet', { routines: { main: { type: 'action', capability: 'animus:say', arguments: { text: 'hello' } } }, triggers: [{ id: 'talk', event: 'interaction', action: 'interact' }] });
  await server.reload();
  await server.cmd('animus shop reload', /Reloaded \d+ shops/);
  await server.cmd('animus reload', /[Rr]eload/);

  const owner = await join(OWNER.name, 'survival');
  await owner.tp(0.5, 61, 1.5);
  const guardUuid = await server.npc('guard', 'minecraft:player', 0.5, 61, 3.5, 'Guard');
  const cartUuid = await server.npc('cart', 'minecraft:horse', 4.5, 61, 3.5, 'Cart');
  const guard = await owner.body(guardUuid);
  await owner.body(cartUuid);

  // ---- window helpers -------------------------------------------------------------------------
  let from = owner.events.length;
  /**
   * The window as the client last saw it: the newest open_screen, the newest full content for that container, and
   * every single-slot update since (an in-place redraw after a click sends only the slots that changed).
   */
  const shown = () => {
    const opened = owner.events.filter(e => e.event === 'open_screen').at(-1);
    assert(opened, 'no window has opened');
    const content = owner.events.filter(e => e.event === 'container_content' && e.container === opened.container && e.sequence > opened.sequence).at(-1);
    assert(content, `no contents for ${opened.title}`);
    const items = new Map(content.items.map(item => [item.slot, { ...item, name: plainName(item.name) }]));
    for (const update of owner.events.filter(e => e.event === 'container_slot' && e.container === opened.container && e.sequence > content.sequence)) {
      if (update.item === 'empty') items.delete(update.slot);
      else items.set(update.slot, { slot: update.slot, item: update.item, count: update.count, name: plainName(update.name) });
    }
    return { ...opened, items: [...items.values()].sort((a, b) => a.slot - b.slot) };
  };
  /**
   * Waits for the window to redraw after a click, open or close. A click that changes nothing on screen (a route point
   * dropped from the palette) sends no packet at all, so {@code optional} gives up quietly after the timeout.
   */
  const settle = async (timeout = 8000, optional = false) => {
    await owner.wait(e => (e.event === 'container_content' || e.event === 'open_screen' || e.event === 'container_slot') && e.sequence >= from, from, timeout)
      .catch(error => { if (!optional) throw error; });
    await delay(250);
    for (let guard = 0; guard < 20 && shown().items.some(item => item.name.startsWith('Reading ')); guard++) await delay(250);
    from = owner.events.length;
    return shown();
  };
  const slotOf = (window, name) => {
    const found = window.items.find(item => item.name.includes(name));
    assert(found, `no item named ${name} in ${window.title}: ${window.items.map(item => item.name).join(' | ')}`);
    return found.slot;
  };
  /** Clicks the button by name: button 0 is a left click, 1 a right click, sneak adds the shift mode. */
  const click = async (name, { right = false, sneak = false } = {}) => {
    const window = shown();
    const slot = slotOf(window, name);
    owner.send('click_container', { container: window.container, slot, button: right ? 1 : 0, mode: sneak ? 'quick_move' : 'pickup' });
    await owner.wait(e => e.event === 'container_click_sent' && e.slot === slot, from, 5000);
    return settle(2500, true);
  };
  const clickAway = async name => {
    const window = shown();
    const slot = slotOf(window, name);
    owner.send('click_container', { container: window.container, slot, button: 0, mode: 'pickup' });
    await owner.wait(e => e.event === 'container_closed' && e.container === window.container, from, 5000);
    await delay(250);
    from = owner.events.length;
  };
  const chatLine = (match, timeout = 10_000) => owner.chat(match, from - 200 < 0 ? 0 : from - 200, timeout);
  const openTools = async () => { from = owner.events.length; await owner.command('animus tools'); return settle(); };
  /** Answers the anvil prompt that a button opened: types the text and clicks the result slot. */
  const answer = async text => {
    const anvil = shown();
    assert.equal(anvil.menu, 'minecraft:anvil', `expected an anvil, saw ${anvil.menu} ${anvil.title}`);
    owner.send('rename_item', { text });
    await owner.wait(e => e.event === 'rename_item_sent', from, 5000);
    await delay(300);
    owner.send('click_container', { container: anvil.container, slot: 2, button: 0, mode: 'pickup' });
    await owner.wait(e => e.event === 'container_click_sent' && e.slot === 2, from, 5000);
    return settle();
  };

  // ---- the wand and the palette ---------------------------------------------------------------
  await owner.command('animus tools wand', /Wand given/);
  await check('toolkit: a wand click selects the NPC and the palette shows it', async () => {
    await owner.look(guard.x, guard.y + 0.9, guard.z);
    owner.send('use', { id: guard.id });
    await delay(400);
    const palette = await openTools();
    assert.equal(palette.title, 'Animus tools');
    slotOf(palette, '1 selected');
    for (const name of ['Animus wand', 'New NPC', 'Find', 'Manage', 'Edit', 'Folders', 'Trait groups', 'Skins', 'Equipment', 'Combat', 'Mounts', 'Routes', 'Recordings', 'Behaviors', 'Shops', 'GUIs', 'Close']) slotOf(palette, name);
    assert(!palette.items.some(item => item.item === 'minecraft:barrier' && item.name !== 'Close'), `a button failed to build: ${JSON.stringify(palette.items.filter(item => item.item === 'minecraft:barrier'))}`);
    return `${palette.items.length} items`;
  });

  await check('toolkit: trait group assigned from the list', async () => {
    const groups = await click('Trait groups');
    assert.equal(groups.title, 'Trait groups');
    await click('watch');
    await chatLine(/Assigned watch to guard; tx /);
    await delay(300);
    assert.deepEqual(npcFile('guard').groups, ['watch']);
    return 'guard has watch';
  });

  await check('toolkit: behavior attached from the list', async () => {
    await click('Back');
    const behaviors = await click('Behaviors');
    assert.equal(behaviors.title, 'Behaviors');
    await click('live:greet');
    await chatLine(/Attached live:greet to guard; tx /);
    await delay(300);
    assert.deepEqual(npcFile('guard').behaviors, ['live:greet']);
    return 'guard runs live:greet';
  });

  await check('toolkit: shop bound from the list', async () => {
    await click('Back');
    const shops = await click('Shops');
    assert.equal(shops.title, 'Shops');
    await click('live:stall');
    await chatLine(/live:stall/);
    await delay(300);
    assert.equal(npcFile('guard').modules['animus:shop'].shop, 'live:stall');
    return 'guard sells at live:stall';
  });

  await check('toolkit: combat enabled from its screen', async () => {
    await click('Back');
    const combat = await click('Combat');
    assert.equal(combat.title, 'Combat');
    await click('Enable combat');
    await chatLine(/; tx /);
    await delay(300);
    assert.equal(npcFile('guard').modules['animus:combat'].enabled, true);
    return 'enabled: true';
  });

  await check('toolkit: a field toggled in the Edit screen', async () => {
    await click('Back');
    const edit = await click('Edit');
    assert.match(edit.title, /Edit guard/);
    const body = await click('Body');
    slotOf(body, 'glowing');
    await click('glowing');
    await chatLine(/; tx /);
    await delay(300);
    assert.equal(npcFile('guard').glowing, true);
    await click('Back');
    await click('Back');
    return 'glowing: true';
  });

  await check('toolkit: equipment from the hand', async () => {
    await server.cmd(`clear ${OWNER.name}`, /Removed|No items/);
    await server.cmd(`give ${OWNER.name} minecraft:diamond_sword 1`, /Gave/);
    await delay(400);
    const equipment = await click('Equipment');
    assert.equal(equipment.title, 'Equipment');
    await click('Main hand');
    await chatLine(/; tx /);
    await delay(300);
    assert.equal(npcFile('guard').equipment.main_hand.id, 'minecraft:diamond_sword');
    await click('Back');
    await server.cmd(`clear ${OWNER.name}`, /Removed|No items/);
    await owner.command('animus tools wand', /Wand given/);
    return 'main_hand diamond_sword';
  });

  await check('toolkit: a route captured from the palette and put on the selection', async () => {
    await click('Routes', { right: true });
    await owner.tp(3.5, 61, 1.5);
    await openTools();
    await click('Routes', { right: true });
    const routes = await click('Routes');
    assert.equal(routes.title, 'Routes');
    await click('Save route', { right: true });
    const saved = await chatLine(/Saved wp-animusproofqa; /);
    assert(saved.actions?.some(action => action.includes('routes/wp-animusproofqa.json')), `no [Path] chip: ${JSON.stringify(saved).slice(0, 300)}`);
    await delay(500);
    assert(existsSync(file('routes', 'wp-animusproofqa.json')), 'route file written');
    assert.equal(json('routes', 'wp-animusproofqa.json').points.length, 2);
    assert.equal(npcFile('guard').modules['animus:navigation'].route, 'wp-animusproofqa');
    await click('Back');
    return '2 points, assigned';
  });

  await check('toolkit: the shop Window button steps chest, native, default and names the NPC', async () => {
    const shops = await click('Shops');
    assert.equal(shops.title, 'Shops');
    const detail = await click('live:stall', { sneak: true });
    const window = detail.items.find(item => item.name.startsWith('Window'));
    assert(window, `no Window button: ${detail.items.map(item => item.name).join(' | ')}`);
    await click('Window');
    await chatLine(/Window set to chest on guard; tx /);
    await click('Window');
    await chatLine(/Window set to native on guard; tx /);
    await click('Back');
    await click('Back');
    return 'chest then native';
  });

  await check('toolkit: navigation and combat replies read as sentences', async () => {
    const navigation = await owner.command('animus navigation status guard', /Navigation guard: /);
    assert(!/kinematic|revalidation|route [0-9a-f]{8}/.test(navigation.text), navigation.text);
    const combat = await owner.command('animus combat status npc=guard', /guard (is|:)/);
    assert(!/APPLIED|target=/.test(combat.text), combat.text);
    return `${navigation.text} / ${combat.text}`;
  });

  await check('toolkit: a click on an NPC without the wand does not select it', async () => {
    owner.send('close_container', { container: shown().container });
    await delay(300);
    await server.cmd(`clear ${OWNER.name}`, /Removed|No items/);
    await delay(300);
    const before = owner.events.length;
    const now = await owner.body(guardUuid);
    await owner.look(now.x, now.y + 0.9, now.z);
    owner.send('use', { id: now.id });
    await delay(1500);
    const selected = owner.events.slice(before).filter(e => e.event === 'chat' && /Selected /.test(e.text));
    assert.equal(selected.length, 0, selected.map(e => e.text).join(' | '));
    await owner.command('animus tools wand', /Wand given/);
    await openTools();
    return 'no selection line';
  });

  await check('toolkit: a skin from a player name through the anvil prompt', async () => {
    const skins = await click('Skins');
    assert.equal(skins.title, 'Skins');
    await click('From a player name');
    const back = await answer('Notch');
    assert.equal(back.title, 'Skins', 'the skins screen comes back after the prompt');
    await chatLine(/Looking up Notch on Mojang/);
    const line = await owner.chat(/Skin .*; tx |Mojang|refused|unknown|not found|no answer/i, from, 30_000);
    assert(/; tx /.test(line.text), line.text);
    await delay(500);
    const skin = npcFile('guard').skin;
    assert(skin, 'guard has a skin');
    assert.equal(json('skins', `${skin}.json`).label, 'Notch');
    await click('Back');
    return `skin ${skin.slice(0, 12)} labelled Notch`;
  });

  await check('toolkit: a cancelled prompt changes nothing and brings the window back', async () => {
    await click('New NPC');
    const anvil = shown();
    assert.equal(anvil.menu, 'minecraft:anvil');
    owner.send('close_container', { container: anvil.container });
    await owner.wait(e => e.event === 'container_close_sent', from, 5000);
    const palette = await settle();
    assert.equal(palette.title, 'Animus tools');
    await chatLine(/Cancelled; nothing changed/);
    assert(!existsSync(file('npcs', 'miller.json')));
    return 'cancelled';
  });

  await check('toolkit: a new NPC from a typed id, saved, selected and spawned', async () => {
    await click('New NPC');
    const palette = await answer('miller');
    assert.equal(palette.title, 'Animus tools');
    await chatLine(/Created npcs\/miller\.json and spawned it; tx /);
    await delay(500);
    const miller = json('npcs', 'miller.json');
    assert.equal(miller.type, 'minecraft:player');
    assert.equal(miller.spawned, true);
    await delay(500);
    slotOf(shown(), '1 selected');
    return `miller at ${miller.position.x}, ${miller.position.y}, ${miller.position.z}`;
  });

  await check('toolkit: a mount pair by clicking the vehicle with the wand', async () => {
    await server.cmd(`clear ${OWNER.name}`, /Removed|No items/);
    await owner.command('animus tools wand', /Wand given/);
    await owner.tp(4.5, 61, 1.5);
    // A teleport presents every body again under new entity ids.
    const cartNow = await owner.body(cartUuid);
    await openTools();
    const mounts = await click('Mounts');
    assert.equal(mounts.title, 'Mounts');
    await clickAway('Ride another NPC');
    await owner.look(cartNow.x, cartNow.y + 0.9, cartNow.z);
    owner.send('use', { id: cartNow.id });
    const line = await chatLine(/ rides cart; tx /);
    const rider = line.text.match(/: (\S+) rides cart/)[1];
    await delay(300);
    const riderFile = existsSync(file('npcs', `${rider}.json`)) ? json('npcs', `${rider}.json`) : npcFile(rider);
    assert.equal(riderFile.modules['animus:mount'].vehicle, 'cart');
    assert.equal((await openTools()).title, 'Animus tools', 'the window stays closed after the pick, so the tools open again');
    return `${rider} rides cart`;
  });

  await check('toolkit: recordings and GUIs open and come back', async () => {
    const recordings = await click('Recordings');
    assert.equal(recordings.title, 'Recordings');
    await click('Back');
    const guis = await click('GUIs');
    assert.match(guis.title, /GUI/);
    await click('Back');
    const window = shown();
    assert.equal(window.title, 'Animus tools');
    return 'both';
  });

  await check('toolkit: manage deletes after a second click and Undo is offered', async () => {
    const manage = await click('Manage');
    assert.equal(manage.title, 'Manage selection');
    const armed = await click('Delete');
    slotOf(armed, 'Click again to delete');
    await click('Click again to delete');
    const line = await chatLine(/Deleted miller; tx /);
    assert(line.actions?.some(action => /^suggest \/animus rollback /.test(action)), `no Undo chip: ${JSON.stringify(line).slice(0, 300)}`);
    await delay(300);
    assert(!existsSync(file('npcs', 'miller.json')));
    return 'deleted with [Undo]';
  });

  owner.send('close_container', { container: shown().container });
  await delay(300);
}
