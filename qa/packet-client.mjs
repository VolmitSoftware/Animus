#!/usr/bin/env node
// Exact-version live packet proof. Uses Mojang's vanilla codecs; no Prismarine
// schema aliases. Run only against the dedicated offline loopback QA server.
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { NativeClient, prepareNative, parseArgs, delay } from './protocol-client/client.mjs';

const args = parseArgs();
const harness = prepareNative(args, import.meta.url);
const { version, port, resultDir, nativeCodec, wire, clients } = harness;
const checks = [];
function passed(name, detail = {}) { checks.push({ name, ...detail }); console.log(JSON.stringify({ event: 'check', status: 'passed', name, ...detail })); }
function near(a, b) { assert(Math.abs(a - b) < 0.00001, `Coordinate mismatch ${a} != ${b}`); }
class Client extends NativeClient {
  constructor(username) { super(harness, username); }
  async status() { return (await this.command('animus proof status', 'Packet proof ready')).text; }
  async resetPosition() {
    const start = this.events.length;
    await this.command('tp @s 0.0 61.0 0.0 0 0');
    await this.wait(e => e.event === 'position' && e.x === 0 && e.y === 61 && e.z === 0, start);
    await delay(100);
  }
  async spawnBody(type, skin = null) {
    const start = this.events.length;
    const reply = await this.command(`animus proof spawn minecraft:${type}${skin ? ` ${skin}` : ''}`, 'Proof spawned id=');
    const fields = Object.fromEntries([...reply.text.matchAll(/([\w-]+)=([^ ]+)/g)].map(m => [m[1], m[2]]));
    const body = { id: Number(fields.id), uuid: fields.uuid, type: fields.type, x: Number(fields.x), y: Number(fields.y), z: Number(fields.z), height: Number(fields.height), start };
    assert.equal(fields['world-registered'], '0');
    const packet = await this.wait(e => e.event === 'spawn' && e.id === body.id, start);
    assert.equal(packet.type, `minecraft:${type}`);
    assert.equal(packet.uuid, body.uuid);
    for (const axis of ['x', 'y', 'z']) near(packet[axis], body[axis]);
    passed(`wire spawn ${type}`, { id: body.id, worldRegistered: 0 });
    return body;
  }
  async clear(bodies = []) {
    const start = this.events.length;
    await this.command('animus proof clear', 'Proof cleared');
    for (const body of bodies) {
      await this.wait(e => e.event === 'remove' && e.ids.includes(body.id), start);
      if (body.type === 'minecraft:player') await this.wait(e => e.event === 'player_info_remove' && e.uuids.includes(body.uuid), start);
    }
  }
  async look(body) {
    const start = this.events.length;
    this.send('look', { x: body.x, y: body.y + body.height / 2, z: body.z });
    await this.wait(e => e.event === 'look', start);
    await delay(150);
  }
  async interact(body, op, match) {
    await this.look(body);
    const start = this.events.length;
    this.send(op, { id: body.id });
    const id = new RegExp(`\\bid=${body.id}(?:\\s|$)`);
    return this.wait(e => e.event === 'chat' && id.test(e.text) && e.text.includes(match), start);
  }
  async move(body, dx, dy, dz) {
    const start = this.events.length;
    await this.command(`animus proof move ${body.id} ${dx} ${dy} ${dz}`, `Proof moved id=${body.id}`);
    const packet = await this.wait(e => e.event === 'move' && e.id === body.id, start);
    body.x += dx; body.y += dy; body.z += dz;
    for (const axis of ['x', 'y', 'z']) near(packet[axis], body[axis]);
  }

}

