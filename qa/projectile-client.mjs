#!/usr/bin/env node
// Real projectiles and lava against killable NPCs on a live server: a survival player shoots an animus:health NPC with a
// bow and sees the hurt feedback and its health drop, a killable NPC standing in lava burns and takes damage, and a bow
// kill counts on the shooter's mob kill statistic. The player must be an operator; --data names the Animus data folder.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { NativeClient, prepareNative, parseArgs, delay } from './protocol-client/client.mjs';

const args = parseArgs();
const harness = prepareNative(args, import.meta.url, 'projectile');
const { version, port, resultDir, nativeCodec, clients, wire } = harness;
const id = args.get('id') ?? `shot${Date.now().toString(36)}`;
assert(typeof id === 'string' && /^shot[a-z0-9]+$/.test(id), 'Use an isolated synthetic projectile ID');
const data = args.get('data');
assert(typeof data === 'string', 'Name the server\'s Animus data folder with --data; its npcs/shotqa/ files are edited by hand');
const target = `${id}-target`, burner = `${id}-burner`;
const checks = [];
const passed = (name, detail = {}) => { checks.push({ name, ...detail }); console.log(JSON.stringify({ event: 'check', status: 'passed', name, ...detail })); };
let player, failure, lava;

async function create(npc, type, x, y, z, health) {
  await player.command(`animus create ${npc} type=${type} world=world x=${x} y=${y} z=${z} folder=shotqa`, `Created dormant ${npc}`);
  const file = path.join(data, 'npcs', 'shotqa', `${npc}.json`);
  const stored = JSON.parse(readFileSync(file, 'utf8'));
  stored.modules = { ...(stored.modules ?? {}), 'animus:health': health };
  stored.revision += 1;
  writeFileSync(file, JSON.stringify(stored, null, 2) + '\n');
  const edited = player.events.length;
  await delay(2000);
  const refused = player.events.slice(edited).find(e => e.event === 'chat' && e.text.includes('Hotload refused'));
  assert(!refused, refused?.text);
  const uuid = JSON.parse(readFileSync(file, 'utf8')).uuid;
  const from = player.events.length;
  await player.command(`animus spawn ${npc}`, `Spawning ${npc}`);
  const shown = await player.wait(e => e.event === 'spawn' && e.uuid === uuid, from, 20_000);
  return { uuid, entity: shown.id, x: shown.x, y: shown.y, z: shown.z };
}
/** Draws the bow fully at the body's chest and looses, once. */
async function shoot(body) {
  const moved = player.events.filter(e => e.event === 'move' && e.id === body.entity).at(-1) ?? body;
  player.send('look', { x: moved.x, y: moved.y + 1.2, z: moved.z });
  await delay(100);
  player.send('use_item');
  await delay(1300);
  player.send('release_use');
}
/** Shoots once a second and a half until the test passes. */
async function shootUntil(body, test, limit) {
  const from = player.events.length;
  for (let i = 0; i < limit; i++) {
    await shoot(body);
    await delay(1500);
    const found = player.events.slice(from).find(test);
    if (found) return found;
  }
  throw new Error(`No ${test} after ${limit} shots`);
}
/** Bit 0x01 of the shared flags, data id 0: the client draws the body in flames. */
const burning = (e, entity) => e.event === 'metadata' && e.id === entity && e.fields.some(f => f.id === 0 && (Number(f.value) & 1) === 1);

try {
  player = new NativeClient(harness, 'ShotQA');
  await player.wait(e => e.event === 'toolchain', 0, 180_000);
  await player.wait(e => e.event === 'position', 0, 60_000);
  await player.ready();
  const start = player.position;
  const ground = { x: Math.floor(start.x) + 0.5, y: start.y, z: Math.floor(start.z) + 0.5 };
  await player.command('gamemode survival @s');
  await player.command('clear @s');
  await player.command('give @s minecraft:bow');
  await player.command('give @s minecraft:arrow 64');
  await player.command('scoreboard objectives add shotkills minecraft.custom:minecraft.mob_kills');

  const body = await create(target, 'minecraft:horse', ground.x + 8, ground.y, ground.z, {
    killable: true, maximumHealth: 20, invulnerableTicks: 0, showHealth: true, knockback: false, respawn: { delayTicks: 6000 }
  });
  passed('a killable horse stands eight blocks away', body);

  const hurt = await shootUntil(body, e => e.event === 'damage_event' && e.id === body.entity, 4);
  passed('a bow shot sends the damage event: the client flashes it red and plays the horse hurt sound', { packet: hurt });
  const bar = await player.wait(e => (e.event === 'action_bar' || e.event === 'chat') && / [0-9.]+\/20$/.test(e.text), 0, 5_000);
  const left = Number(bar.text.match(/ ([0-9.]+)\/20$/)[1]);
  assert(left < 20 && left >= 8, `a full draw takes about 6 to 10; health shows ${left}`);
  passed('the shooter sees its health drop by a full-draw arrow', { text: bar.text, damage: 20 - left });

  const fell = await shootUntil(body, e => e.event === 'entity_event' && e.id === body.entity && e.status === 3, 8);
  passed('more arrows kill it: entity event 3 plays the death fall', { event: fell });
  await delay(500);
  const from = player.events.length;
  await player.command('scoreboard players get ShotQA shotkills', /ShotQA has 1 \[shotkills\]/);
  passed('the bow kill counts on the shooter\'s mob kill statistic', { reply: player.events.slice(from).find(e => e.event === 'chat' && e.text.includes('shotkills'))?.text });

  const hot = await create(burner, 'minecraft:horse', ground.x - 4, ground.y, ground.z + 4, {
    killable: true, maximumHealth: 40, sources: ['lava', 'fire'], respawn: { delayTicks: 6000 }
  });
  lava = { x: Math.floor(hot.x), y: Math.floor(hot.y), z: Math.floor(hot.z) };
  const before = player.events.length;
  await player.command(`setblock ${lava.x} ${lava.y} ${lava.z} minecraft:lava`);
  const flames = await player.wait(e => burning(e, hot.entity), before, 10_000);
  passed('lava at its feet sets the body burning for every viewer', { metadata: flames });
  const scorched = await player.wait(e => e.event === 'damage_event' && e.id === hot.entity, before, 10_000);
  passed('the lava hurts it', { packet: scorched });
  await player.command(`setblock ${lava.x} ${lava.y} ${lava.z} minecraft:air`);
  lava = null;

  for (const npc of [target, burner]) await player.command(`animus delete ${npc}`, /Deleted [^;]*; tx /);
  await player.command('scoreboard objectives remove shotkills');
  passed('the QA NPCs are deleted');
} catch (error) {
  failure = error;
} finally {
  if (lava && player) { try { await player.command(`setblock ${lava.x} ${lava.y} ${lava.z} minecraft:air`); } catch { /* the instance is deleted anyway */ } }
  for (const client of clients) await client.close();
  wire.end();
}
const result = { event: 'result', status: failure ? 'failed' : 'passed', id, version, port, checks, nativeCodec, evidence: resultDir,
  ...(failure ? { error: String(failure.stack ?? failure.message ?? failure) } : {}) };
writeFileSync(path.join(resultDir, 'result.json'), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result));
if (failure) process.exitCode = 1;
