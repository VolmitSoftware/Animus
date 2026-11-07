#!/usr/bin/env node
// Production persisted-NPC scenarios, using the shared exact native wire client.
// prepare leaves one spawned definition; verify runs after a managed restart.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync, unlinkSync } from 'node:fs';
import path from 'node:path';
import { NativeClient, ownedQaPath, prepareNative, parseArgs, delay } from './protocol-client/client.mjs';

const args = parseArgs();
const phase = args.get('phase');
assert(['prepare', 'verify'].includes(phase), '--phase prepare|verify is required');
const recoveryOnly = args.has('recovery-only');
assert(!recoveryOnly || phase === 'verify', 'Recovery-only requires a prior prepare state');
const stateFile = path.resolve(args.get('state-file') ?? '');
const recoveryFile = stateFile + '.reload-backup.json';
assert(ownedQaPath(stateFile), 'State file must belong to ignored QA output');
const dataDir = args.get('data-dir') && path.resolve(args.get('data-dir'));
if (dataDir) assert(ownedQaPath(dataDir), 'Only isolated QA data may be edited');
const harness = prepareNative(args, import.meta.url, `runtime-${phase}`);
const { version, port, resultDir, nativeCodec, wire, clients } = harness;
const checks = [];
const passed = (name, detail = {}) => { checks.push({ name, ...detail }); console.log(JSON.stringify({ event: 'check', status: 'passed', name, ...detail })); };
const near = (a, b) => assert(Math.abs(a - b) < 0.00001, `${a} != ${b}`);
const state = phase === 'verify' ? JSON.parse(readFileSync(stateFile, 'utf8')) : { id: args.get('id') ?? `runtime-${Date.now().toString(36)}`, group: 'runtimeqa', version };
assert(/^runtime-[a-z0-9]+$/.test(state.id) && state.group === 'runtimeqa', 'Only owned synthetic runtime IDs are allowed');
assert.equal(state.version, version);
const position = { world: 'world', x: 0, y: 61, z: 3, yaw: 0, pitch: 0 };
class Client extends NativeClient {
  constructor(name) { super(harness, name); }
  current(uuid) {
    const live = new Map();
    for (const event of this.events) {
      if (event.event === 'spawn' && event.uuid === uuid) live.set(event.id, event);
      if (event.event === 'remove') for (const id of event.ids) live.delete(id);
      if (event.event === 'respawn') live.clear();
    }
    assert(live.size <= 1, 'Duplicate live wire bodies for one persisted NPC');
    return [...live.values()][0];
  }
  async body(uuid, from = 0, name = null) {
    const body = await this.wait(e => e.event === 'spawn' && e.uuid === uuid && this.current(uuid)?.id === e.id, from, 20_000);
    assert.equal(body.type, 'minecraft:player');
    if (name) {
      const profile = await this.wait(e => e.event === 'player_info' && e.uuid === uuid && e.actions.includes('ADD_PLAYER'), from);
      assert.equal(profile.name, name);
      assert(profile.sequence < body.sequence, 'Player profile must arrive before spawn');
    }
    return body;
  }
  async teleport(x = 0, y = 61, z = 0, dimension = null) {
    const from = this.events.length;
    const coordinate = value => Number.isInteger(value) ? value.toFixed(1) : String(value);
    await this.command(`${dimension ? `execute in minecraft:${dimension} run ` : ''}tp @s ${coordinate(x)} ${coordinate(y)} ${coordinate(z)} 0 0`, `Teleported ${this.username}`);
    if (this.position?.x !== x || this.position?.y !== y || this.position?.z !== z)
      await this.wait(e => e.event === 'position' && e.x === x && e.y === y && e.z === z, from);
    await delay(200);
  }
  async inspect(id = state.id) {
    const from = this.events.length;
    await this.command(`animus inspect ${id}`);
    const document = await this.wait(e => e.event === 'chat' && e.text.replace(/^\[Animus\]: /, '').startsWith('{') && e.text.includes(`"id":"${id}"`), from);
    const runtime = await this.wait(e => e.event === 'chat' && e.text.replace(/^\[Animus\]: /, '').startsWith(`Runtime ${id}:`), from);
    return { document: JSON.parse(document.text.replace(/^\[Animus\]: /, '')), runtime: runtime.text.replace(/^\[Animus\]: /, '') };
  }
  async look(body) {
    const from = this.events.length;
    this.send('look', { x: body.x, y: body.y + 0.9, z: body.z });
    await this.wait(e => e.event === 'look', from);
    await delay(150);
  }
  async interaction(body, op = 'use', admin = true) {
    await this.look(body);
    const from = this.events.length;
    this.send(op, { id: body.id });
    const action = op === 'attack' ? 'ATTACK' : 'INTERACT';
    if (args.has('event-fixture')) await this.wait(e => e.event === 'chat' && e.text.includes(`QA production interaction id=${state.uuid}`) && e.text.includes(`action=${action}`) && e.text.includes('preview=false world-registered=0'), from);
    if (admin) await this.wait(e => e.event === 'chat' && e.text.includes(`[Animus]: Selected ${state.id}`), from);
    else { await delay(250); assert(!this.events.slice(from).some(e => e.event === 'chat' && e.text.includes('Selected '))); }
  }
}
let owner, peer, failure, invalidBackup, invalidPath;
try {
  owner = new Client('AnimusProofQA');
  const toolchain = await owner.ready();
  await owner.command('gamemode creative @s');
  await owner.teleport(0, 61, 0, 'overworld');
  await owner.command('animus status', 'NPC runtime ready');
  passed('production runtime and exact native protocol ready', { version, protocol: toolchain.protocol, phase });
  if (phase === 'prepare') {
    await owner.command(`animus create ${state.id} type=minecraft:player world=world x=0 y=61 z=3 folder=${state.group} name="Town Guard"`, `Created dormant ${state.id}`);
    const before = await owner.inspect();
    state.uuid = before.document.uuid;
    assert.equal(before.document.spawned, false);
    assert(before.runtime.includes('dormant'));
    await delay(350);
    assert(!owner.current(state.uuid));
    passed('create persists a dormant individually editable definition', { uuid: state.uuid });
    if (args.get('skin')) await owner.command(`animus edit ${state.id} ${JSON.stringify({skin: args.get('skin')})}`, 'Saved;');
    await owner.command(`animus spawn ${state.id}`, `Spawning ${state.id}`);
  }
  let body = owner.current(state.uuid) ?? await owner.body(state.uuid, 0, 'Town Guard');
  const profile = owner.events.find(e => e.event === 'player_info' && e.uuid === state.uuid && e.actions.includes('ADD_PLAYER'));
  assert.equal(profile?.name, 'Town Guard');
  if (args.get('skin-file')) {
    const skin = JSON.parse(readFileSync(args.get('skin-file'), 'utf8'));
    const hash = value => createHash('sha256').update(value, 'utf8').digest('hex');
    assert.equal(profile.textures.length, 1);
    assert.equal(profile.textures[0].valueSha256, hash(skin.textureRaw));
    assert.equal(profile.textures[0].signatureSha256, hash(skin.signature));
    passed('persisted skin reference retains exact signed texture payloads');
  }
  for (const axis of ['x', 'y', 'z']) near(body[axis], position[axis]);
  const active = await owner.inspect();
  assert.equal(active.document.uuid, state.uuid);
  assert.equal(active.document.spawned, true);
  assert(active.runtime.includes('active;') && active.runtime.includes('world-registered=0'));
  passed(phase === 'prepare' ? 'spawn renders persisted UUID without a registered entity' : 'restart automatically restores the same UUID and profile', { uuid: state.uuid, entityId: body.id });
  if (phase === 'verify' && state.recovery) {
    assert(dataDir, 'Recovery requires isolated definition access');
    const source = path.join(dataDir, 'npcs', state.group, `${state.id}.json`);
    const hash = bytes => createHash('sha256').update(bytes).digest('hex');
    assert.equal(hash(readFileSync(source)), state.recovery.invalidSha256, 'Startup must preserve the administrator\'s invalid bytes');
    assert.equal(active.document.revision, state.revision);
    await owner.command('animus status', 'Definition file problems: 1');
    await owner.command('animus validate', state.recovery.field ?? '$.position.x');
    await owner.command(`animus edit ${state.id} ${JSON.stringify({ name: 'Town Guard' })}`, 'Conflict:');
    assert.equal(hash(readFileSync(source)), state.recovery.invalidSha256);
    assert.equal(owner.current(state.uuid)?.id, body.id);
    passed('durable checkpoint restores active UUID and revision while preserving invalid bytes and edit conflicts', { invalidSha256: state.recovery.invalidSha256, revision: state.revision });
    const valid = readFileSync(state.recovery.validBackup);
    assert.equal(hash(valid), state.recovery.validSha256);
    writeFileSync(source, valid);
    await owner.command('animus reload', 'Reloaded; ');
    await owner.command(`animus edit ${state.id} ${JSON.stringify({ name: 'Town Guard' })}`, 'Saved;');
    state.revision = (await owner.inspect()).document.revision;
    assert(state.revision > active.document.revision);
    writeFileSync(stateFile, JSON.stringify(state, null, 2) + '\n');
    body = owner.current(state.uuid) ?? await owner.body(state.uuid);
    passed('restoring exact valid bytes and reloading clears recovery quarantine and permits edits');
    if (args.has('stage-inline-recovery')) {
      const valid = readFileSync(source), before = (await owner.inspect()).document, currentBody = owner.current(state.uuid);
      const validBackup = stateFile + '.inline-valid.json';
      writeFileSync(validBackup, valid);
      const invalid = JSON.parse(valid.toString('utf8'));
      invalid.modules = { ...invalid.modules, 'animus:navigation': { enabled: true, mode: 'walk', speed: 'bad', points: [{ x: 0, y: 61, z: 3 }] } };
      const bad = JSON.stringify(invalid, null, 2) + '\n'; writeFileSync(source, bad);
      const field = '$.modules.animus:navigation';
      await owner.command('animus reload', field);
      assert.deepEqual((await owner.inspect()).document, before);
      assert.equal(owner.current(state.uuid)?.id, currentBody.id);
      assert.equal(readFileSync(source, 'utf8'), bad);
      state.recovery = { field, validBackup, validSha256: hash(valid), invalidSha256: hash(bad) };
      writeFileSync(stateFile + '.inline-invalid.json', bad);
      writeFileSync(stateFile, JSON.stringify(state, null, 2) + '\n');
      passed('invalid inline navigation file is rejected on reload while its active body and last-valid checkpoint remain available');
    }
  }
  if (!recoveryOnly) {
  if (args.has('event-fixture')) await owner.command(`animusqa expect ${state.uuid}`, /QA.*expect/i);
  peer = new Client('AnimusProofPeer');
  await peer.ready();
  const peerFrom = peer.events.length;
  await owner.command('execute in minecraft:overworld run tp AnimusProofPeer 0.0 61.0 0.0', 'Teleported AnimusProofPeer');
  if (peer.position?.x !== 0 || peer.position?.y !== 61 || peer.position?.z !== 0)
    await peer.wait(e => e.event === 'position' && e.x === 0 && e.y === 61 && e.z === 0, peerFrom);
  await delay(250);
  let peerBody = peer.current(state.uuid) ?? await peer.body(state.uuid, peerFrom, 'Town Guard');
  assert.notEqual(body.id, peerBody.id);
  assert.equal(body.uuid, peerBody.uuid);
  assert((await owner.inspect()).runtime.includes('viewers=2'));
  await peer.command('animus spawn nope', 'You do not have permission');
  passed('non-admin sees the same persisted UUID with an independent wire ID', { ownerId: body.id, peerId: peerBody.id });
  if (phase === 'prepare') {
    const proofFrom = owner.events.length, peerProofFrom = peer.events.length;
    const reply = await owner.command('animus proof spawn minecraft:armor_stand', 'Proof spawned id=');
    const fields = Object.fromEntries([...reply.text.matchAll(/([\w-]+)=([^ ]+)/g)].map(match => [match[1], match[2]]));
    const preview = { id: Number(fields.id), uuid: fields.uuid, x: Number(fields.x), y: Number(fields.y), z: Number(fields.z) };
    await owner.wait(e => e.event === 'spawn' && e.id === preview.id && e.uuid === preview.uuid, proofFrom);
    assert(![body.id, peerBody.id].includes(preview.id));
    await delay(250);
    assert(!peer.events.slice(peerProofFrom).some(e => e.event === 'spawn' && e.uuid === preview.uuid));
    if (args.has('event-fixture')) {
      await owner.command('animusqa cancel', 'QA next interaction=cancel');
      const from = owner.events.length;
      await owner.look(preview); owner.send('use', { id: preview.id });
      await owner.wait(e => e.event === 'chat' && e.text.includes('QA cancelled preview interaction'), from);
      assert(!owner.events.slice(from).some(e => e.event === 'chat' && e.text.includes('QA production interaction')));
    }
    const clearedFrom = owner.events.length;
    await owner.command('animus proof clear', 'Proof cleared');
    await owner.wait(e => e.event === 'remove' && e.ids.includes(preview.id), clearedFrom);
    assert.equal(owner.current(state.uuid)?.id, body.id);
    assert.equal(peer.current(state.uuid)?.id, peerBody.id);
    await owner.interaction(body);
    passed('preview and production adapters coexist with isolated IDs, events, and teardown');
    await owner.interaction(body);
    await owner.interaction(body, 'attack');
    passed('production interaction and attack select through non-preview events');
    await peer.interaction(peerBody, 'use', false);
    passed('non-admin interaction reaches integrations without admin selection');
    const forgedFrom = owner.events.length;
    owner.send('use', { id: peerBody.id }); owner.send('attack', { id: 2147483000 });
    await delay(400);
    assert(!owner.events.slice(forgedFrom).some(e => e.event === 'chat' && (/\[Animus\]: Selected |QA production interaction/.test(e.text))));
    passed('foreign-viewer and forged IDs produce no production interaction');
    if (args.has('event-fixture')) {
      await owner.command('animusqa cancel', 'QA next interaction=cancel');
      const from = owner.events.length;
      await owner.look(body); owner.send('use', { id: body.id });
      await owner.wait(e => e.event === 'chat' && e.text.includes('QA cancelled'), from);
      await delay(250);
      assert(!owner.events.slice(from).some(e => e.event === 'chat' && e.text.includes(`Selected ${state.id}`)));
      passed('third-party cancellation prevents production selection');
    }
    for (const [suffix, type, world, name] of [['world', 'minecraft:player', 'animus_missing_world', 'Missing World'], ['type', 'minecraft:not_a_type', 'world', 'Missing Type']]) {
      const id = `${state.id}-${suffix}`;
      await owner.command(`animus create ${id} type=${type} world=${world} x=0 y=61 z=3 folder=${state.group} name="${name}"`, `Created dormant ${id}`);
      const before = (await owner.inspect(id)).document;
      await owner.command(`animus spawn ${id}`, 'Cannot spawn ');
      const after = (await owner.inspect(id)).document;
      assert.deepEqual(after, before, 'Rejected activation must preserve revision and spawned flag');
      await owner.command(`animus delete ${id}`, /Deleted [^;]*; tx /);
    }
    passed('missing world and unsupported type reject before activation commit');
    // Names longer than a player profile name are presentation text, so they activate.
    const longId = `${state.id}-name`;
    await owner.command(`animus create ${longId} type=minecraft:player world=world x=0 y=61 z=3 folder=${state.group} name="Name Longer Than Sixteen"`, `Created dormant ${longId}`);
    const longBefore = (await owner.inspect(longId)).document;
    await owner.command(`animus spawn ${longId}`, `Spawning ${longId}`);
    const longAfter = (await owner.inspect(longId)).document;
    assert.equal(longAfter.spawned, true);
    assert.equal(longAfter.revision, longBefore.revision + 1);
    assert.equal(longAfter.name, 'Name Longer Than Sixteen');
    await owner.command(`animus delete ${longId}`, /Deleted [^;]*; tx /);
    passed('long display name activates and deletes cleanly');
    state.revision = (await owner.inspect()).document.revision;
    writeFileSync(stateFile, JSON.stringify(state, null, 2) + '\n');
    passed('spawned definition retained for managed restart', { stateFile, revision: state.revision });
  } else {
    assert.equal((await owner.inspect()).document.revision, state.revision);
    const old = body, removedFrom = owner.events.length;
    await owner.teleport(128, 61, 0);
    await owner.wait(e => e.event === 'remove' && e.ids.includes(old.id), removedFrom);
    assert(!owner.current(state.uuid));
    assert(peer.current(state.uuid));
    passed('distance and chunk departure remove only that viewer subscription');
    let from = owner.events.length;
    await owner.teleport();
    body = await owner.body(state.uuid, from, 'Town Guard');
    assert.notEqual(body.id, old.id);
    const staleFrom = owner.events.length;
    owner.send('use', { id: old.id });
    await delay(300);
    assert(!owner.events.slice(staleFrom).some(e => e.event === 'chat' && (/\[Animus\]: Selected |QA production interaction/.test(e.text))));
    passed('visibility re-entry allocates fresh IDs and rejects stale IDs');
    from = owner.events.length;
    const priorWorldBody = body;
    await owner.teleport(0, 90, 0, 'the_nether');
    await owner.wait(e => e.event === 'respawn' && e.dimension.includes('the_nether'), from);
    await delay(400);
    assert(!owner.current(state.uuid));
    assert(!owner.events.slice(from).some(e => e.event === 'spawn' && e.uuid === state.uuid));
    from = owner.events.length;
    await owner.teleport(0, 61, 0, 'overworld');
    body = await owner.body(state.uuid, from, 'Town Guard');
    assert.notEqual(body.id, priorWorldBody.id);
    passed('dimension change clears presentation and return resubscribes');
    const disconnectId = peerBody.id;
    await peer.close();
    await delay(400);
    assert((await owner.inspect()).runtime.includes('viewers=1'), 'Logout must release the old viewer subscription');
    peer = new Client('AnimusProofPeer');
    await peer.ready();
    peerBody = peer.current(state.uuid) ?? await peer.body(state.uuid, 0, 'Town Guard');
    assert.notEqual(peerBody.id, disconnectId);
    passed('logout and reconnect preserve identity with a fresh viewer ID');
    from = owner.events.length;
    const peerEditFrom = peer.events.length;
    const oldEditId = body.id;
    await owner.command(`animus edit ${state.id} ${JSON.stringify({ name: 'Garde Étoile', position: { ...position, x: 1 } })}`, 'Saved;');
    await owner.wait(e => e.event === 'remove' && e.ids.includes(oldEditId), from);
    body = await owner.body(state.uuid, from, 'Garde Étoile');
    peerBody = await peer.body(state.uuid, peerEditFrom, 'Garde Étoile');
    near(body.x, 1); near(peerBody.x, 1);
    passed('live name and position edits refresh both viewers with exact Unicode profile');
    if (dataDir) {
      invalidPath = path.join(dataDir, 'npcs', state.group, `${state.id}.json`);
      assert(existsSync(invalidPath));
      invalidBackup = readFileSync(invalidPath);
      writeFileSync(recoveryFile, invalidBackup);
      writeFileSync(invalidPath, '{"schemaVersion":');
      await owner.command('animus reload', state.id);
      await delay(400);
      assert.equal(owner.current(state.uuid)?.id, body.id);
      await owner.interaction(body);
      writeFileSync(invalidPath, invalidBackup); invalidBackup = null; unlinkSync(recoveryFile);
      await owner.command('animus reload', 'Reloaded; ');
      passed('invalid hand edit reports its file and retains the last active definition');
    }
    await owner.command(`animus select ${state.id}`, `Selected ${state.id}`);
    from = owner.events.length;
    const peerDespawnFrom = peer.events.length;
    await owner.command('animus despawn', `Despawn requested for ${state.id}`);
    await owner.wait(e => e.event === 'remove' && e.ids.includes(body.id), from);
    await peer.wait(e => e.event === 'remove' && e.ids.includes(peerBody.id), peerDespawnFrom);
    assert.equal((await owner.inspect()).document.spawned, false);
    passed('selected-ID despawn persists and removes both presentations');
    from = owner.events.length;
    await owner.command('animus spawn', `Spawning ${state.id}`);
    body = await owner.body(state.uuid, from, 'Garde Étoile');
    from = owner.events.length;
    await owner.command('animus delete', /Deleted [^;]*; tx /);
    await owner.wait(e => e.event === 'remove' && e.ids.includes(body.id), from);
    await delay(300);
    assert(!owner.current(state.uuid) && !peer.current(state.uuid));
    if (dataDir) assert(!existsSync(path.join(dataDir, 'npcs', state.group, `${state.id}.json`)));
    passed('selected-ID delete removes the persisted file and all presentations');
    if (args.has('event-fixture')) await owner.command(`animusqa forget ${state.uuid}`, /QA.*forget/i);
  }
  }
} catch (error) { failure = error; console.error(error.stack); }
finally {
  if (invalidBackup && invalidPath) {
    writeFileSync(invalidPath, invalidBackup);
    if (existsSync(recoveryFile)) unlinkSync(recoveryFile);
  }
  await Promise.all(clients.map(client => client.close()));
  wire.end();
  const report = { status: failure ? 'failed' : 'passed', scenario: 'persisted-production-npc', phase, recoveryOnly, version, port, nativeCodec, checks, state, resultDir, limitation: 'Native packet decoding and command assertions; no graphical rendering, movement simulation, or combat certification', error: failure?.message };
  writeFileSync(path.join(resultDir, 'result.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify({ event: 'result', ...report }));
}
if (failure) process.exitCode = 1;
