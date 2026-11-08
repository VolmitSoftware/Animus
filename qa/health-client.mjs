#!/usr/bin/env node
// Killable NPCs on a live server: a real player hits an animus:health NPC, sees the hurt and death packets, picks up
// its drops, watches it respawn at a point, and a permadeath NPC stays dead across a restart until it is revived.
// Run --phase kill, restart the server, then --phase restart with the same --id and --data. The player must be an operator.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { NativeClient, prepareNative, parseArgs, delay } from './protocol-client/client.mjs';

const args = parseArgs();
const phase = args.get('phase');
assert(phase === 'kill' || phase === 'restart', 'Use --phase kill, then --phase restart after a server restart');
const harness = prepareNative(args, import.meta.url, `health-${phase}`);
const { version, port, resultDir, nativeCodec, clients, wire } = harness;
const id = args.get('id') ?? `health${Date.now().toString(36)}`;
assert(typeof id === 'string' && /^health[a-z0-9]+$/.test(id), 'Use an isolated synthetic health ID');
const data = args.get('data');
assert(typeof data === 'string', 'Name the server\'s Animus data folder with --data; its npcs/healthqa/ files are edited by hand');
const horse = `${id}-horse`, ghost = `${id}-ghost`;
const checks = [];
const passed = (name, detail = {}) => { checks.push({ name, ...detail }); console.log(JSON.stringify({ event: 'check', status: 'passed', name, ...detail })); };
let player, failure;

/** The stored definition, read from its file. */
async function inspect(npc) { return JSON.parse(readFileSync(path.join(data, 'npcs', 'healthqa', `${npc}.json`), 'utf8')); }
async function create(npc, type, x, y, z, health) {
  await player.command(`animus create ${npc} type=${type} world=world x=${x} y=${y} z=${z} folder=healthqa`, `Created dormant ${npc}`);
  // A chat command holds 256 characters, so the module goes in as a hand edit of the definition file, which applies live.
  const file = path.join(data, 'npcs', 'healthqa', `${npc}.json`);
  const stored = JSON.parse(readFileSync(file, 'utf8'));
  stored.modules = { ...(stored.modules ?? {}), 'animus:health': health };
  stored.revision += 1;
  writeFileSync(file, JSON.stringify(stored, null, 2) + '\n');
  const edited = player.events.length;
  await delay(2000);
  const refused = player.events.slice(edited).find(e => e.event === 'chat' && e.text.includes('Hotload refused'));
  assert(!refused, refused?.text);
  const document = await inspect(npc);
  const from = player.events.length;
  await player.command(`animus spawn ${npc}`, `Spawning ${npc}`);
  const shown = await player.wait(e => e.event === 'spawn' && e.uuid === document.uuid, from, 20_000);
  return { uuid: document.uuid, entity: shown.id, x: shown.x, y: shown.y, z: shown.z };
}
/** Swings at the body once a second, as a charged punch, until the test passes. */
async function hitUntil(body, test, limit = 20) {
  const from = player.events.length;
  for (let i = 0; i < limit; i++) {
    // Knockback moves the body; aim at where the client last saw it.
    const moved = player.events.filter(e => e.event === 'move' && e.id === body.entity).at(-1) ?? body;
    player.send('look', { x: moved.x, y: moved.y + 1, z: moved.z });
    await delay(50);
    // A vanilla client attacks, then swings; swinging first would spend the attack charge.
    player.send('attack', { id: body.entity });
    player.send('swing');
    await delay(1000);
    const found = player.events.slice(from).find(test);
    if (found) return found;
  }
  throw new Error(`No ${test} after ${limit} hits`);
}

