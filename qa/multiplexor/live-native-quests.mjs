// Animus's own quests as a player meets them: a giver marked with ! offers a quest with chat buttons, the player
// accepts, talks to a farmer, brings wheat, kills a husk and a killable bandit NPC, turns the quest in, gets the
// rewards and a server behavior hears the completion. The journal lists it as finished, and a restart keeps it.
// Driven by live-features.mjs on Paper or Folia; positions follow the player's spawn so Folia's own terrain works.
import assert from 'node:assert/strict';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { delay } from '../protocol-client/client.mjs';

const QUEST = 'live:rescue';
// Mod servers count no NPC kill objective, so there the second stage has no bandit.
const quest = (healer, farmer, bandit, mod) => ({
  schemaVersion: 1, revision: 1, id: QUEST, title: '&aRescue the farm',
  description: 'The healer needs wheat, and a husk and a bandit roam the fields.',
  icon: { id: 'minecraft:wheat' }, givers: [healer], turnIn: healer,
  stages: [
    { description: 'Ask the farmer what happened.', objectives: [{ type: 'talk', npc: farmer }] },
    { description: 'Bring wheat and clear the fields.', objectives: [
      { type: 'bring', npc: healer, item: { id: 'minecraft:wheat' }, amount: 3 },
      { type: 'kill', entity: 'minecraft:husk' },
      ...(mod ? [] : [{ type: 'kill', npc: bandit }]),
    ] },
  ],
  rewards: { items: [{ item: { id: 'minecraft:bread' }, amount: 5 }], experience: 30, flags: [{ name: 'rescued' }] },
  messages: { offer: 'The farm needs you.', complete: 'Thank you, {player}.' },
});

