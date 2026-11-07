// Every interaction action on a real server: the four clicks and the drop and swap-hands keys, alone and while
// sneaking, the vanilla drop and swap cancelled only when a trigger takes them, spam starting a dialogue once, and
// clicks that miss the NPC or come from too far firing nothing. Driven by live-features.mjs.
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { delay } from '../protocol-client/client.mjs';

const ACTIONS = ['interact', 'attack', 'sneak_interact', 'sneak_attack', 'drop', 'sneak_drop', 'swap_hands', 'sneak_swap_hands'];

/** `t` carries live-features' server, join, check and behavior helpers. */
export async function npcKeys(t) {
  const { server, join, check, sayTo, data, OWNER } = t;
  const routines = { main: sayTo('keys-main') };
  for (const action of ACTIONS) routines[action] = sayTo(`key-${action}`);
  server.behavior('live:keys', {
    routines,
    triggers: ACTIONS.map(action => ({ id: action.replaceAll('_', '-'), event: 'interaction', action, routine: action, playerCooldownTicks: 0 })),
  });
  server.behavior('live:story', {
    routines: { main: { type: 'sequence', steps: [sayTo('story-start'), { type: 'wait', ticks: 60 }, sayTo('story-end')] } },
    triggers: [{ id: 'talk', event: 'interaction', action: 'sneak_interact' }],
  });
  await server.reload();
  const owner = await join(OWNER.name, 'survival');
  await owner.tp(0.5, 61, -2.5);
  const keysNpc = await server.npc('keys', 'minecraft:player', 0.5, 61, 0.5, 'Keys', { behaviors: ['live:keys'] });
  const storyNpc = await server.npc('story', 'minecraft:player', 6.5, 61, 0.5, 'Story', { behaviors: ['live:story'] });
  const keys = await owner.body(keysNpc);
  // A teleport presents every body again under new entity ids, so the story body is read after each one.
  let story = await owner.body(storyNpc);
  const moveTo = async (x, z) => { const from = owner.events.length; await owner.tp(x, 61, z); await owner.wait(e => e.event === "spawn" && e.uuid === storyNpc, from, 5000).catch(() => {}); story = await owner.body(storyNpc); };
  const sneak = async on => { await owner.keys(on ? { shift: true } : {}); await delay(200); };
  const fired = async (send, expected) => {
    const from = owner.events.length;
    await send();
    for (const action of expected) await owner.chat(`Keys » key-${action}`, from, 5000);
    await delay(600);
    const all = owner.events.slice(from).filter(e => e.event === 'chat' && /Keys » key-/.test(e.text)).map(e => e.text.replace(/.*key-/, ''));
    assert.deepEqual([...all].sort(), [...expected].sort(), `fired ${all.join(', ')}`);
    return all.join(', ');
  };
  const holding = async slot => /Test passed/.test((await server.cmd(`execute if items entity ${OWNER.name} weapon.${slot} minecraft:dirt`, /Test (passed|failed)[^\n]*/))[0]);
  const reset = async () => {
    await server.cmd(`clear ${OWNER.name}`, /Removed|No items/);
    await server.cmd('kill @e[type=minecraft:item]', /Killed|No entity/);
    await server.cmd(`give ${OWNER.name} minecraft:dirt 4`, /Gave/);
    await delay(300);
  };
  const atKeys = () => owner.look(keys.x, keys.y + 0.9, keys.z);
  const away = () => owner.look(keys.x + 6, keys.y + 0.9, keys.z - 2);

  await atKeys();
  await check('keys: right and left clicks fire as before', async () => [
    await fired(() => owner.send('use', { id: keys.id }), ['interact']),
    await fired(() => owner.send('attack', { id: keys.id }), ['attack']),
  ].join(' / '));
  await check('keys: sneaking clicks fire the sneak actions too', async () => {
    await sneak(true);
    try {
      return [
        await fired(() => owner.send('use', { id: keys.id, sneak: true }), ['interact', 'sneak_interact']),
        await fired(() => owner.send('attack', { id: keys.id }), ['attack', 'sneak_attack']),
      ].join(' / ');
    } finally { await sneak(false); }
  });
  await check('keys: drop at the NPC fires and keeps the item', async () => {
    await reset();
    const from = owner.events.length;
    const result = await fired(() => owner.send('drop', {}), ['drop']);
    assert(await holding('mainhand'), 'the dirt left the hand');
    assert(!owner.has(e => e.event === 'spawn' && e.type === 'minecraft:item', from), 'an item entity appeared');
    return result;
  });
  await check('keys: sneak drop fires both drop actions and keeps the item', async () => {
    await reset();
    await sneak(true);
    try {
      const result = await fired(() => owner.send('drop', {}), ['drop', 'sneak_drop']);
      assert(await holding('mainhand'), 'the dirt left the hand');
      return result;
    } finally { await sneak(false); }
  });
  await check('keys: swap at the NPC fires and keeps the hands', async () => {
    await reset();
    const result = await fired(() => owner.send('swap', {}), ['swap_hands']);
    assert(await holding('mainhand') && !(await holding('offhand')), 'the hands were swapped');
    return result;
  });
  await check('keys: sneak swap fires both swap actions and keeps the hands', async () => {
    await reset();
    await sneak(true);
    try {
      const result = await fired(() => owner.send('swap', {}), ['swap_hands', 'sneak_swap_hands']);
      assert(await holding('mainhand') && !(await holding('offhand')), 'the hands were swapped');
      return result;
    } finally { await sneak(false); }
  });
  await check('keys: drop and swap looking away happen as usual and fire nothing', async () => {
    await reset();
    await away();
    const from = owner.events.length;
    owner.send('drop', {});
    await owner.wait(e => e.event === 'spawn' && e.type === 'minecraft:item', from, 5000);
    owner.send('swap', {});
    await delay(800);
    assert(await holding('offhand'), 'the swap did not happen');
    await owner.noChat(/Keys » key-/, from, 200);
    return 'item dropped, hands swapped';
  });
  await check('keys: a click aimed beside the NPC fires nothing', async () => {
    await away();
    const from = owner.events.length;
    await sneak(true);
    try { owner.send('use', { id: keys.id, sneak: true }); owner.send('attack', { id: keys.id }); } finally { await sneak(false); }
    await owner.noChat(/Keys » key-/, from, 1200);
    return 'refused';
  });

  await moveTo(6.5, -1.5);
  await owner.look(story.x, story.y + 0.9, story.z);
  await check('story: ten sneaking clicks in two seconds start it once', async () => {
    const from = owner.events.length;
    await sneak(true);
    try {
      for (let i = 0; i < 10; i++) { owner.send('use', { id: story.id, sneak: true }); await delay(200); }
    } finally { await sneak(false); }
    await owner.chat('Story » story-end', from, 8000).catch(async error => {
      const rows = await server.capture('animus behaviors triggers', /Behavior triggers:[^\n]*/);
      throw new Error(`${error.message}; ${rows.split('\n').filter(line => /story|Behavior triggers|refus|START/i.test(line)).join(' | ').slice(0, 900)}`);
    });
    await delay(500);
    const starts = owner.events.slice(from).filter(e => e.event === 'chat' && e.text.includes('story-start')).length;
    assert.equal(starts, 1, `${starts} starts`);
    return '1 start for 10 clicks';
  });
  await check('story: a plain right click and a sneaking click on air beside it do nothing', async () => {
    await delay(1200);
    const from = owner.events.length;
    owner.send('use', { id: story.id });
    await delay(300);
    await owner.look(story.x + 3, story.y + 0.9, story.z - 2);
    await sneak(true);
    try { owner.send('use', { id: story.id, sneak: true }); } finally { await sneak(false); }
    await owner.noChat('story-start', from, 1500);
    return 'nothing started';
  });
  await check('story: a sneaking click from 6.5 blocks is refused', async () => {
    await moveTo(6.5, -6);
    await owner.look(story.x, story.y + 0.9, story.z);
    const from = owner.events.length;
    await sneak(true);
    try { owner.send('use', { id: story.id, sneak: true }); } finally { await sneak(false); }
    await owner.noChat('story-start', from, 1500);
    await moveTo(6.5, -1.5);
    await owner.look(story.x, story.y + 0.9, story.z);
    const near = owner.events.length;
    await sneak(true);
    try { owner.send('use', { id: story.id, sneak: true }); } finally { await sneak(false); }
    await owner.chat('Story » story-start', near, 5000);
    return 'refused at 6.5 blocks, accepted at 2';
  });
  await check('shop: a plain right click opens the animus:shop window', async () => {
    const file = path.join(data, 'shops', 'live', 'keyshop.json');
    mkdirSync(path.dirname(file), { recursive: true });
    writeFileSync(file, JSON.stringify({
      schemaVersion: 1, revision: 1, id: 'live:keyshop', title: '&bKeys', modes: ['trade'],
      pages: [{ name: 'Keys', rows: 3, trade: { 11: { id: 'dirt', type: 'trade', item: { id: 'minecraft:dirt' },
        needs: [{ item: { id: 'minecraft:emerald' }, amount: 1 }], gives: [{ item: { id: 'minecraft:dirt' }, amount: 1 }] } } }],
    }, null, 1) + '\n');
    await server.cmd('animus shop reload', /Reloaded \d+ shops/);
    await moveTo(12.5, -1.5);
    const shopNpc = await server.npc('keyshop', 'minecraft:player', 12.5, 61, 0.5, 'Shopkeeper', { modules: { 'animus:shop': { shop: 'live:keyshop', openOn: 'right' } } });
    const shop = await owner.body(shopNpc);
    await owner.look(shop.x, shop.y + 1.4, shop.z);
    const from = owner.events.length;
    owner.send('use', { id: shop.id });
    const opened = await owner.wait(e => e.event === 'open_screen', from, 8000);
    await server.cmd('animus delete keyshop', /Deleted/);
    return `${opened.menu} opened`;
  });
  await owner.close();
  await server.cmd('animus delete keys', /Deleted/);
  await server.cmd('animus delete story', /Deleted/);
}
