#!/usr/bin/env node
// Long route legs on generated terrain, observed through exact native packets. A patrol whose points lie 100+ blocks
// apart, one leg across a river, must reach every point; a player follows it so the patrol stays awake and its
// terrain stays loaded. Only an isolated QA instance is used.
import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
import path from 'node:path';
import { NativeClient, prepareNative, parseArgs, delay } from './protocol-client/client.mjs';

const args = parseArgs(), harness = prepareNative(args, import.meta.url, 'longlegs');
const id = args.get('id') ?? `longlegs-${Date.now().toString(36)}`;
assert(/^longlegs-[a-z0-9]+$/.test(id));
const legTimeout = Number(args.get('leg-timeout') ?? 300) * 1000;
const checks = [];
const passed = (name, detail = {}) => { checks.push({ name, ...detail }); console.log(JSON.stringify({ event: 'check', status: 'passed', name, ...detail })); };
const fields = text => Object.fromEntries([...text.matchAll(/([\w-]+)=([^ ;|]+)/g)].map(match => [match[1], match[2]]));
const vector = (text, label) => {
  const m = text.match(new RegExp(`${label}=Vec3\\[x=([^,]+), y=([^,]+), z=([^\\]]+)\\]`));
  return m && { x: Number(m[1]), y: Number(m[2]), z: Number(m[3]) };
};
const horizontal = (a, b) => Math.hypot(a.x - b.x, a.z - b.z);
let owner, uuid, live = false;

class Client extends NativeClient {
  constructor(name) { super(harness, name); }
  async status() {
    const e = await this.command(`animus navigation ${id} status`, `Navigation ${id}:`);
    const f = fields(e.text);
    return { text: e.text, state: e.text.slice(e.text.indexOf(': ') + 2).split(';')[0], position: vector(e.text, 'position'), arrivals: Number(f.arrivals) };
  }
  /** The surface a player lands on at x z, or null over water or lava. */
  async surface(x, z) {
    const from = this.events.length;
    const reply = await this.command(`spreadplayers ${x} ${z} 0 1 false @s`, /Spread|Could not spread/);
    if (/Could not/.test(reply.text)) return null;
    const at = await this.wait(e => e.event === 'position' && Math.abs(e.x - (x + .5)) < 1.01 && Math.abs(e.z - (z + .5)) < 1.01, from, 10_000);
    const point = { x: Math.floor(at.x) + .5, y: Math.round(at.y), z: Math.floor(at.z) + .5 };
    // Players land on tree tops; an NPC route point belongs on the ground.
    const below = `${Math.floor(point.x)} ${point.y - 1} ${Math.floor(point.z)}`;
    for (const tag of ['#minecraft:leaves', '#minecraft:logs']) {
      const test = await this.command(`execute if block ${below} ${tag}`, /Test (passed|failed)/);
      if (test.text.includes('passed')) return null;
    }
    return point;
  }
  async water(x, z) {
    const reply = await this.command(`execute if block ${x} 62 ${z} minecraft:water`, /Test (passed|failed)/);
    return reply.text.includes('passed');
  }
}