try {
  player = new NativeClient(harness, 'HealthQA');
  // The exact-version client bootstraps Minecraft first, which takes longer than ready() waits on a busy machine.
  await player.wait(e => e.event === 'toolchain', 0, 180_000);
  await player.wait(e => e.event === 'position', 0, 60_000);
  await player.ready();
  const start = player.position;
  const ground = { x: Math.floor(start.x) + 0.5, y: start.y, z: Math.floor(start.z) + 0.5 };
  if (phase === 'kill') {
    await player.command('gamemode survival @s');
    await player.command('clear @s');
    await player.command('give @s minecraft:iron_sword');
    const target = await create(horse, 'minecraft:horse', ground.x + 2, ground.y, ground.z, {
      killable: true, maximumHealth: 3, invulnerableTicks: 0, showHealth: true,
      death: { drops: [{ item: { id: 'minecraft:emerald', count: 2 } }, { item: { id: 'minecraft:diamond' }, chance: 0 }], experience: 3 },
      respawn: { delayTicks: 40, points: [{ world: 'world', x: ground.x + 6, y: ground.y, z: ground.z }] }
    });
    passed('a killable horse is presented', target);

    const hurt = await hitUntil(target, e => e.event === 'damage_event' && e.id === target.entity, 3);
    passed('a hit sends the damage event: the client flashes it red and plays the horse hurt sound', { packet: hurt });
    const bar = await player.wait(e => (e.event === 'action_bar' || e.event === 'chat') && / [0-9.]+\/3$/.test(e.text), 0);
    passed('the attacker sees its health above the hotbar', { text: bar.text });

    const fall = await hitUntil(target, e => e.event === 'entity_event' && e.id === target.entity && e.status === 3, 10);
    const lying = await player.wait(e => e.event === 'metadata' && e.id === target.entity && e.fields.some(f => f.value === 0), fall.sequence - 1, 2_000);
    passed('a death sends entity event 3 and zero health: the body falls and plays its death sound', { event: fall, metadata: lying });

    const drops = await player.wait(e => e.event === 'spawn' && e.type === 'minecraft:item' && Math.abs(e.x - target.x) < 2, fall.sequence, 5_000);
    const orb = await player.wait(e => e.event === 'spawn' && e.type === 'minecraft:experience_orb', fall.sequence, 5_000);
    const stack = await player.wait(e => e.event === 'metadata' && e.id === drops.id && e.fields.some(f => f.item === 'minecraft:emerald'), fall.sequence, 5_000);
    assert(!player.events.some(e => e.event === 'metadata' && e.fields?.some(f => f.item === 'minecraft:diamond')), 'a zero chance never drops');
    passed('the configured drops and experience fall where it died', { item: drops, stack, orb });
    const gone = await player.wait(e => e.event === 'remove' && e.ids.includes(target.entity), fall.sequence, 5_000);
    passed('the body is removed after its fall', { afterSequence: gone.sequence });

    const back = await player.wait(e => e.event === 'spawn' && e.uuid === target.uuid, gone.sequence, 10_000);
    assert(Math.abs(back.x - (ground.x + 6)) < 0.01, `respawned at ${back.x}`);
    passed('it respawns at its respawn point after the delay', { x: back.x, y: back.y, z: back.z });

    const doomed = await create(ghost, 'minecraft:player', ground.x - 2, ground.y, ground.z, {
      killable: true, maximumHealth: 1, invulnerableTicks: 0, respawn: { afterDeath: 'permadeath' }
    });
    await hitUntil(doomed, e => e.event === 'entity_event' && e.id === doomed.entity && e.status === 3, 5);
    await player.wait(e => e.event === 'remove' && e.ids.includes(doomed.entity), 0, 5_000);
    let marked = false;
    for (let i = 0; i < 20 && !marked; i++) { marked = (await inspect(ghost)).modules?.['animus:health']?.dead === true; if (!marked) await delay(250); }
    assert(marked, 'permadeath is written to the definition');
    passed('a permadeath NPC is marked dead in its definition', { uuid: doomed.uuid });
  } else {
    const document = await inspect(ghost);
    assert.equal(document.modules['animus:health'].dead, true);
    await delay(3000);
    assert(!player.events.some(e => e.event === 'spawn' && e.uuid === document.uuid), 'a dead NPC is not presented after a restart');
    passed('a permadeath NPC stays dead across the restart', { uuid: document.uuid });
    const from = player.events.length;
    await player.command(`animus npc revive ${ghost}`, `${ghost} revived`);
    await player.wait(e => e.event === 'spawn' && e.uuid === document.uuid, from, 20_000);
    assert.equal((await inspect(ghost)).modules['animus:health']?.dead, undefined);
    passed('revive clears the mark and brings it back', { uuid: document.uuid });
    for (const npc of [horse, ghost]) await player.command(`animus delete ${npc}`, /Deleted [^;]*; tx /);
    passed('the QA NPCs are deleted');
  }
} catch (error) {
  failure = error;
} finally {
  for (const client of clients) await client.close();
  wire.end();
}
const result = { event: 'result', status: failure ? 'failed' : 'passed', phase, id, version, port, checks, nativeCodec, evidence: resultDir,
  ...(failure ? { error: String(failure.stack ?? failure.message ?? failure) } : {}) };
writeFileSync(path.join(resultDir, 'result.json'), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result));
if (failure) process.exitCode = 1;
