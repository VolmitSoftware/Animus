#!/usr/bin/env node
// Production navigation observed through exact native packets, owner commands,
// and a third-party event fixture. Only isolated synthetic QA data is edited.
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, readFileSync, writeFileSync, unlinkSync } from 'node:fs';
import path from 'node:path';
import { NativeClient, prepareNative, parseArgs, delay } from './protocol-client/client.mjs';
const args = parseArgs(), harness = prepareNative(args, import.meta.url, 'movement');
const { version, port, resultDir, nativeCodec, clients, wire } = harness;
const id = args.get('id') ?? `movement-${Date.now().toString(36)}`;
assert(/^movement-[a-z0-9]+$/.test(id));
const dataDir = path.resolve(args.get('data-dir') ?? '');
assert(dataDir.split(path.sep).includes('.qa'), 'Only isolated QA data may be edited');
const routeFile = path.join(dataDir, 'behaviors', 'routes', `${id}.json`);
const checks = [];
const passed = (name, detail = {}) => { checks.push({ name, ...detail }); console.log(JSON.stringify({ event: 'check', status: 'passed', name, ...detail })); };
const distance = (a, b) => Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z);
const near = (a, b, tolerance = .00001) => assert(distance(a, b) <= tolerance, `${JSON.stringify(a)} differs from ${JSON.stringify(b)}`);
const fields = text => Object.fromEntries([...text.matchAll(/([\w-]+)=([^ ;|]+)/g)].map(match => [match[1], match[2]]));
const vector = (text, label) => {
  const m = text.match(new RegExp(`${label}=Vec3\\[x=([^,]+), y=([^,]+), z=([^\\]]+)\\]`));
  return m && { x: Number(m[1]), y: Number(m[2]), z: Number(m[3]) };
};
const origin = { world: 'world', x: .5, y: 61, z: 3.5, yaw: 0, pitch: 0 };
let uuid, owner, failure, live = false, routeOwned = false;
class Client extends NativeClient {
  constructor(name) { super(harness, name); }
  body() {
    let body;
    for (const e of this.events) {
      if (e.event === 'spawn' && e.uuid === uuid) body = { ...e };
      if (body && e.event === 'move' && e.id === body.id) Object.assign(body, e, { uuid });
      if (body && e.event === 'remove' && e.ids.includes(body.id)) body = undefined;
      if (e.event === 'respawn') body = undefined;
    }
    return body;
  }
  async inspect(npc = id) {
    const from = this.events.length;
    await this.command(`animus inspect ${npc}`);
    const doc = await this.wait(e => e.event === 'chat' && e.text.startsWith('{') && e.text.includes(`"id":"${npc}"`), from);
    const runtime = await this.wait(e => e.event === 'chat' && e.text.startsWith(`Runtime ${npc}:`), from);
    return { document: JSON.parse(doc.text), runtime: runtime.text };
  }
  async status(npc = id) {
    const e = await this.command(`animus navigation ${npc} status`, `Navigation ${npc}:`);
    const f = fields(e.text);
    return { text: e.text, state: e.text.slice(e.text.indexOf(': ') + 2).split(';')[0], route: f.route, position: vector(e.text, 'position'), tick: Number(f.tick), arrivals: Number(f.arrivals), sampled: Number(f.sampled), unavailable: Number(f.unavailable) };
  }
  async until(predicate, npc = id, timeout = 20_000) {
    const end = Date.now() + timeout;
    let status;
    do { status = await this.status(npc); if (predicate(status)) return status; await delay(80); } while (Date.now() < end);
    throw new Error(`Navigation condition timed out: ${status?.text}`);
  }
  async route(target, mode = 'walk', speed = 2, npc = id) {
    const e = await this.command(`animus navigate ${npc} ${target.x} ${target.y} ${target.z} ${mode} ${speed}`, `Navigation requested for ${npc}`);
    return fields(e.text).route;
  }
  async arrive(target, mode = 'walk', speed = 2) {
    const from = this.events.length, route = await this.route(target, mode, speed);
    const status = await this.until(s => s.route === route && (s.state === 'arrived' || /^(blocked|failed)/.test(s.state)));
    assert.equal(status.state, 'arrived', status.text); near(status.position, target);
    const receipt = await this.wait(e => e.event === 'chat' && e.text.includes(`QA waypoint npc=${uuid}`) && e.text.includes(`route=${route}`), from);
    assert(receipt.text.includes('world-registered=0') && receipt.text.includes('waypoint=-1'));
    if (!this.body() || distance(this.body(), target) >= .00001)
      await this.wait(e => (e.event === 'move' || e.event === 'spawn') && this.body() && distance(this.body(), target) < .00001, from, 5000);
    near(this.body(), target);
    return { status, from, route };
  }
  async reset(position = origin, modules = {}) {
    await this.command(`animus navigation ${id} stop`, 'Navigation stop requested');
    const previous = this.body()?.id, from = this.events.length;
    await this.command(`animus despawn ${id}`, 'Despawn requested');
    if (previous) await this.wait(e => e.event === 'remove' && e.ids.includes(previous), from);
    const edit = `animus edit ${id} ${JSON.stringify({ position, modules })}`;
    if (edit.length <= 256) await this.command(edit, 'Saved;');
    else {
      const source = path.join(dataDir, 'npcs', 'movementqa', `${id}.json`);
      const definition = JSON.parse(readFileSync(source, 'utf8'));
      assert.equal(definition.id, id); assert.equal(definition.uuid, uuid); assert.equal(definition.spawned, false);
      Object.assign(definition, { position, modules, revision: definition.revision + 1 });
      writeFileSync(source, JSON.stringify(definition, null, 2) + '\n');
      await this.command('animus reload', 'reload successful');
    }
    await this.command(`animus spawn ${id}`, 'Spawn requested');
    await this.wait(e => e.event === 'spawn' && e.uuid === uuid, from);
    near(this.body(), position); await delay(150);
  }
  async click(cancel = false) {
    if (cancel) await this.command('animusqa cancel', 'QA next interaction=cancel');
    const body = this.body(); assert(body);
    this.send('look', { x: body.x, y: body.y + .9, z: body.z }); await delay(80);
    const from = this.events.length;
    this.send('use', { id: body.id });
    if (cancel) {
      await this.wait(e => e.event === 'chat' && e.text.includes('QA cancelled'), from);
      await delay(150);
      assert(!this.events.slice(from).some(e => e.event === 'chat' && e.text.includes(`Selected ${id} via`)));
    } else {
      await this.wait(e => e.event === 'chat' && e.text.includes(`QA production interaction id=${uuid}`) && e.text.includes('preview=false world-registered=0'), from);
      await this.wait(e => e.event === 'chat' && e.text.includes(`Selected ${id} via INTERACT`), from);
    }
  }
}
try {
  owner = new Client('AnimusProofQA'); const toolchain = await owner.ready();
  await owner.command('gamemode creative @s');
  const tp = owner.events.length;
  await owner.command('execute in minecraft:overworld run tp @s 0.0 61.0 0.0 0 0', 'Teleported AnimusProofQA');
  if (owner.position?.x !== 0 || owner.position?.y !== 61 || owner.position?.z !== 0)
    await owner.wait(e => e.event === 'position' && e.x === 0 && e.y === 61 && e.z === 0, tp);
  await owner.command(`animus create ${id} minecraft:player world .5 61 3.5 movementqa Motion Guard`, `Created dormant ${id}`); live = true;
  uuid = (await owner.inspect()).document.uuid;
  await owner.command(`animusqa expect ${uuid}`, 'QA production expect');
  await owner.command(`animusqa motion expect ${uuid}`, 'QA motion expect');
  await owner.command(`animus spawn ${id}`, `Spawn requested for ${id}`);
  await owner.wait(e => e.event === 'spawn' && e.uuid === uuid);
  assert((await owner.inspect()).runtime.includes('world-registered=0'));
  passed('production navigation starts with an authoritative virtual body', { version, protocol: toolchain.protocol, uuid });

  let arrived = await owner.arrive({ x: 4.5, y: 61, z: 3.5 });
  assert(arrived.status.sampled > 0 && arrived.status.unavailable === 0 && /capture=\d+\.\.\d+/.test(arrived.status.text));
  const moves = owner.events.slice(arrived.from).filter(e => e.event === 'move' && e.id === owner.body().id);
  assert(moves.length > 2, 'Execution must produce incremental wire movement');
  assert.equal((await owner.inspect()).document.position.x, origin.x, 'Runtime movement must not rewrite the definition origin');
  passed('bounded capture executes an incremental route and reports a committed arrival matching wire coordinates', { packets: moves.length, sampled: arrived.status.sampled });

  await owner.reset();
  const movingRoute = await owner.route({ x: 6.5, y: 61, z: 3.5 }, 'walk', 1);
  await owner.until(s => s.state === 'moving' && s.position.x > .55);
  await owner.click(); await owner.click(true);
  const moving = await owner.status(); assert.equal(moving.route, movingRoute); assert.equal(moving.state, 'moving');
  passed('moving NPC accepts server-aimed interactions and honors third-party cancellation');
  await owner.command(`animus navigation ${id} stop`, 'stopped=true');
  const stopped = await owner.status(); await delay(800); const stable = await owner.status();
  near(stopped.position, stable.position); assert.equal(stable.arrivals, stopped.arrivals);
  passed('administrator cancellation stops authoritative movement without a late arrival');

  await owner.reset();
  await owner.route({ x: 8.5, y: 61, z: 3.5 }, 'walk', 1);
  await owner.until(s => s.state === 'moving' && s.position.x > .55);
  await owner.command('fill 4 61 0 4 64 7 stone', /filled/i);
  const blocked = await owner.until(s => /^(blocked|failed)/.test(s.state));
  assert(blocked.state.startsWith('blocked:'), blocked.text); assert(blocked.position.x <= 3.70001);
  passed('new obstruction stops a previously planned route before virtual hitbox penetration', { position: blocked.position, state: blocked.state });
  await owner.command('fill 4 61 0 4 64 7 air', /filled/i);

  await owner.reset();
  await owner.command('fill 2 61 3 2 64 3 stone', /filled/i);
  arrived = await owner.arrive({ x: 5.5, y: 61, z: 3.5 });
  const detour = owner.events.slice(arrived.from).filter(e => e.event === 'move' && e.id === owner.body().id);
  assert(detour.some(e => Math.abs(e.z - 3.5) > .7), 'Path must visibly detour around the obstructed column');
  assert(!detour.some(e => e.x + .3 > 2.000001 && e.x - .3 < 2.999999 && e.z + .3 > 3.000001 && e.z - .3 < 3.999999 && e.y < 64.999999), 'Wire body must avoid obstacle bounds');
  passed('planned obstacle detour remains collision-free on the wire');
  await owner.command('fill 2 61 3 2 64 3 air', /filled/i);

  await owner.reset();
  await owner.command('fill 1 61 2 3 61 4 stone', /filled/i);
  await owner.arrive({ x: 2.5, y: 62, z: 3.5 }, 'walk', 2);
  await owner.arrive({ x: 5.5, y: 61, z: 3.5 }, 'walk', 2);
  passed('whole-block rise and fall execute with supported landings');
  await owner.command('fill 1 61 2 3 61 4 air', /filled/i);
  await owner.reset();
  await owner.arrive({ x: 3.5, y: 64, z: 3.5 }, 'fly', 3);
  passed('flying route reaches an unsupported elevated target without a world entity');

  // A walking body swims at the water surface and climbs out; only aquatic bodies dive.
  // Walls inside the route capture leave a one-block lane, so the pond is the only way across.
  await owner.reset();
  await owner.command('fill -1 61 2 5 63 4 stone', /filled/i);
  await owner.command('fill 0 61 3 4 63 3 air', /filled/i);
  await owner.command('fill 0 57 2 4 59 4 stone', /filled/i);
  await owner.command('fill 1 58 3 3 60 3 water', /filled/i);
  const swum = await owner.arrive({ x: 4.5, y: 61, z: 3.5 }, 'swim', 2);
  const strokes = owner.events.slice(swum.from).filter(e => e.event === 'move' && e.id === owner.body().id && e.x > 1.5 && e.x < 3.5);
  assert(strokes.length && strokes.every(e => Math.abs(e.y - 60) < .00001), 'Pond crossing must stay at the water surface: ' + JSON.stringify(strokes.slice(0, 4)));
  passed('swimming route crosses a prepared pond at the surface and climbs out', { strokes: strokes.length });
  await owner.command('fill 1 58 3 3 60 3 stone', /filled/i);
  await owner.command('fill -1 61 2 5 63 4 air', /filled/i);

  const waypointFrom = owner.events.length;
  await owner.reset(origin, { 'animus:navigation': { enabled: true, mode: 'walk', speed: 3, loop: false, waitTicks: 10, points: [{ x: 1.5, y: 61, z: 3.5 }, { x: 1.5, y: 61, z: 3.5 }, { x: 3.5, y: 61, z: 3.5 }] } });
  const first = await owner.wait(e => e.event === 'chat' && e.text.includes(`QA waypoint npc=${uuid}`) && e.text.includes('waypoint=0 '), waypointFrom);
  const second = await owner.wait(e => e.event === 'chat' && e.text.includes(`QA waypoint npc=${uuid}`) && e.text.includes('waypoint=1 '), first.sequence + 1);
  assert(second.sequence > first.sequence);
  assert(Number(fields(second.text)['fixture-tick']) - Number(fields(first.text)['fixture-tick']) >= 10, 'Repeated point must still respect the waypoint wait');
  await owner.wait(e => e.event === 'chat' && e.text.includes(`QA waypoint npc=${uuid}`) && e.text.includes('waypoint=2 '), second.sequence + 1);
  near((await owner.status()).position, { x: 3.5, y: 61, z: 3.5 });
  passed('explicit native waypoint JSON executes ordered points with owner-thread arrival events');
  const completed = await owner.status(), reloadFrom = owner.events.length;
  await owner.command('animus reload', 'reload successful'); await delay(1200);
  const unchanged = await owner.status(); near(unchanged.position, completed.position); assert.equal(unchanged.arrivals, completed.arrivals);
  assert(!owner.events.slice(reloadFrom).some(e => e.event === 'chat' && e.text.includes(`QA waypoint npc=${uuid}`)));
  passed('completed once-only waypoint route does not restart after an unchanged reload');

  assert(!existsSync(routeFile)); mkdirSync(path.dirname(routeFile), { recursive: true });
  writeFileSync(routeFile, JSON.stringify({ schemaVersion: 1, mode: 'walk', speed: 3, loop: false, waitTicks: 20, points: [{ x: 2.5, y: 61, z: 3.5 }] }, null, 2)); routeOwned = true;
  const reusableFrom = owner.events.length;
  await owner.reset(origin, { 'animus:navigation': { enabled: true, route: id } });
  await owner.wait(e => e.event === 'chat' && e.text.includes(`QA waypoint npc=${uuid}`) && e.text.includes('waypoint=0 '), reusableFrom);
  const reusable = await owner.status(); near(reusable.position, { x: 2.5, y: 61, z: 3.5 }); assert(reusable.text.includes('route-source='));
  await owner.command('animus reload', 'reload successful'); await delay(1000);
  const sameRoute = await owner.status(); near(sameRoute.position, reusable.position); assert.equal(sameRoute.arrivals, reusable.arrivals);
  const retainedFrom = owner.events.length;
  writeFileSync(routeFile, '{"schemaVersion":1,"points":"invalid"}');
  await owner.command('animus reload', 'reload successful');
  const retained = await owner.until(s => s.text.includes('route-error='), id, 15_000);
  assert(retained.text.includes('last-valid-route-retained=true'));
  assert.equal(readFileSync(routeFile, 'utf8'), '{"schemaVersion":1,"points":"invalid"}', 'Route reload must preserve invalid source bytes');
  near(retained.position, reusable.position); assert.equal(retained.arrivals, reusable.arrivals);
  assert(!owner.events.slice(retainedFrom).some(e => e.event === 'chat' && e.text.includes(`QA waypoint npc=${uuid}`)));
  passed('reusable JSON route executes and invalid replacement preserves its last valid state with diagnostics');
  writeFileSync(routeFile, JSON.stringify({ schemaVersion: 1, mode: 'walk', speed: 3, loop: false, waitTicks: 0, points: [{ x: 4.5, y: 61, z: 3.5 }] }));
  const repairedFrom = owner.events.length;
  await owner.command('animus reload', 'reload successful');
  await owner.wait(e => e.event === 'chat' && e.text.includes(`QA waypoint npc=${uuid}`) && e.text.includes('waypoint=0 '), repairedFrom);
  const repairedRoute = await owner.status(); near(repairedRoute.position, { x: 4.5, y: 61, z: 3.5 });
  assert(!repairedRoute.text.includes('route-error='));
  passed('changed valid reusable route restarts from its new first point and clears prior diagnostics');
  await owner.reset(); unlinkSync(routeFile); routeOwned = false;

  const loopFrom = owner.events.length;
  await owner.reset(origin, { 'animus:navigation': { enabled: true, mode: 'walk', speed: 3, loop: true, waitTicks: 5, points: [{ x: 1.5, y: 61, z: 3.5 }, { x: 2.5, y: 61, z: 3.5 }] } });
  const loopFirst = await owner.wait(e => e.event === 'chat' && e.text.includes(`QA waypoint npc=${uuid}`) && e.text.includes('waypoint=0 '), loopFrom);
  await owner.wait(e => e.event === 'chat' && e.text.includes(`QA waypoint npc=${uuid}`) && e.text.includes('waypoint=0 '), loopFirst.sequence + 1);
  await owner.command(`animus navigation ${id} stop`, 'stopped=true');
  const loopStopped = await owner.status(); await delay(700); const loopStable = await owner.status();
  near(loopStopped.position, loopStable.position); assert.equal(loopStopped.arrivals, loopStable.arrivals);
  passed('looping waypoint routes repeat and stop without scheduling another arrival');
  await owner.reset();

  // Observe the loaded edge around the wrapper's bounded arena. Explicitly
  // loading a new source can retain its neighbors, so never invent an absent
  // destination by assuming a chunk request has already unloaded it.
  const farId = `${id}-unloaded`;
  let sourceChunk, targetChunk;
  for (const candidate of [4, 3, 5]) {
    const source = await owner.command(`animusqa motion chunk ${candidate} 0 status`, 'QA chunk');
    const target = await owner.command(`animusqa motion chunk ${candidate + 2} 0 status`, 'QA chunk');
    if (source.text.includes('loaded=true') && source.text.includes('forced=false') && target.text.includes('loaded=false') && target.text.includes('forced=false')) {
      sourceChunk = candidate; targetChunk = candidate + 2; break;
    }
  }
  assert(sourceChunk !== undefined, 'QA requires an observed loaded source and absent destination within one bounded leg');
  const farStart = sourceChunk * 16 + 14.5, farGoal = targetChunk * 16 + 2.5;
  await owner.command(`animus create ${farId} minecraft:player world ${farStart} 100 8.5 movementqa Unloaded Guard`, `Created dormant ${farId}`);
  await owner.command(`animus spawn ${farId}`, `Spawn requested for ${farId}`);
  const sourceBefore = await owner.command(`animusqa motion chunk ${sourceChunk} 0 status`, 'QA chunk');
  assert(sourceBefore.text.includes('loaded=true') && sourceBefore.text.includes('forced=false'), 'Only the destination may be unavailable in this case');
  await owner.route({ x: farGoal, y: 100, z: 8.5 }, 'fly', 2, farId);
  const suspended = await owner.until(s => s.state === 'suspended', farId);
  await delay(2200); const suspendedAgain = await owner.status(farId);
  assert.equal(suspendedAgain.state, 'suspended'); near(suspended.position, suspendedAgain.position);
  const chunk = await owner.command(`animusqa motion chunk ${targetChunk} 0 status`, 'QA chunk');
  assert(chunk.text.includes('loaded=false') && chunk.text.includes('forced=false'));
  const sourceAfter = await owner.command(`animusqa motion chunk ${sourceChunk} 0 status`, 'QA chunk');
  assert(sourceAfter.text.includes('loaded=true') && sourceAfter.text.includes('forced=false'));
  assert(suspendedAgain.text.includes('unavailable terrain; no chunk loading requested'));
  await owner.command(`animus delete ${farId}`, 'Deleted;');
  passed('unavailable destination suspends and retries without loading or forcing its chunk', { sourceChunk, targetChunk });

  const cancelFrom = owner.events.length;
  await owner.route({ x: 8.5, y: 61, z: 3.5 });
  owner.send('command', { text: `animus despawn ${id}` });
  await owner.wait(e => e.event === 'chat' && e.text.includes(`Despawn requested for ${id}`), cancelFrom);
  await delay(900);
  assert(!owner.body()); assert(!owner.events.slice(cancelFrom).some(e => e.event === 'chat' && e.text.includes(`QA waypoint npc=${uuid}`)));
  await owner.command(`animus delete ${id}`, 'Deleted;'); live = false;
  passed('despawn invalidates queued planning and prevents late movement or arrival');
} catch (error) { failure = error; console.error(error.stack); }
finally {
  if (owner && !owner.fatal) {
    try {
      if (live) await owner.command(`animus delete ${id}`, 'Deleted;');
      if (existsSync(path.join(dataDir, 'npcs', 'movementqa', `${id}-unloaded.json`))) await owner.command(`animus delete ${id}-unloaded`, 'Deleted;');
      if (uuid) await owner.command(`animusqa forget ${uuid}`, 'QA production forget');
      await owner.command('animusqa motion cleanup', 'QA motion cleanup complete');
      await owner.command('fill -20 61 -20 20 72 20 air', /filled/i);
    } catch (error) { failure ??= error; }
  }
  if (routeOwned && existsSync(routeFile)) unlinkSync(routeFile);
  await Promise.all(clients.map(client => client.close())); wire.end();
  const report = { status: failure ? 'failed' : 'passed', scenario: 'production-navigation', version, port, id, uuid, nativeCodec, checks, resultDir, limitation: 'Native wire and authoritative command/event observations; conservative full-block collision, bounded kinematic movement, no graphical client certification', error: failure?.message };
  writeFileSync(path.join(resultDir, 'result.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify({ event: 'result', ...report }));
}
if (failure) process.exitCode = 1;
