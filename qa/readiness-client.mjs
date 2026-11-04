#!/usr/bin/env node
// Diagnostic only: distinguish native-client readiness from NPC presentation.
import { writeFileSync } from 'node:fs';
import path from 'node:path';
import { NativeClient, prepareNative, parseArgs, delay } from './protocol-client/client.mjs';
const h = prepareNative(parseArgs(), import.meta.url, 'readiness');
const checks = [];
let failure, owner, peer;
try {
  owner = new NativeClient(h, 'AnimusProofQA'); await owner.ready();
  await owner.command('gamemode creative @s');
  await owner.command('execute in minecraft:overworld run tp @s 0.0 61.0 0.0 0 0', 'Teleported');
  peer = new NativeClient(h, 'AnimusProofPeer'); await peer.ready();
  async function observe(label) {
    await delay(1000);
    const message = await owner.command('animusqa motion viewer AnimusProofPeer', 'QA viewer');
    const batches = peer.events.filter(e => e.event === 'chunk_batch').length;
    const spawns = peer.events.filter(e => e.event === 'spawn').length;
    const entry = { name: label, receipt: message.text, batches, spawns };
    checks.push(entry); console.log(JSON.stringify({ event:'observation', ...entry }));
  }
  await observe('initial stationary login');
  peer.send('look', { x:2, y:61.9, z:3 }); await observe('rotation packet');
  await owner.command('gamemode creative AnimusProofPeer', /game mode|already/i); await observe('creative mode');
  await owner.command('gamemode survival AnimusProofPeer', /game mode|already/i);
  const from = peer.events.length;
  await owner.command('execute in minecraft:overworld run tp AnimusProofPeer 1.0 61.0 0.0 0 0', 'Teleported');
  await peer.wait(e => e.event === 'position' && e.x===1 && e.y===61 && e.z===0, from);
  await observe('changed position teleport');
  await owner.command('execute in minecraft:overworld run tp AnimusProofPeer 0.0 61.0 0.0 0 0', 'Teleported');
  await observe('returned to origin');
} catch (error) { failure=error; console.error(error.stack); }
finally {
  if (owner && !owner.fatal) {
    try { await owner.command('gamemode survival AnimusProofPeer', /game mode|already/i); } catch {}
  }
  await Promise.all(h.clients.map(c=>c.close())); h.wire.end();
  const result = { event:'result', status:failure?'failed':'passed', scenario:'diagnostic-native-readiness', checks, nativeCodec:h.nativeCodec, version:h.version, port:h.port, resultDir:h.resultDir, error:failure?.message, limitation:'Observations only; no NPC or full matrix acceptance claim' };
  writeFileSync(path.join(h.resultDir,'result.json'),JSON.stringify(result,null,2)+'\n'); console.log(JSON.stringify(result));
}
if(failure)process.exitCode=1;
