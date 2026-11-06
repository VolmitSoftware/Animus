// What a player sees of a fight and a posed armor stand: a melee NPC swings on every hit, an archer holds its bow drawn,
// and an imported armor stand without look-close never turns toward the player. Driven by live-features.mjs.
import assert from 'node:assert/strict';
import { delay } from '../protocol-client/client.mjs';

/** Living entity flags (hand in use) are entity data index 8. */
const LIVING_FLAGS = 8;

/** Runs the swing, bow draw and armor stand checks; `t` carries live-features' server, client and check helpers. */
export async function swingLook(t) {
  const { server, join, check, combatNpc, summon, sturdy, plain, folia, OWNER } = t;
  await server.cmd('difficulty easy', /difficulty/i);
  await server.cmd('gamerule minecraft:spawn_mobs false', /spawn_mobs/).catch(() => {});
  const owner = await join(OWNER.name, 'creative');
  const ground = folia ? Math.round(owner.position.y) : 61;
  const sx = folia ? Math.floor(owner.position.x) : -30, sz = folia ? Math.floor(owner.position.z) : 10;
  const at = (dx, dz) => [sx + dx + .5, ground, sz + dz + .5];
  await owner.tp(...at(0, 0));

  await check('swing: a melee NPC swings its arm on every hit', async () => {
    // A husk does not burn in daylight, so every drop in its health is a hit.
    const husk = await summon(61, 'minecraft:husk', ...at(2, 8), sturdy(400));
    const uuid = await server.npc('sfighter', 'minecraft:player', ...at(0, 8), 'Fighter', combatNpc({
      targets: { entityTypes: ['minecraft:husk'] }, targeting: { scanRange: 12, scanIntervalTicks: 5, lineOfSight: 'ignored' },
      melee: { damage: 1, reach: 3, cooldownTicks: 20 }, pursuit: { closeChase: false },
    }));
    const body = await owner.body(uuid);
    const health = async () => Number(plain(await server.capture(`data get entity ${husk} Health`, /has the following entity data: [^\n]*/)).match(/entity data: ([\d.]+)f/)[1]);
    const from = owner.events.length;
    let hits = 0, last = await health();
    for (let i = 0; i < 25; i++) {
      await delay(200);
      const now = await health();
      if (now < last) hits++;
      last = now;
    }
    const swings = owner.events.slice(from).filter(e => e.event === 'animate' && e.id === body.id && e.action === 0).length;
    await server.cmd('animus delete sfighter', /Deleted/);
    await server.cmd('kill @e[type=minecraft:husk]', /Killed|No entity/);
    assert(hits >= 3, `only ${hits} hits while sampling`);
    assert(swings >= hits, `${swings} swings for ${hits} hits`);
    return `${swings} arm swings for ${hits} hits while sampling`;
  });

  await check('swing: an archer holds its bow drawn and lowers it after the fight', async () => {
    const zombie = await summon(62, 'minecraft:zombie', ...at(-6, 16), sturdy(400));
    const uuid = await server.npc('sarcher', 'minecraft:player', ...at(-6, 4), 'Archer', {
      ...combatNpc({
        targets: { entityTypes: ['minecraft:zombie'] }, targeting: { scanRange: 20, scanIntervalTicks: 5, lineOfSight: 'ignored' },
        melee: { enabled: false }, pursuit: { closeChase: false, rangedChase: false },
        ranged: { enabled: true, weapon: 'arrow', damage: 1, range: 20, cooldownTicks: 20, velocity: 1.6, inaccuracy: 0 },
      }),
    });
    const body = await owner.body(uuid);
    const flag = value => e => e.event === 'metadata' && e.id === body.id && e.fields.some(f => f.id === LIVING_FLAGS && f.value === value);
    const drawn = await owner.wait(flag(1), 0, 10000);
    await server.cmd(`kill ${zombie}`, /Killed/);
    const lowered = await owner.wait(flag(0), drawn.sequence + 1, 10000).finally(() => server.cmd('animus delete sarcher', /Deleted/));
    return `drawn at event ${drawn.sequence}, lowered at ${lowered.sequence}`;
  });

  await check('look: an imported armor stand without look-close never turns toward the player', async () => {
    const uuid = await server.npc('sstand', 'minecraft:armor_stand', ...at(6, 4), 'Statue', {
      armorStand: { arms: true, head: [20, 30, 0], rightArm: [-60, 0, 10] },
      modules: { 'animus:look-close': { enabled: false, range: 10, lineOfSight: false, whileNavigating: false } },
    });
    const body = await owner.body(uuid);
    // The spawn itself sends the stand's facing once; only later turns count.
    await delay(1000);
    const from = owner.events.length;
    for (const [dx, dz] of [[6, 7], [9, 4], [6, 1], [3, 4], [8, 6]]) { await owner.tp(...at(dx, dz)); await delay(800); }
    const turns = owner.events.slice(from).filter(e => (e.event === 'head' || e.event === 'rotation') && e.id === body.id);
    await server.cmd('animus delete sstand', /Deleted/);
    assert.equal(turns.length, 0, `turned: ${JSON.stringify(turns.slice(0, 3))}`);
    return `no head or body rotation for ${body.id} while the player walked around it`;
  });
  await owner.close();
  await server.cmd('difficulty peaceful', /difficulty/i);
}
