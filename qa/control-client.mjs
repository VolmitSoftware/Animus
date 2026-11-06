#!/usr/bin/env node
// Exact native input driving an explicitly unmounted, viewer-private proof body.
import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
import path from 'node:path';
import { NativeClient, prepareNative, parseArgs, delay } from './protocol-client/client.mjs';
const args = parseArgs(), harness = prepareNative(args, import.meta.url, 'control');
const { version, port, resultDir, nativeCodec, clients, wire } = harness;
const checks = [];
const passed = (name, detail = {}) => { checks.push({ name, ...detail }); console.log(JSON.stringify({ event: 'check', status: 'passed', name, ...detail })); };
const fields = text => Object.fromEntries([...text.matchAll(/([\w-]+)=([^ |;]+)/g)].map(match => [match[1], match[2]]));
const point = value => ({ x: Number(value.x), y: Number(value.y), z: Number(value.z) });
const distance = (a, b) => Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z);
const near = (a, b, tolerance = 0.00001) => assert(Math.abs(a - b) <= tolerance, `${a} differs from ${b}`);
class Client extends NativeClient {
  constructor(name) { super(harness, name); }
  async origin() {
    const from = this.events.length;
    await this.command('execute in minecraft:overworld run tp @s 0.0 61.0 0.0 0 0', `Teleported ${this.username}`);
    if (this.position?.x !== 0 || this.position?.y !== 61 || this.position?.z !== 0)
      await this.wait(e => e.event === 'position' && e.x === 0 && e.y === 61 && e.z === 0, from);
    await delay(150);
  }
  async keys(keys = {}, repeat = 1) {
    const from = this.events.length;
    this.send('input', { ...keys, repeat });
    return this.wait(e => e.event === 'input' && e.packets === repeat, from);
  }
  async heartbeat(enabled) {
    const from = this.events.length; this.send('heartbeat', { enabled });
    await this.wait(e => e.event === 'heartbeat' && e.enabled === enabled, from);
  }
  async status() {
    const reply = await this.command('animus proof control status', /^Proof control (?:id=|idle)/);
    if (reply.text.startsWith('Proof control idle')) return { idle: true, text: reply.text };
    const f = fields(reply.text);
    return { ...f, ...point(f), id: Number(f.id), tick: Number(f.tick), steps: Number(f.steps), sequence: Number(f['input-sequence']) };
  }
  async clear() { await this.command('animus proof clear', 'Proof cleared'); }
  async body() {
    const from = this.events.length;
    const reply = await this.command('animus proof spawn minecraft:player', 'Proof spawned id=');
    const f = fields(reply.text), body = { ...point(f), id: Number(f.id), uuid: f.uuid };
    assert.equal(f['world-registered'], '0');
    const packet = await this.wait(e => e.event === 'spawn' && e.id === body.id && e.uuid === body.uuid, from);
    assert.equal(packet.type, 'minecraft:player'); return body;
  }
  async start(body) {
    const receipt = await this.command(`animus proof control start ${body.id}`, 'Proof control started id=');
    const f = fields(receipt.text);
    assert.equal(Number(f.id), body.id); assert.equal(Number(f.speed), 2); assert.equal(Number(f.leash), 8);
    assert(receipt.text.includes('unmounted remote control') && receipt.text.includes('awaiting fresh input'));
    return f.generation;
  }
  async stop() { await this.command('animus proof control stop', 'Proof control stopped reason='); }
  async fresh() {
    await this.clear(); await this.keys();
    const body = await this.body(); body.generation = await this.start(body);
    const status = await this.status(); assert.equal(status.generation, body.generation); assert.equal(status.steps, 0); assert.equal(status.sequence, 0);
    return { body, status };
  }
  assertMotion(before, after) {
    assert.equal(after.generation, before.generation);
    assert(after.tick >= before.tick && after.steps >= before.steps);
    assert(after.steps - before.steps <= after.tick - before.tick, 'Input traffic caused more than one step per owner tick');
    assert(distance(before, after) <= (after.steps - before.steps) * 0.1 + 0.00001, 'Control exceeds its declared movement bound');
    near(before.y, after.y);
  }
  async release() { await this.keys(); await delay(150); return this.status(); }
  async stationary(before, duration = 250) {
    await delay(duration); const after = await this.status(); near(distance(before, after), 0); assert.equal(after.steps, before.steps); return after;
  }
  async until(test, timeout = 12000) {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      const status = await this.status();
      if (test(status)) return status;
      assert.notEqual(status.state, 'STOPPED', `Control stopped before expected state: ${JSON.stringify(status)}`);
      await delay(75);
    }
    throw new Error('Timed out waiting for authoritative control progress');
  }
}
let owner, peer, failure, wall = false, hole = false, peerOperator = false;
try {
  owner = new Client('AnimusProofQA'); await owner.ready(); await owner.command('gamemode creative @s'); await owner.origin();
  await owner.command('animus proof status', 'Packet proof ready');
  peer = new Client('AnimusProofPeer'); await peer.ready();
  await peer.command('animus proof control status', 'You do not have permission');
  await owner.clear(); await owner.keys({ forward: true });
  let body = await owner.body(); const firstGeneration = await owner.start(body);
  let before = await owner.status(); assert.equal(before.state, 'WAITING_INPUT'); assert.equal(before.steps, 0); assert.equal(before.sequence, 0);
  before = await owner.stationary(before);
  passed('control requires a fresh native input after its generation starts');

  await owner.keys({ forward: true });
  let after = await owner.until(value => value.steps - before.steps >= 6, 5000); owner.assertMotion(before, after);
  assert.equal(after.sequence, 1, 'Tick heartbeats must not synthesize additional key inputs');
  assert(after.steps - before.steps >= 4); assert(after.z > before.z + 0.3); near(after.x, before.x);
  const moved = owner.events.filter(e => e.event === 'move' && e.id === body.id);
  assert(moved.length > 1, 'Held input must produce multiple native movement packets');
  passed('one held-key packet drives bounded movement while tick heartbeats continue', { steps: after.steps - before.steps, ticks: after.tick - before.tick });
  before = await owner.release(); await owner.stationary(before);
  assert.equal(before.sequence, 2);
  await owner.wait(e => e.event === 'move' && e.id === body.id && distance(e, before) < .00001);
  passed('an empty native input releases held movement');

  await peer.keys({ forward: true }); await delay(250); await owner.stationary(before);
  assert(!peer.events.some(e => e.event === 'spawn' && e.uuid === body.uuid));
  passed('another viewer input cannot move or reveal the private controlled body');
  await owner.command('op AnimusProofPeer', /operator|already/i); peerOperator = true;
  await peer.body();
  await peer.command(`animus proof control start ${body.id}`, 'Proof body is not active for this viewer');
  assert((await peer.status()).idle);
  await peer.clear(); await owner.command('deop AnimusProofPeer', /operator|already/i); peerOperator = false;
  await owner.stationary(before);
  passed('another administrator cannot acquire a foreign viewer body by its wire ID');

  ({ body, status: before } = await owner.fresh());
  await owner.keys({ forward: true, right: true }); after = await owner.until(value => value.steps >= 6, 5000); owner.assertMotion(before, after);
  assert(after.x < before.x && after.z > before.z, 'Forward/right at authoritative yaw0 must move toward negative X and positive Z');
  assert(after.steps >= 4); near(Math.abs(after.x - before.x), Math.abs(after.z - before.z), 0.0001);
  assert(distance(before, after) > 0.3);
  passed('diagonal input is normalized to the same per-step speed');
  before = await owner.release();
  await owner.keys({ forward: true, backward: true, left: true, right: true }); await owner.stationary(before);
  passed('opposing direction flags cancel');

  ({ body, status: before } = await owner.fresh());
  await owner.keys({ forward: true, jump: true, sprint: true }); after = await owner.until(value => value.steps >= 6, 5000); owner.assertMotion(before, after);
  assert(after.steps >= 4); near(after.x, before.x); near(after.z - before.z, (after.steps - before.steps) * 0.1);
  passed('jump and sprint cannot add vertical movement or speed to flat remote control');

  ({ body, status: before } = await owner.fresh());
  await owner.keys({ forward: true }, 64); after = await owner.until(value => value.steps > 0, 5000); owner.assertMotion(before, after);
  assert(after.state !== 'STOPPED'); assert(after.steps > 0);
  passed('a bounded input burst coalesces without extra simulation steps', { packets: 64, steps: after.steps, ticks: after.tick });
  await delay(1100); before = await owner.status();
  const floodedFrom = owner.events.length;
  await owner.keys({ forward: true }, 96);
  await owner.wait(e => e.event === 'chat' && e.text.includes('Proof control stopped reason=input-rate'), floodedFrom);
  after = await owner.status(); owner.assertMotion(before, after); assert.equal(after.state, 'STOPPED'); assert.equal(after.reason, 'input-rate');
  await owner.stationary(after);
  passed('excess input rate closes control instead of accelerating it');

  ({ body, status: before } = await owner.fresh());
  const shiftedFrom = owner.events.length;
  owner.send('input', { shift: true }); owner.send('input', {});
  await owner.wait(e => e.event === 'chat' && e.text.includes('Proof control stopped reason=shift'), shiftedFrom);
  after = await owner.status(); assert.equal(after.state, 'STOPPED'); assert.equal(after.reason, 'shift'); await owner.stationary(after);
  passed('shift release is latched across a following empty input');

  ({ body, status: before } = await owner.fresh());
  await owner.command('fill -2 61 5 2 64 5 stone', /Successfully filled|No blocks were filled/); wall = true;
  await owner.keys({ forward: true }); after = await owner.until(value => value.state === 'BLOCKED'); owner.assertMotion(before, after);
  assert.equal(after.state, 'BLOCKED'); assert(after.z <= 4.70001 && after.z > 3.5); await owner.stationary(after);
  passed('live wall collision prevents entry into solid terrain', { z: after.z });
  await owner.clear(); await owner.command('fill -2 61 5 2 64 5 air', /Successfully filled|No blocks were filled/); wall = false;

  ({ body, status: before } = await owner.fresh());
  await owner.command('fill -2 60 5 2 60 5 air', /Successfully filled|No blocks were filled/); hole = true;
  await owner.keys({ forward: true }); after = await owner.until(value => value.state === 'BLOCKED'); owner.assertMotion(before, after);
  // Support is ground under any part of the 0.6-wide body, so it may overhang the hole but never leave the edge block.
  assert.equal(after.state, 'BLOCKED'); assert(after.z > 4.7 && after.z < 5.3 && after.y === 61, JSON.stringify(after)); await owner.stationary(after);
  passed('flat control refuses unsupported ground instead of walking into a hole', { z: after.z });
  await owner.clear(); await owner.command('fill -2 60 5 2 60 5 stone', /Successfully filled|No blocks were filled/); hole = false;

  ({ body, status: before } = await owner.fresh());
  await owner.keys({ forward: true }); after = await owner.until(value => value.state === 'LEASH'); owner.assertMotion(before, after);
  assert(distance(body, after) <= 8.00001 && distance(body, after) >= 7.5); assert.equal(after.state, 'LEASH');
  await owner.stationary(after);
  passed('held input survives the liveness window but remains inside the eight-block leash');

  ({ body, status: before } = await owner.fresh());
  await owner.keys({ forward: true }); await owner.heartbeat(false);
  const staleFrom = owner.events.length;
  await owner.wait(e => e.event === 'chat' && e.text.includes('Proof control stopped reason=connection-stale'), staleFrom, 5000);
  after = await owner.status(); assert.equal(after.state, 'STOPPED'); assert.equal(after.reason, 'connection-stale');
  await owner.heartbeat(true); await owner.keys(); await owner.stationary(after);
  passed('missing client tick heartbeats expire the lease without retaining held input');

  ({ body, status: before } = await owner.fresh());
  await owner.keys({ forward: true }); await delay(200); await owner.stop();
  const stopped = await owner.status(), generation = await owner.start(body);
  assert.notEqual(generation, stopped.generation); assert.notEqual(generation, firstGeneration);
  after = await owner.status(); assert.equal(after.steps, 0); assert.equal(after.sequence, 0); assert.equal(after.state, 'WAITING_INPUT'); await owner.stationary(after);
  passed('restarting control creates a new generation with no inherited key latch');
  const clearFrom = owner.events.length;
  owner.send('input', { forward: true, repeat: 16 }); owner.send('command', { text: 'animus proof clear' });
  await owner.wait(e => e.event === 'chat' && e.text.includes('Proof cleared'), clearFrom);
  await owner.wait(e => e.event === 'remove' && e.ids.includes(body.id), clearFrom);
  assert((await owner.status()).idle);
  body = await owner.body(); await owner.start(body); before = await owner.status(); assert.equal(before.steps, 0); await owner.stationary(before);
  passed('clearing pending input removes the old body and cannot seed a replacement control');

  await owner.keys({ forward: true }); await delay(200); const oldUuid = body.uuid;
  await owner.close(); await delay(300);
  owner = new Client('AnimusProofQA'); await owner.ready(); await owner.origin();
  assert((await owner.status()).idle);
  const status = await owner.command('animus proof status', 'Packet proof ready'); assert(status.text.includes('viewer bodies=0'));
  await owner.keys({ forward: true }); await delay(300);
  assert(!owner.events.some(e => e.event === 'spawn' && e.uuid === oldUuid));
  passed('disconnect and reconnect discard control generation and private presentation');
} catch (error) { failure = error; console.error(error.stack); }
finally {
  if (owner && !owner.fatal && !owner.closing) {
    try {
      await owner.heartbeat(true); await owner.keys(); await owner.clear();
      if (wall) await owner.command('fill -2 61 5 2 64 5 air', /Successfully filled|No blocks were filled/);
      if (hole) await owner.command('fill -2 60 5 2 60 5 stone', /Successfully filled|No blocks were filled/);
      if (peerOperator) await owner.command('deop AnimusProofPeer', /operator|already/i);
    } catch (error) { failure ??= error; }
  }
  await Promise.all(clients.map(client => client.close())); wire.end();
}
const report = { event: 'result', status: failure ? 'failed' : 'passed', scenario: 'unmounted-private-remote-control', version, port, nativeCodec, checks, evidence: resultDir,
  limitation: 'Exact input and server-authoritative flat movement proof; no mounted riding, camera possession, production NPC control, or graphical claim', ...(failure ? { error: failure.message } : {}) };
writeFileSync(path.join(resultDir, 'result.json'), JSON.stringify(report, null, 2) + '\n'); console.log(JSON.stringify(report));
if (failure) process.exitCode = 1;