try {
  owner = new Client('AnimusProofQA');
  const toolchain = await owner.ready();
  await owner.command('gamemode spectator @s');
  // A river crossing by default; --biome minecraft:windswept_hills (or another) walks over hills instead.
  const biome = args.get('biome') ?? 'minecraft:river', crossing = biome === 'minecraft:river';
  const located = await owner.command(`locate biome ${biome}`, new RegExp(`nearest ${biome} is at|Could not find`), 60_000);
  const river = located.text.match(/at \[(-?\d+), [^,]+, (-?\d+)\]/);
  assert(river, located.text);
  const rx = Number(river[1]), rz = Number(river[2]);
  // Land on both sides of the located place along one axis, 60 to 80 blocks out.
  let start, across;
  for (const [ux, uz] of [[1, 0], [0, 1], [1, 1], [1, -1]]) {
    for (const reach of [60, 70, 80]) {
      const a = await owner.surface(rx - ux * reach, rz - uz * reach), b = a && await owner.surface(rx + ux * reach, rz + uz * reach);
      if (a && b && (!crossing || Math.abs(a.y - b.y) < 24)) { start = a; across = b; break; }
    }
    if (start) break;
  }
  assert(start && across, `No land found on both sides of ${biome} near ${rx} ${rz}`);
  let wet = null;
  for (let step = 0; step <= 40 && !wet && crossing; step += 2) {
    const t = .5 + (step - 20) / horizontal(start, across);
    const x = Math.floor(start.x + (across.x - start.x) * t), z = Math.floor(start.z + (across.z - start.z) * t);
    if (await owner.water(x, z)) wet = { x, z };
  }
  assert(wet || !crossing, 'The crossing passes river water');
  // A third point 100+ blocks on, turned a quarter, over whatever hills lie there.
  const dx = across.x - start.x, dz = across.z - start.z, length = Math.hypot(dx, dz);
  let beyond;
  search: for (const reach of [110, 125, 140, 155, 170]) for (const side of [0, 6, -6]) {
    beyond = await owner.surface(Math.round(across.x - dz / length * reach + dx / length * side), Math.round(across.z + dx / length * reach + dz / length * side));
    if (beyond) break search;
  }
  assert(beyond, 'No land for the third point');
  const points = [across, beyond, start];
  const legs = [horizontal(start, across), horizontal(across, beyond), horizontal(beyond, start)];
  assert(legs.every(leg => leg >= 100), `Every leg is 100+ blocks: ${legs}`);
  passed('route chosen on generated terrain', { river: { x: rx, z: rz }, water: wet, points, legs: legs.map(Math.round), protocol: toolchain.protocol });

  await owner.command(`execute in minecraft:overworld run tp @s ${start.x} ${start.y + 6} ${start.z}`, 'Teleported');
  await owner.command(`animus create ${id} minecraft:player world ${start.x} ${start.y} ${start.z} longlegs Long Legs`, `Created dormant ${id}`); live = true;
  const route = { 'animus:navigation': { enabled: true, mode: 'swim', speed: 5, loop: false, waitTicks: 10, points } };
  const edit = `animus edit ${id} ${JSON.stringify({ modules: route })}`;
  assert(edit.length <= 256, `edit command is ${edit.length} characters`);
  await owner.command(edit, 'Saved;');
  await owner.command(`animus spawn ${id}`, `Spawn requested for ${id}`);
  const spawned = await owner.wait(e => e.event === 'spawn' && Math.abs(e.x - start.x) < 1 && Math.abs(e.z - start.z) < 1, 0, 20_000);
  uuid = spawned.uuid;

  const trail = [];
  let arrived = 0, last, began = Date.now(), legBegan = Date.now();
  while (arrived < points.length) {
    await delay(1500);
    const status = await owner.status();
    if (status.position) {
      trail.push({ t: Date.now() - began, ...status.position, state: status.state });
      // Follow above the NPC: the patrol sleeps with no player near, and the player keeps its terrain loaded.
      await owner.command(`tp @s ${status.position.x} ${status.position.y + 8} ${status.position.z}`, 'Teleported');
    }
    assert(!/^(blocked|failed|cancelled|invalid)/.test(status.state), status.text);
    if (status.arrivals > arrived) {
      const point = points[status.arrivals - 1];
      if (status.state === 'arrived' || status.arrivals > arrived) {
        passed(`reached point ${status.arrivals - 1}`, { point, position: status.position, seconds: Math.round((Date.now() - legBegan) / 1000), leg: Math.round(legs[status.arrivals - 1]) });
      }
      arrived = status.arrivals; legBegan = Date.now();
    }
    last = status;
    assert(Date.now() - legBegan < legTimeout, `Leg ${arrived} timed out: ${status.text}`);
  }
  const end = points[points.length - 1];
  assert(horizontal(last.position, end) < .01 && Math.abs(last.position.y - end.y) < 1.01, `Ended at ${JSON.stringify(last.position)}`);
  const ys = trail.map(p => p.y), swum = trail.filter(p => Math.abs(p.y - Math.round(p.y)) > .01 || p.y < 63);
  const moves = owner.events.filter(e => e.event === 'move' && e.uuid === undefined).length;
  passed('every point of the long-leg patrol was reached', { seconds: Math.round((Date.now() - began) / 1000), lowest: Math.min(...ys), highest: Math.max(...ys), samplesInWaterOrBelowLand: swum.length, wireMoves: moves });
  writeFileSync(path.join(harness.resultDir, 'longlegs-report.json'), JSON.stringify({ status: 'passed', id, uuid, points, legs, checks, trail, nativeCodec: harness.nativeCodec }, null, 2));
} catch (error) {
  writeFileSync(path.join(harness.resultDir, 'longlegs-report.json'), JSON.stringify({ status: 'failed', id, error: String(error?.stack ?? error), checks }, null, 2));
  console.log(JSON.stringify({ event: 'check', status: 'failed', error: String(error?.message ?? error) }));
  process.exitCode = 1;
} finally {
  if (live && owner && !owner.fatal) { try { await owner.command(`animus delete ${id}`); await delay(1000); } catch { /* the instance is deleted next */ } }
  for (const client of harness.clients) await client.close();
  harness.wire.end();
}