export async function nativeQuests(t) {
  const { server, join, check, data, results, mod, OWNER } = t;
  const transcript = [];
  const note = text => { transcript.push(text); return text; };
  let owner = await join(OWNER.name, 'survival');
  await server.cmd(`op ${OWNER.name}`, /Made|already/);
  // The player file outlives a run, so the inventory and experience start empty here.
  await server.cmd(`clear ${OWNER.name}`, /Removed|No items/);
  await server.cmd(`xp set ${OWNER.name} 0 levels`, /Set|experience/i);
  await server.cmd(`xp set ${OWNER.name} 0 points`, /Set|experience/i);
  const base = { x: Math.floor(owner.position.x) + 0.5, y: Math.round(owner.position.y), z: Math.floor(owner.position.z) + 0.5 };
  const at = (dx, dz) => [base.x + dx, base.y, base.z + dz];
  const killable = { modules: { 'animus:combat': { enabled: true }, 'animus:health': { killable: true, maximumHealth: 1 } } };
  const npc = async (id, display, dx, dz, edit = {}) => {
    const [x, y, z] = at(dx, dz);
    await server.cmd(`animus create ${id} type=minecraft:player world=world x=${x} y=${y} z=${z} folder=live name=${display}`, new RegExp(`Created (?:dormant )?${id}`));
    if (Object.keys(edit).length) await server.cmd(`animus edit ${id} ${JSON.stringify(edit)}`, /Saved[^;]*; tx /);
    await server.cmd(`animus spawn ${id}`, new RegExp(`Spawning ${id}|${id}[^\\n]*(?:already|unchanged|no change)`, 'i'));
    return JSON.parse(readFileSync(path.join(data, 'npcs/live', `${id}.json`), 'utf8')).uuid;
  };
  const ids = { healer: await npc('qhealer', 'Healer', 3, 5), farmer: await npc('qfarmer', 'Farmer', -3, 5), bandit: await npc('qbandit', 'Bandit', 0, 9, killable) };

  await check('native quests: a quest file and a server behavior load', async () => {
    mkdirSync(path.join(data, 'quests/live'), { recursive: true });
    writeFileSync(path.join(data, 'quests/live/rescue.json'), JSON.stringify(quest('qhealer', 'qfarmer', 'qbandit', mod), null, 1) + '\n');
    const loaded = await server.cmd('animus quest reload', /Reloaded (\d+) quests; (\d+) problems/);
    assert.equal(loaded[1], '1', loaded[0]);
    assert.equal(loaded[2], '0', loaded[0]);
    server.behavior('live:rescue-done', {
      server: true,
      routines: { main: { type: 'action', capability: 'animus:message', arguments: { text: 'Behavior heard the rescue' } } },
      triggers: [{ id: 'done', event: 'quest_completed', quest: QUEST }],
    });
    writeFileSync(path.join(data, 'server-behaviors.json'), JSON.stringify({ schemaVersion: 1, behaviors: ['live:rescue-done'] }) + '\n');
    await server.reload();
    return loaded[0];
  });

  // Each click stands two blocks in front of the NPC, inside both use and attack reach.
  const use = async (uuid, op = 'use') => {
    let body = await owner.body(uuid);
    if (Math.hypot(owner.position.x - body.x, owner.position.z - body.z) > 2.5) {
      await owner.tp(body.x, body.y, body.z - 2);
      await delay(500);
      body = await owner.body(uuid);
    }
    await owner.look(body.x, body.y + 1.4, body.z);
    const from = owner.events.length;
    owner.send(op, { id: body.id });
    return from;
  };
  const chat = async (pattern, from, timeout = 10_000) => note((await owner.chat(pattern, from, timeout)).text);
  // Paper sends the action bar as an overlay system chat line, which the client reports as chat.
  const bar = async (pattern, from, timeout = 10_000) => note((await owner.wait(e => (e.event === 'action_bar' || e.event === 'chat') && pattern.test(e.text), from, timeout)).text);
  // How many of an item the player's own inventory holds, as the client sees it. Folia's /clear counts stacks, not items.
  const carried = item => {
    const slots = new Map();
    for (const e of owner.events) {
      if (e.event === 'container_content' && e.container === 0) { slots.clear(); for (const stack of e.items ?? []) slots.set(stack.slot, stack); }
      else if (e.event === 'container_slot' && e.container === 0) slots.set(e.slot, { slot: e.slot, item: e.item, count: e.count });
    }
    return [...slots.values()].filter(stack => stack.item === item).reduce((sum, stack) => sum + stack.count, 0);
  };
  const holds = async (item, count) => {
    for (let tries = 0; tries < 50 && carried(item) !== count; tries++) await delay(100);
    assert.equal(carried(item), count, `${item} in the inventory`);
    return `${count} ${item}`;
  };
  const marker = (text, from) => owner.wait(e => e.event === 'metadata' && (e.fields ?? []).some(field => field.text === text), from, 10_000);

  await check('native quests: the giver carries a ! marker and offers the quest with buttons', async () => {
    const from = owner.events.length;
    await owner.tp(...at(0, 2));
    await marker('!', 0).catch(() => marker('!', from));
    const clicked = await use(ids.healer);
    await chat(/The farm needs you\./, clicked);
    const offer = await owner.wait(e => e.event === 'chat' && (e.clicks ?? []).some(command => command.includes(' quest choice ')), clicked);
    const accept = offer.clicks.find(command => command.endsWith(' accept'));
    assert(accept, JSON.stringify(offer.clicks));
    note(offer.text);
    const accepted = owner.events.length;
    owner.send('command', { text: accept.replace(/^\//, '') });
    return `${await chat(/Started/, accepted)} | ${await chat(/Talk to/, accepted)}`;
  });

  await check('native quests: talking to the farmer opens stage 2', async () => {
    const from = await use(ids.farmer);
    return `${await chat(/stage 2\/2/, from)} | ${await chat(/Bring 3 wheat/, from)}`;
  });

  await check('native quests: the healer takes the wheat', async () => {
    await server.cmd(`give ${OWNER.name} minecraft:wheat 5`, /Gave/);
    await delay(300);
    const from = await use(ids.healer);
    const line = await bar(/Bring 3 wheat/, from);
    return `${line} | ${await holds('minecraft:wheat', 2)} left`;
  });

  // A husk, unlike a zombie, does not burn to death in the daylight before the player can hit it.
  await check('native quests: killing a husk counts', async () => {
    const [x, y, z] = at(-4, 0);
    // Peaceful removes hostile mobs at once; the husk needs a difficulty that keeps it.
    await server.cmd('difficulty easy', /difficulty/i);
    const from = owner.events.length;
    await owner.command(`summon minecraft:husk ${x} ${y} ${z} {NoAI:1b,Health:1f,Silent:1b,Tags:["questqa"]}`, /Summoned/);
    const husk = await owner.wait(e => e.event === 'spawn' && e.type === 'minecraft:husk', from);
    await owner.tp(x, y, z - 2);
    let line = null;
    for (let hit = 0; hit < 6 && !line; hit++) {
      await owner.look(x, y + 1.2, z);
      const swing = owner.events.length;
      owner.send('attack', { id: husk.id });
      line = await bar(/Defeat 1 husk/, swing, 1500).catch(() => null);
    }
    await server.cmd('difficulty peaceful', /difficulty/i);
    assert(line, 'the husk kill was not counted');
    return line;
  });

  if (!mod) await check('native quests: defeating the bandit NPC readies the turn-in', async () => {
    const from = owner.events.length;
    let line = null;
    for (let hit = 0; hit < 8 && !line; hit++) {
      const swing = await use(ids.bandit, 'attack');
      line = await bar(/Defeat Bandit/, swing, 1200).catch(() => null);
    }
    assert(line, 'the bandit kill was not counted');
    return `${line} | ${await chat(/return to/, from)}`;
  });

  await check('native quests: turning in pays the rewards and a server behavior hears it', async () => {
    const from = await use(ids.healer);
    const done = await chat(/Completed/, from);
    const thanks = await chat(/Thank you, AnimusProofQA\./, from);
    const heard = await chat(/Behavior heard the rescue/, from, 15_000);
    const bread = await holds('minecraft:bread', 5);
    const levels = await server.cmd(`xp query ${OWNER.name} levels`, /has (\d+) experience levels/);
    assert(Number(levels[1]) >= 1, levels[0]);
    return `${done} | ${thanks} | ${heard} | ${bread} | ${levels[0]}`;
  });

  // The open window's slots as the client holds them: a full content packet replaces them, a slot packet changes one.
  const slots = from => {
    const shown = new Map();
    for (const e of owner.events.slice(from)) {
      if (e.event === 'container_content') { shown.clear(); for (const item of e.items ?? []) shown.set(item.slot, item); }
      else if (e.event === 'container_slot') { if (e.item === 'empty') shown.delete(e.slot); else shown.set(e.slot, { slot: e.slot, item: e.item, name: e.name }); }
    }
    return shown;
  };
  const journal = async () => {
    const from = owner.events.length;
    // Mod servers open the journal with /animus quest journal.
    await owner.command(mod ? 'animus quest journal' : 'quests');
    await owner.wait(e => e.event === 'open_screen' && /Quests/.test(e.title), from);
    await owner.wait(e => e.event === 'container_content', from);
    owner.send('click_container', { slot: 51 });
    // The selected tab shows an enchanted book.
    for (let tries = 0; tries < 40 && slots(from).get(51)?.item !== 'minecraft:enchanted_book'; tries++) await delay(100);
    const shown = slots(from);
    owner.send('close_container');
    assert.equal(shown.get(51)?.item, 'minecraft:enchanted_book', 'the Finished tab did not open');
    const listed = [...shown.values()].filter(item => item.slot < 45).map(item => item.name);
    assert(listed.includes('Rescue the farm'), `finished tab lists ${listed.join(', ') || 'nothing'}`);
    return listed.join(', ');
  };

  await check('native quests: the journal lists the quest as finished', async () => note(`finished tab: ${await journal()}`));

  await check('native quests: progress, the stage value and the journal survive a restart', async () => {
    await owner.close();
    await delay(300);
    await server.stop();
    await server.start();
    owner = await join(OWNER.name, 'survival');
    const progress = await server.capture(`animus quest progress ${OWNER.name}`, /live:rescue \| finished 1x/);
    const line = progress.match(/live:rescue \| finished 1x[^\n]*/)[0];
    const finished = await journal();
    const from = await use(ids.healer);
    await delay(1500);
    assert(!owner.has(e => e.event === 'chat' && (e.clicks ?? []).some(command => command.includes(' quest choice ')), from), 'a one-time quest was offered again');
    return `${line} | finished tab after restart: ${finished}`;
  });

  writeFileSync(path.join(results, 'native-quests-chat.txt'), transcript.join('\n') + '\n');
  await owner.close();
  for (const id of ['qhealer', 'qfarmer', 'qbandit']) await server.cmd(`animus delete ${id}`, /Deleted|Unknown/);
  await server.cmd('kill @e[type=minecraft:husk,tag=questqa]', /Killed|No entity/).catch(() => {});
}
