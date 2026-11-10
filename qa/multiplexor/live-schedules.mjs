// NPC schedules as a viewer sees them: an NPC walks to the place of the current world time and moves on when
// /time set changes it, lies down and puts on its night clothes where it sleeps, takes a place added with
// /animus schedule add, and an NPC Destinations save imports, waits for its review, walks once accepted and rolls
// back. Driven by live-features.mjs (--scenarios schedules).
import assert from 'node:assert/strict';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

/** Entity data index of the pose. */
const POSE = 6;

/** A Citizens save as NPC Destinations writes its trait: two destinations, one of them asleep through the Animations add-on. */
const SAVES = `npc:
  '9':
    uuid: 0e6b7c2a-9d1f-4c55-8a0b-6f2a51c9e009
    name: Miller
    traits:
      type: PLAYER
      spawned: true
      location: {world: world, x: 0.5, y: 61, z: -9.5, yaw: 0, pitch: 0}
      waypoints: {provider: npcdestinations}
      npcdestinations:
        PauseForPlayers: 5
        Locations: []
        OpensGates: true
        OpensWoodDoors: true
        OpensMetalDoors: false
        TeleportOnNoPath: true
        enabledplugins: [Animations]
        Destinations:
          citizens_Swim: true
          '0':
            locationid: 2f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c01
            Location: {world: world, x: 12, y: 61, z: -9, pitch: '0.0', yaw: '0.0'}
            Probability: {ChancePercent: 0, Min_Time: 0, Max_Time: 0}
            TimeOfDay: 1000
            WanderSettings: {Wait_Maximum: 0, Wait_Minimum: 0, Wandering_Region: '', Wandering_Distance: 0.0}
            AliasName: Mill
            WeatherFlag: 0
            PluginSettings: ''
          '1':
            locationid: 2f0c2c52-6f0e-4f43-a3a3-9d1f8f8a0c02
            Location: {world: world, x: -12, y: 61, z: -9, pitch: '0.0', yaw: '90.0'}
            Probability: {ChancePercent: 0, Min_Time: 0, Max_Time: 0}
            TimeOfDay: 13000
            AliasName: Home
            WeatherFlag: 0
            PluginSettings:
              Animations: {Setting: SLEEP, Interval: 500}
`;

