// Folia: an NPC whose animus:home lies in another region teleports there once that region has the place loaded, and
// warns once while it cannot, not on every check. Driven by live-features.mjs with --project folia.
import assert from 'node:assert/strict';
import { delay } from '../protocol-client/client.mjs';

/** `t` carries live-features' server, join, check and inspect helpers. */
export async function foliaHome(t) {
  const { server, join, check, inspect, until, logText, OWNER, PEER } = t;
  const owner = await join(OWNER.name);
  const ground = Math.round(owner.position.y), sx = Math.floor(owner.position.x), sz = Math.floor(owner.position.z);
  // Far enough that Folia never merges the home's chunks into the spawn region.
  const home = { x: sx + 2000.5, z: sz + 0.5 };
  const warning = /NPC fhome could not teleport home[^\n]*/g;
  const from = logText().length;
  const warnings = () => logText().slice(from).match(warning) ?? [];
  await server.npc('fhome', 'minecraft:villager', sx + 0.5, ground, sz + 4.5, 'Homebody',
    { modules: { 'animus:home': { x: home.x, y: ground, z: home.z, delayTicks: 0, return: 'teleport' } } });
  await check('folia home: an unloaded home in another region warns once, not every second', async () => {
    await until(async () => ({ ok: warnings().length > 0, warnings: warnings() }), 15_000, 'no warning');
    await delay(6000);
    assert.equal(warnings().length, 1, warnings().join('\n'));
    return warnings()[0];
  });
  await check('folia home: the NPC teleports home across regions once the home is loaded', async () => {
    const peer = await join(PEER.name);
    await peer.tp(home.x, ground, home.z + 4);
    const end = await until(async () => { const at = await inspect('fhome'); return { ok: Math.abs(at.x - home.x) < 1.5 && Math.abs(at.z - home.z) < 1.5, line: at.line.slice(0, 240) }; }, 30_000, 'never went home');
    assert.equal(warnings().length, 1, warnings().join('\n'));
    await peer.close();
    return `${end.line.match(/position=[^;]*/)?.[0] ?? end.line}; ${warnings().length} warning`;
  });
  await owner.close();
  await server.cmd('animus delete fhome', /Deleted/);
}