let owner;
let wall = false;
let navigationCleanup;
let registryFixture = false;
let failure;
try {
  owner = new Client('AnimusProofQA');
  const toolchain = await owner.ready();
  passed('exact native protocol connection', { version, protocol: toolchain.protocol });
  const registries = owner.events.find(e => e.event === 'registries');
  passed('server configuration registries loaded with native codecs', { registries: registries.count, entries: registries.entries });
  assert((await owner.status()).includes('world-registered=0'));
  await owner.resetPosition();
  await owner.command('kill @e[type=minecraft:cow,tag=AnimusRegistryQA]');
  const cowStart = owner.events.length;
  registryFixture = true;
  await owner.command('summon minecraft:cow 4.0 61.0 0.0 {Tags:["AnimusRegistryQA"],NoAI:1b,Silent:1b}', 'Summoned');
  const cow = await owner.wait(e => e.event === 'spawn' && e.type === 'minecraft:cow' && e.x === 4 && e.y === 61 && e.z === 0, cowStart);
  await owner.wait(e => e.event === 'metadata' && e.id === cow.id && e.entries > 0, cowStart);
  passed('real cow metadata decodes against synchronized variant registries');
  await owner.command('kill @e[type=minecraft:cow,tag=AnimusRegistryQA]'); registryFixture = false;
  await owner.clear();
  if (args.has('skin-race')) {
    assert(args.get('skin'), '--skin-race requires a signed --skin fixture');
    const start = owner.events.length;
    owner.send('command', { text: `animus proof spawn minecraft:player ${args.get('skin')}` });
    owner.send('command', { text: 'animus proof clear' });
    await owner.wait(e => e.event === 'chat' && e.text.includes('Proof cleared'), start);
    await delay(500);
    assert((await owner.status()).includes('viewer bodies=0'), 'An asynchronous skin read must not recreate a cleared session');
    const active = new Set();
    // No peer is connected yet, so player spawns in this isolated phase are proof bodies.
    for (const event of owner.events.slice(start)) {
      if (event.event === 'spawn' && event.type === 'minecraft:player') active.add(event.id);
      if (event.event === 'remove') for (const id of event.ids) active.delete(id);
    }
    assert.equal(active.size, 0, 'A spawn that wins the race must still be removed by clear');
    passed('asynchronous signed skin spawn cannot survive immediate clear');
  }
  const player = await owner.spawnBody('player', args.get('skin'));
  const info = await owner.wait(e => e.event === 'player_info' && e.uuid === player.uuid && e.actions.includes('ADD_PLAYER'), player.start);
  assert(info.sequence < owner.events.find(e => e.event === 'spawn' && e.id === player.id).sequence, 'Player info must precede spawn');
  await owner.wait(e => e.event === 'metadata' && e.id === player.id && e.fields.some(field => field.id === toolchain.skinMetadataId && field.value === 127), player.start);
  passed('NPC skin layer metadata uses the exact native accessor');
  if (args.get('skin')) {
    assert.equal(info.textures.length, 1, 'Expected one skin texture property');
    assert(info.textures[0].signed, 'Expected preserved texture signature');
    if (args.get('skin-file')) {
      const expected = JSON.parse(readFileSync(args.get('skin-file'), 'utf8'));
      const hash = s => createHash('sha256').update(s, 'utf8').digest('hex');
      assert.equal(info.textures[0].valueSha256, hash(expected.textureRaw));
      assert.equal(info.textures[0].signatureSha256, hash(expected.signature));
      passed('skin texture and signature exact payload hashes');
    } else passed('signed skin texture on wire');
  }
  passed('player info precedes entity spawn');
  await owner.move(player, 1, 0, 0);
  passed('absolute movement packet');
  await owner.move(player, -1, 0, 0);
  const attack = await owner.interact(player, 'attack', 'action=ATTACK');
  assert(attack.text.includes('health=19.0') || attack.text.includes('health=19'));
  const use = await owner.interact(player, 'use', 'action=INTERACT');
  assert(use.text.includes('health=19'));
  passed('authoritative attack and use', { health: 19 });
  await owner.move(player, 0, 0, 4);
  await owner.interact(player, 'attack', 'reason=OUT_OF_REACH');
  passed('out of reach rejected');
  await owner.move(player, 0, 0, -4);
  await owner.command('fill -1 61 1 1 64 1 stone'); wall = true;
  await delay(150);
  await owner.interact(player, 'attack', 'reason=OCCLUDED');
  passed('line of sight rejected');
  await owner.command('fill -1 61 1 1 64 1 air'); wall = false;
  await owner.interact(player, 'attack', 'action=ATTACK');
  passed('interaction recovers after obstruction removal');
  await owner.clear([player]);
  passed('entity and player info despawn');

  if (args.has('event-fixture')) {
    await owner.resetPosition();
    const cancellable = await owner.spawnBody('armor_stand');
    await owner.command('animusqa cancel', 'QA next interaction=cancel');
    await owner.interact(cancellable, 'attack', 'Proof interaction cancelled');
    const unchanged = await owner.interact(cancellable, 'use', 'action=INTERACT');
    assert(unchanged.text.includes('health=20'), 'Cancelled event must preserve authoritative health');
    passed('third-party preview event cancellation preserves health');
    await owner.command('animusqa clear', 'QA next interaction=clear');
    await owner.look(cancellable);
    const clearStart = owner.events.length;
    owner.send('attack', { id: cancellable.id });
    await owner.wait(e => e.event === 'chat' && e.text.includes('QA cleared preview during event'), clearStart);
    await owner.wait(e => e.event === 'remove' && e.ids.includes(cancellable.id), clearStart);
    await delay(150);
    assert(!owner.events.slice(clearStart).some(e => e.event === 'chat' && e.text.includes('Proof accepted')));
    assert((await owner.status()).includes('viewer bodies=0'));
    passed('reentrant preview teardown prevents post-event mutation');
    await owner.command('animusqa allow', 'QA next interaction=allow');
  }

  if (args.has('navigation')) {
    await owner.resetPosition();
    const planned = await owner.spawnBody('player');
    const planStart = owner.events.length;
    const found = await owner.command(`animus proof path ${planned.id} 2 0 0`, `Proof path id=${planned.id} status=FOUND`);
    assert(/\bsteps=3(?:\s|$)/.test(found.text));
    assert(/\bunavailable=0(?:\s|$)/.test(found.text));
    const sampled = Number(/\bsampled=(\d+)/.exec(found.text)?.[1]);
    const ticks = /\bcapture-ticks=(\d+)\.\.(\d+)/.exec(found.text);
    assert(sampled > 0 && sampled <= 8192 && ticks && Number(ticks[2]) >= Number(ticks[1]));
    assert(found.text.includes('planning only'));
    assert(!owner.events.slice(planStart).some(e => e.event === 'move' && e.id === planned.id), 'Planning must not move the virtual body');
    passed('bounded world capture produces a three-node plan without movement', { sampled, captureTicks: [Number(ticks[1]), Number(ticks[2])] });
    const x = Math.floor(planned.x) + 2, y = Math.floor(planned.y), z = Math.floor(planned.z);
    navigationCleanup = `fill ${x} ${y} ${z} ${x} ${y + 3} ${z} air`;
    await owner.command(`fill ${x} ${y} ${z} ${x} ${y + 3} ${z} stone`, /Successfully filled|No blocks were filled/);
    await owner.command(`animus proof path ${planned.id} 2 0 0`, `Proof path id=${planned.id} status=UNREACHABLE`);
    assert(!owner.events.slice(planStart).some(e => e.event === 'move' && e.id === planned.id));
    passed('solid destination rejects a route without moving the body');
    await owner.command(navigationCleanup, /Successfully filled|No blocks were filled/); navigationCleanup = undefined;
    const cancellationStart = owner.events.length;
    // This 2,704-voxel capture exceeds the 256-unit tick budget. Clear promptly
    // after the queue receipt, without the ordinary synthetic command pacing.
    await owner.command(`animus proof path ${planned.id} 8 8 8`, `Proof path queued id=${planned.id}`);
    owner.send('command', { text: 'animus proof clear' });
    await owner.wait(e => e.event === 'chat' && e.text.includes('Proof cleared'), cancellationStart);
    await owner.wait(e => e.event === 'remove' && e.ids.includes(planned.id), cancellationStart);
    await delay(1000);
    assert(!owner.events.slice(cancellationStart).some(e => e.event === 'chat' && e.text.includes(`Proof path id=${planned.id} `)), 'Cleared pending capture must not report a completed path');
    assert((await owner.status()).includes('viewer bodies=0'));
    passed('clearing a queued capture suppresses late path results');
  }

  if (!args.has('quick')) {
    await owner.resetPosition();
    const bodies = [];
    for (const type of ['armor_stand', 'horse', 'turtle', 'frog', 'llama', 'armadillo', 'ender_dragon']) bodies.push(await owner.spawnBody(type));
    assert((await owner.status()).includes('viewer bodies=7'));
    assert((await owner.status()).includes('world-registered=0'));
    const horse = bodies.find(b => b.type === 'minecraft:horse');
    const rider = bodies.find(b => b.type === 'minecraft:armor_stand');
    const mountStart = owner.events.length;
    await owner.command(`animus proof mount ${horse.id} ${rider.id}`, 'Proof passengers');
    await owner.wait(e => e.event === 'passengers' && e.vehicle === horse.id && e.ids.includes(rider.id), mountStart);
    const detachStart = owner.events.length;
    await owner.command(`animus proof mount ${horse.id}`, 'Proof passengers');
    await owner.wait(e => e.event === 'passengers' && e.vehicle === horse.id && e.ids.length === 0, detachStart);
    passed('static mount and detach packets');
    await owner.clear(bodies);
    passed('all nonplayer bodies despawn');

    await owner.resetPosition();
    const ownBody = await owner.spawnBody('player');
    await owner.command('deop AnimusProofPeer');
    const peer = new Client('AnimusProofPeer');
    await peer.ready();
    await peer.command('animus proof status', /permission|allowed|access|Unknown or incomplete command/i);
    passed('nonadministrator proof command denied');
    const peerBaseline = peer.events.length;
    const another = await owner.spawnBody('frog');
    await delay(200);
    assert(!peer.events.slice(peerBaseline).some(e => e.event === 'spawn' && e.uuid === another.uuid));
    assert(!peer.events.some(e => e.event === 'spawn' && e.uuid === ownBody.uuid));
    passed('second viewer does not receive owner proof bodies');
    await owner.command('op AnimusProofPeer');
    await delay(200);
    await peer.resetPosition();
    await peer.clear();
    const otherBody = await peer.spawnBody('turtle');
    assert(!owner.events.some(e => e.event === 'spawn' && e.uuid === otherBody.uuid));
    const statusBefore = await peer.status();
    await peer.look(ownBody);
    peer.send('attack', { id: ownBody.id });
    peer.send('attack', { id: 2147483000 });
    await delay(300);
    const statusAfter = await peer.status();
    assert.equal(/accepted=([0-9]+)/.exec(statusAfter)?.[1], /accepted=([0-9]+)/.exec(statusBefore)?.[1]);
    assert((await owner.status()).includes('accepted=0'));
    passed('foreign viewer and forged IDs cannot affect proof bodies');
    await peer.clear([otherBody]);
    const staleStart = peer.events.length;
    peer.send('attack', { id: otherBody.id });
    await delay(300);
    assert(!peer.events.slice(staleStart).some(e => e.event === 'chat' && e.text.includes('Proof accepted')));
    assert((await peer.status()).includes('viewer bodies=0'));
    passed('stale virtual ID rejected after clear');
    await owner.command('deop AnimusProofPeer');
    await peer.close();
    const oldUUIDs = [ownBody.uuid, another.uuid];
    await owner.close();
    await delay(500);
    owner = new Client('AnimusProofQA');
    await owner.ready();
    const reconnect = await owner.status();
    assert(reconnect.includes('viewer bodies=0') && reconnect.includes('accepted=0'));
    assert(!owner.events.some(e => e.event === 'spawn' && oldUUIDs.includes(e.uuid)));
    passed('logout and reconnect removes proof state and ghost spawns');
  }
} catch (error) {
  failure = error;
  console.error(error.stack);
} finally {
  if (owner && !owner.closing && !owner.fatal) {
    try { if (wall) await owner.command('fill -1 61 1 1 64 1 air'); if (navigationCleanup) await owner.command(navigationCleanup); if (registryFixture) await owner.command('kill @e[type=minecraft:cow,tag=AnimusRegistryQA]'); await owner.command('deop AnimusProofPeer'); await owner.clear(); } catch {}
  }
  for (const client of clients) await client.close();
  wire.end();
}
const result = { event: 'result', status: failure ? 'failed' : 'passed', version, port, nativeCodec, checks, evidence: resultDir, limitations: ['Packet codec and wire assertions do not establish graphical appearance, client frame time, controllable riding, or production NPC lifecycle.'], ...(failure ? { error: failure.message } : {}) };
writeFileSync(path.join(resultDir, 'result.json'), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result));
if (failure) process.exitCode = 1;