/** Runs the schedule checks; `t` carries live-features' server, client and check helpers. */
export async function schedules(t) {
  const { server, join, check, inspect, until, data, OWNER } = t;
  const owner = await join(OWNER.name, 'creative');
  await t.floor(owner);
  const ground = 61;
  await owner.tp(0.5, ground, 4.5);
  const at = (id, x, z, label) => until(async () => {
    const n = await inspect(id);
    return { ok: Math.abs(n.x - x) < 1.2 && Math.abs(n.z - z) < 1.2, x: n.x, z: n.z };
  }, 90_000, label);
  const poses = (body, from) => owner.events.slice(from).filter(e => e.event === 'metadata' && e.id === body.id)
    .flatMap(e => e.fields).filter(field => field.id === POSE).map(field => field.value);

  await check('schedule: an NPC walks between two places as the world time changes', async () => {
    await server.cmd('time set 1000', /time|tick|clock/i);
    await server.cmd(`animus create baker type=minecraft:player world=world x=0.5 y=${ground} z=0.5 folder=live name=Baker`, /Created baker/);
    // The create spawns the NPC; it waits despawned for its schedule, so the body the client follows is the scheduled one.
    await server.cmd('animus despawn baker', /Despawn requested for baker/);
    await server.cmd(`animus edit baker ${JSON.stringify({ modules: { 'animus:schedule': { speed: 4, locations: [
      { name: 'shop', time: 1000, x: 10, y: ground, z: 0 },
      { name: 'bed', time: 13000, x: -10, y: ground, z: 0, yaw: 90, pose: 'sleeping', equipment: { head: { id: 'minecraft:leather_helmet' } } },
    ] } } })}`, /Saved[^;]*; tx /);
    await server.cmd('animus spawn baker', /Spawning baker/);
    const uuid = JSON.parse(readFileSync(path.join(data, 'npcs/live/baker.json'), 'utf8')).uuid;
    const body = await owner.body(uuid);
    const morning = await at('baker', 10.5, 0.5, 'baker reaches the shop at time 1000');
    const night = owner.events.length;
    await server.cmd('time set 13000', /time|tick|clock/i);
    const home = await at('baker', -9.5, 0.5, 'baker walks home at time 13000');
    await until(async () => ({ ok: poses(body, night).includes('SLEEPING') }), 10_000, 'sleeping pose at home');
    await until(async () => ({ ok: owner.events.slice(night).some(e => e.event === 'equipment' && e.id === body.id && e.slots.head === 'minecraft:leather_helmet') }),
      10_000, 'night cap on arrival');
    const dawn = owner.events.length;
    await server.cmd('time set 1000', /time|tick|clock/i);
    await at('baker', 10.5, 0.5, 'baker walks back to the shop the next morning');
    assert(poses(body, dawn).includes('STANDING'), `stands up before leaving: ${poses(body, dawn).join(',')}`);
    return `shop x=${morning.x.toFixed(1)} at 1000; home x=${home.x.toFixed(1)} at 13000 with SLEEPING pose and leather_helmet; STANDING and back at the shop at 1000`;
  });

  await check('schedule: /animus schedule adds, lists and turns off a place', async () => {
    await server.cmd(`animus schedule add baker noon 0 ${ground} 8 name=well`, /Added well at 6000/);
    const list = await server.capture('animus schedule list baker', /well/);
    assert(['shop', 'bed', 'well'].every(word => list.includes(word)), list.slice(0, 400));
    await server.cmd('time set 6000', /time|tick|clock/i);
    const well = await at('baker', 0.5, 8.5, 'baker goes to the well at noon');
    await server.cmd('animus schedule off baker', /schedule is off/);
    await server.cmd('animus delete baker', /Deleted/);
    return `added at 6000, listed with shop and bed, walked to x=${well.x.toFixed(1)} z=${well.z.toFixed(1)}, turned off`;
  });

  await check('schedule: an NPC Destinations save imports, waits for review, walks once accepted and rolls back', async () => {
    await server.cmd('time set 1000', /time|tick|clock/i);
    mkdirSync(path.join(data, 'imports'), { recursive: true });
    writeFileSync(path.join(data, 'imports', 'saves.yml'), SAVES);
    const preview = await server.capture('animus import citizens preview', /Next: \/animus import citizens apply/, 500);
    assert(/schedules 1/.test(preview), preview.slice(0, 600));
    await server.cmd('animus import citizens apply', /Installed import/);
    const review = await server.capture('animus import citizens review', /schedules 1/, 300);
    await server.cmd('animus import citizens accept spawn', /Accepted/);
    await server.cmd('animus import citizens accept schedules', /Accepted/);
    const mill = await at('citizens-9', 12.5, -8.5, 'the imported miller walks to the mill at 1000');
    await server.cmd('time set 13000', /time|tick|clock/i);
    const home = await at('citizens-9', -11.5, -8.5, 'the imported miller walks home at 13000');
    const undone = await server.capture('animus import citizens rollback', /Citizens import undone|Rolled back/, 500);
    await until(async () => {
      const text = await server.capture('animus list', /NPC|npcs|No NPCs/i, 300);
      return { ok: !text.includes('citizens-9') };
    }, 20_000, 'the rollback removes the imported NPC');
    return `preview counted schedules 1; review: ${review.match(/Waiting[^\n]*/)?.[0] ?? 'listed'}; mill x=${mill.x.toFixed(1)}, home x=${home.x.toFixed(1)}; ${undone.match(/Citizens import undone[^\n]*/)?.[0] ?? 'rolled back'}`;
  });
  await owner.close();
}
