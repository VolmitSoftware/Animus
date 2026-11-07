#!/usr/bin/env node
// Real combat tests use active persisted NPCs. Preview bodies never damage the world.
import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
import path from 'node:path';
import { NativeClient, prepareNative, parseArgs, delay } from './protocol-client/client.mjs';

const args = parseArgs();
assert(args.has('event-fixture'), 'Combat assertions require the isolated QA event fixture');
const harness = prepareNative(args, import.meta.url, 'combat');
const { version, port, resultDir, nativeCodec, clients, wire } = harness;
const checks = [], id = args.get('id') ?? `combat-${Date.now().toString(36)}`;
assert(typeof id === 'string' && /^combat-[a-z0-9]+$/.test(id), 'Use an isolated synthetic combat ID');
const passed = (name, detail = {}) => { checks.push({ name, ...detail }); console.log(JSON.stringify({ event: 'check', status: 'passed', name, ...detail })); };
const uuid = text => { const match = /entity=([a-f0-9-]{36})/.exec(text); assert(match, text); return match[1]; };
const number = (text, key) => { const match = new RegExp(`(?:^|[ ;])${key}=(-?[0-9.]+)`).exec(text); assert(match, text); return Number(match[1]); };
let owner, created = false, failure;
async function command(text, expected, timeout) { return owner.command(text, expected, timeout); }
async function immediateCommand(text, expected) {
  const from = owner.events.length;
  owner.lastCommand = Date.now();
  owner.send('command', { text });
  return owner.wait(e => e.event === 'chat' && e.text.includes(expected), from);
}
async function observe(entity) { return (await command(`animusqa observe ${entity}`, `QA observed entity=${entity}`)).text; }
async function npcHealth() {
  const from = owner.events.length;
  await command(`animus inspect ${id}`);
  return number((await owner.wait(e => e.event === 'chat' && e.text.replace(/^\[Animus\]: /, '').startsWith(`Runtime ${id}:`) && e.text.includes('health='), from)).text, 'health');
}
async function spawn(type, x, y, z) {
  const receipt = await command(`animusqa spawn ${type} ${x} ${y} ${z}`, `QA spawned type=${type}`);
  return uuid(receipt.text);
}
try {
  owner = new NativeClient(harness, 'AnimusProofQA');
  await owner.ready();
  await command('gamemode creative @s');
  const origin = owner.events.length;
  await command('execute in minecraft:overworld run tp @s 0.0 61.0 0.0 0 0');
  await owner.wait(e => e.event === 'position' && e.x === 0 && e.y === 61 && e.z === 0, origin);
  await command('animus combat status', 'Combat ready');
  await command(`animus create ${id} type=minecraft:player world=world x=0 y=61 z=3 folder=combatqa name="Town Guard"`, `Created dormant ${id}`);
  created = true;
  const from = owner.events.length;
  await command(`animus inspect ${id}`);
  const document = JSON.parse((await owner.wait(e => e.event === 'chat' && e.text.replace(/^\[Animus\]: /, '').startsWith('{') && e.text.includes(`"id":"${id}"`), from)).text.replace(/^\[Animus\]: /, ''));
  const shown = owner.events.length;
  await command(`animus spawn ${id}`, `Spawning ${id}`);
  await owner.wait(e => e.event === 'spawn' && e.uuid === document.uuid, shown, 20_000);
  assert.equal(await npcHealth(), 20);
  passed('combat binds the active persisted virtual NPC', { uuid: document.uuid });

  const cow = await spawn('cow', 0, 61, 5);
  const before = number(await observe(cow), 'health');
  const attackStart = owner.events.length;
  const attack = await command(`animus combat attack ${id} ${cow} damage=2`, `Attack ${id}: APPLIED`);
  assert.equal(number(attack.text, 'applied'), 2);
  const receipt = await owner.wait(e => e.event === 'chat' && e.text.includes(`QA real damage entity=${cow}`), attackStart);
  assert(receipt.text.includes('null-attacker=true') && receipt.text.includes('cancelled=false'));
  const damaged = await observe(cow);
  assert.equal(number(damaged, 'health'), before - 2);
  assert(damaged.includes('null-attacker=true'));
  passed('virtual attacker applies real Bukkit damage without a fabricated entity');

  await command(`animusqa combatcancel ${cow}`, 'QA armed mode=combatcancel');
  await command(`animus combat attack ${id} ${cow} damage=2`, `Attack ${id}: CANCELLED`);
  assert.equal(number(await observe(cow), 'health'), before - 2);
  passed('Animus damage event cancellation preserves real health');

  await command(`animusqa realcancel ${cow}`, 'QA armed mode=realcancel');
  await command(`animus combat attack ${id} ${cow} damage=2`, `Attack ${id}: NO_CHANGE`);
  assert.equal(number(await observe(cow), 'health'), before - 2);
  passed('Bukkit damage event cancellation preserves real health');

  await command(`animusqa combatteleport ${cow}`, 'QA armed mode=combatteleport');
  await command(`animus combat attack ${id} ${cow} damage=2`, `Attack ${id}: OUT_OF_REACH`);
  assert.equal(number(await observe(cow), 'health'), before - 2);
  passed('reentrant target teleport invalidates reach before damage');

  // Keep its entire hitbox beyond the wall while its nearest face remains inside 2.5-block reach.
  await command(`animusqa move ${cow} 0 61 5.8`, 'QA moved');
  await command('fill -1 61 4 1 64 4 stone', /Successfully filled|No blocks were filled/);
  await command(`animus combat attack ${id} ${cow} damage=2`, `Attack ${id}: OBSTRUCTED`);
  assert.equal(number(await observe(cow), 'health'), before - 2);
  await command('fill -1 61 4 1 64 4 air', /Successfully filled|No blocks were filled/);
  passed('solid obstruction blocks real damage');

  await command(`animusqa move ${cow} 8 61 3`, 'QA moved');
  const zombie = await spawn('zombie', 5, 61, 3);
  await command(`animusqa mobcancel ${zombie}`, 'QA armed mode=mobcancel');
  const cancelledStart = owner.events.length;
  await command(`animusqa controlprepare ${id} ${zombie} ${cow}`, 'QA control prepared');
  await immediateCommand(`animusqa replancheck ${zombie}`, 'autonomous=false path-unchanged=true');
  await owner.wait(e => e.event === 'chat' && e.text.includes(`mob=${zombie}`) && e.text.includes('QA mob hit') && e.text.includes('cancelled=true'), cancelledStart, 20_000);
  const restored = await immediateCommand(`animusqa releasecheck ${id} ${zombie}`, `QA restore mob=${zombie}`);
  assert(restored.text.includes('goals=true target=true path=true'));
  assert.equal(await npcHealth(), 20);
  passed('bounded zombie pursuit reaches virtual NPC; cancellation preserves virtual health');
  passed('leased zombie cannot autonomously replan outside Animus budgets');
  passed('mob release restores original goals, target and route');

  await command(`animusqa move ${zombie} 5 61 3`, 'QA moved');
  const mobStart = owner.events.length;
  await command(`animusqa controlprepare ${id} ${zombie} ${cow}`, 'QA control prepared');
  await owner.wait(e => e.event === 'chat' && e.text.includes(`mob=${zombie}`) && e.text.includes('QA mob hit') && e.text.includes('cancelled=false'), mobStart, 20_000);
  await immediateCommand(`animusqa releasecheck ${id} ${zombie}`, `QA restore mob=${zombie}`);
  const virtualHealth = await npcHealth();
  assert(virtualHealth > 0 && virtualHealth < 20);
  passed('existing vanilla mob damages shared authoritative virtual health', { health: virtualHealth });
  await command(`animusqa bridgeclosecheck ${zombie} ${cow}`, 'QA bridge close registration-rejected=true reacquired=true controlled=0 registered=0');
  passed('closed bridge rejects registration and releases the real mob for reacquisition');

  const editStart = owner.events.length;
  await command(`animus edit ${id} {"name":"Renamed Guard"}`, /Saved[^;]*; tx /);
  await owner.wait(e => e.event === 'spawn' && e.uuid === document.uuid, editStart, 20_000);
  assert.equal(await npcHealth(), virtualHealth);
  passed('editing appearance preserves runtime health');

  // Restored vanilla goals may kill the original cow; policy checks need their own living target.
  const policyTarget = await spawn('cow', 0, 61, 5.8);
  await command(`animus edit ${id} {"modules":{"animus:combat":{"enabled":false,"reviewAccepted":false}}}`, /Saved[^;]*; tx /);
  await command(`animus combat attack ${id} ${policyTarget} damage=2`, 'combat is disabled or its migration review has not been accepted');
  await command(`animus edit ${id} {"modules":{"animus:combat":{"enabled":"true","reviewAccepted":true}}}`, /Saved[^;]*; tx /);
  await command(`animus combat attack ${id} ${policyTarget} damage=2`, 'combat is disabled or its migration review has not been accepted');
  passed('unreviewed combat and string booleans cannot bypass the import gate');

  await command(`animus edit ${id} {"modules":{"animus:combat":{"enabled":true,"reviewAccepted":true}}}`, /Saved[^;]*; tx /);
  await delay(250);
  await command(`animusqa move ${zombie} 5 61 3`, 'QA moved');
  await command(`animus combat mob ${id} ${zombie}`, 'Mob control started');
  const removeStart = owner.events.length;
  owner.send('command', { text: `animus despawn ${id}` });
  await owner.wait(e => e.event === 'chat' && e.text.includes(`Despawn requested for ${id}`), removeStart);
  await delay(200);
  const status = await command('animus combat status', 'Combat ready');
  assert(status.text.includes('manual registrations=0') && status.text.includes('controlled-mobs=0'));
  passed('despawn revokes combat and releases mob ownership');
} catch (error) {
  failure = error;
} finally {
  if (owner && !owner.fatal) {
    try {
      await command(`animus combat mob ${id} stop`);
      await command('fill -1 61 4 1 64 4 air');
      await command('animusqa cleanup', /QA (?:combat cleanup|cleanup combat) complete/);
      if (created) await command(`animus delete ${id}`, /Deleted [^;]*; tx /);
    } catch (error) { failure ??= error; }
  }
  for (const client of clients) await client.close();
  wire.end();
}
const result = { event: 'result', status: failure ? 'failed' : 'passed', id, version, port, checks, nativeCodec, evidence: resultDir,
  ...(failure ? { error: String(failure.message ?? failure) } : {}),
  limitations: ['Explicit manual combat and ordinary-zombie adapter only; no full Sentinel, ranged weapons, WorldGuard policy, Folia ownership, or graphical claims.'] };
writeFileSync(path.join(resultDir, 'result.json'), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result));
if (failure) process.exitCode = 1;
