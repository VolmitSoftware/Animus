#!/usr/bin/env node
// Exact-version live packet proof. Uses Mojang's vanilla codecs; no Prismarine
// schema aliases. Run only against the dedicated offline loopback QA server.
import { spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync, createWriteStream } from 'node:fs';
import { homedir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createInterface } from 'node:readline';
import assert from 'node:assert/strict';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = new Map();
for (let i = 2; i < process.argv.length; i++) {
  const arg = process.argv[i];
  if (!arg.startsWith('--')) throw new Error(`Expected named option: ${arg}`);
  args.set(arg.slice(2), process.argv[i + 1]?.startsWith('--') || i + 1 === process.argv.length ? true : process.argv[++i]);
}
const version = args.get('version') ?? '26.3';
const port = Number(args.get('port') ?? 65490);
assert(/^[0-9]+\.[0-9]+(?:\.[0-9]+)?$/.test(version), 'Use an exact release version');
assert(['26.1.2', '26.2', '26.3'].includes(version), 'Native QA adapters must be validated before adding another exact version');
assert(Number.isInteger(port) && port > 0 && port < 65536, 'Invalid loopback port');
const cache = path.join(root, '.qa', 'protocol-cache');
const classes = path.join(cache, 'client-classes', version);
const resultDir = path.join(cache, 'results', `${new Date().toISOString().replaceAll(':', '-')}-${version}`);
mkdirSync(classes, { recursive: true });
mkdirSync(resultDir, { recursive: true });
const jar = path.resolve(args.get('jar') ?? path.join(homedir(), '.gradle', 'caches', 'fabric-loom', version, 'minecraft-extracted_server.jar'));
const libraries = path.resolve(args.get('libraries') ?? path.join(root, '.qa', `leaf-${version}`, 'libraries'));
assert(existsSync(jar), `Exact ${version} vanilla server jar required: ${jar}`);
assert(existsSync(libraries), `Server dependency directory required: ${libraries}`);
const javaHome = args.get('java-home') ?? (existsSync('/opt/homebrew/opt/openjdk@25/bin/java') ? '/opt/homebrew/opt/openjdk@25' : process.env.JAVA_HOME);
const java = javaHome ? path.join(javaHome, 'bin', 'java') : 'java';
const javac = javaHome ? path.join(javaHome, 'bin', 'javac') : 'javac';
function jars(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const name = path.join(directory, entry.name);
    return entry.isDirectory() ? jars(name) : entry.name.endsWith('.jar') ? [name] : [];
  });
}
const classpath = [classes, jar, ...jars(libraries)].join(path.delimiter);
const revision = version === '26.3' ? 'v26_3' : 'v26_1_26_2';
const nativeSource = path.join(root, 'qa', 'protocol-client', 'NativeProtocolClient.java');
const revisionSource = path.join(root, 'qa', 'protocol-client', revision, 'ProtocolRevision.java');
const compile = spawnSync(javac, ['-cp', classpath, '-d', classes, nativeSource, revisionSource], { encoding: 'utf8' });
if (compile.status !== 0) throw new Error(`Exact-version helper compilation failed; no schema fallback is permitted.\n${compile.stdout}\n${compile.stderr}`);
const hashFile = file => createHash('sha256').update(readFileSync(file)).digest('hex');
const nativeCodec = { adapter: revision, jarSha256: hashFile(jar), clientSourceSha256: hashFile(nativeSource), adapterSourceSha256: hashFile(revisionSource), scenarioSourceSha256: hashFile(fileURLToPath(import.meta.url)) };
const wire = createWriteStream(path.join(resultDir, 'wire.ndjson'));
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const checks = [];
function passed(name, detail = {}) { checks.push({ name, ...detail }); console.log(JSON.stringify({ event: 'check', status: 'passed', name, ...detail })); }
function near(a, b) { assert(Math.abs(a - b) < 0.00001, `Coordinate mismatch ${a} != ${b}`); }
const clients = [];

class Client {
  constructor(username) {
    this.username = username;
    this.events = [];
    this.waiters = new Set();
    this.lastCommand = 0;
    this.closing = false;
    this.stderr = createWriteStream(path.join(resultDir, `${username}-${clients.length}.stderr.log`));
    this.process = spawn(java, ['-cp', classpath, 'NativeProtocolClient', String(port), username, version], { cwd: resultDir, stdio: ['pipe', 'pipe', 'pipe'] });
    this.process.stderr.pipe(this.stderr);
    createInterface({ input: this.process.stdout }).on('line', line => {
      let event;
      try { event = JSON.parse(line); } catch { this.fatal = new Error(`Unexpected native client output: ${line.slice(0, 200)}`); this.wake(); return; }
      event.sequence = this.events.length;
      this.events.push(event);
      wire.write(JSON.stringify(event) + '\n');
      if (event.event === 'error') this.fatal = new Error(`${username}: ${event.message}`);
      this.wake();
    });
    this.process.on('error', error => { this.fatal = error; this.wake(); });
    this.process.on('exit', (code, signal) => {
      this.exited = true;
      if (!this.closing) this.fatal ??= new Error(`${username} exited unexpectedly (${code}, ${signal})`);
      this.wake();
    });
    clients.push(this);
  }
  wake() { for (const waiter of [...this.waiters]) waiter(); }
  send(op, data = {}) {
    if (this.fatal) throw this.fatal;
    this.process.stdin.write(JSON.stringify({ op, ...data }) + '\n');
  }
  wait(test, from = 0, timeout = 15_000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.waiters.delete(check); reject(new Error(`${this.username}: timed out waiting for ${test}; recent chat: ${this.events.filter(e => e.event === 'chat').slice(-4).map(e => e.text).join(' | ')}`)); }, timeout);
      const check = () => {
        const found = this.events.slice(from).find(test);
        if (found || this.fatal) { clearTimeout(timer); this.waiters.delete(check); found ? resolve(found) : reject(this.fatal); }
      };
      this.waiters.add(check); check();
    });
  }
  async ready() {
    const toolchain = await this.wait(e => e.event === 'toolchain');
    assert.equal(toolchain.version, version);
    const registries = await this.wait(e => e.event === 'registries');
    assert(registries.count > 0 && registries.entries > 0, 'Server registry contents must be loaded before game packet decoding');
    if (version === '26.3') assert(registries.keys.includes('minecraft:cow_sound_variant'));
    await this.wait(e => e.event === 'position');
    return toolchain;
  }
  async command(text, match, timeout = 15_000) {
    // Bound synthetic command rate; ordinary client/server packet processing continues.
    await delay(Math.max(0, 650 - (Date.now() - this.lastCommand)));
    const start = this.events.length;
    this.lastCommand = Date.now();
    this.send('command', { text });
    return match ? this.wait(e => e.event === 'chat' && (typeof match === 'string' ? e.text.includes(match) : match.test(e.text)), start, timeout) : start;
  }
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
  async close() {
    if (this.closing) return;
    this.closing = true;
    if (!this.exited) {
      this.process.stdin.write('{"op":"close"}\n');
      this.process.stdin.end();
      for (let i = 0; i < 30 && !this.exited; i++) await delay(100);
      if (!this.exited) this.process.kill('SIGKILL');
    }
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
