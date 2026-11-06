// Combat edge cases on Paper and Folia: refused targets, a fight cancelled mid-path, arrow credit and cleanup after a
// despawn, and a respawn away from the post. Driven by live-features.mjs, which passes its server and helpers in.
import assert from 'node:assert/strict';
import { delay } from '../protocol-client/client.mjs';

/** Runs every combat edge check; `t` carries live-features' server, client and check helpers. */
export async function combatEdges(t) {
  const { server, join, check, combatNpc, inspect, combatOf, combatCommand, click, until, summon, sturdy, plain, folia, OWNER } = t;
  await server.cmd('difficulty easy', /difficulty/i);
  await server.cmd('gamerule minecraft:spawn_mobs false', /spawn_mobs/).catch(() => {});
  const owner = await join(OWNER.name, 'creative');
  // Folia skips the platform fills, so there everything stands on the flat world beside spawn; Paper uses its platform.
  const ground = folia ? Math.round(owner.position.y) : 61;
  const sx = folia ? Math.floor(owner.position.x) : -30, sz = folia ? Math.floor(owner.position.z) : 10;
  const at = (dx, dz) => [sx + dx + .5, ground, sz + dz + .5];
  const near = (n, point, within) => Math.hypot(n.x - point[0], n.z - point[2]) < within;
  const helpersLine = async () => plain(await server.capture('animus combat status', /Combat \w+[^\n]*/)).match(/Combat \w+[^\n]*/)[0];
  await owner.tp(...at(0, 0));

  await check('combat edges: an invincible NPC is never a target and refuses player hits', async () => {
    const statue = await server.npc('estatue', 'minecraft:player', ...at(0, 6), 'Statue', combatNpc({ defense: { invincible: true }, targeting: { fightBack: false } }));
    await server.npc('ehunter', 'minecraft:player', ...at(3, 6), 'Hunter', combatNpc({
      targets: { categories: ['npcs'] }, targeting: { scanRange: 16, scanIntervalTicks: 5, lineOfSight: 'ignored' }, melee: { damage: 4, reach: 3, cooldownTicks: 10 },
    }));
    const quiet = [];
    for (let i = 0; i < 6; i++) { quiet.push(await combatOf('ehunter')); await delay(500); }
    await owner.tp(...at(0, 4));
    for (let i = 0; i < 3; i++) { await click(owner, statue, 'attack'); await delay(600); }
    const after = await inspect('estatue');
    await server.cmd('animus delete ehunter', /Deleted/);
    await server.cmd('animus delete estatue', /Deleted/);
    assert(quiet.every(s => /target=none/.test(s)), quiet.find(s => !/target=none/.test(s)));
    assert.equal(after.health, 20, after.line.slice(0, 200));
    return `hunter stayed idle (${quiet.at(-1).slice(0, 80)}); statue health ${after.health} after 3 hits`;
  });

  await check('combat edges: disengaging mid-path stops the chase and the NPC walks back to its post', async () => {
    const post = at(4, 14);
    const zombie = await summon(41, 'minecraft:zombie', ...at(4, 26), sturdy(200));
    await server.npc('echaser', 'minecraft:player', ...post, 'Chaser', combatNpc({
      targets: { entityTypes: ['minecraft:zombie'] }, targeting: { scanRange: 16, scanIntervalTicks: 5, lineOfSight: 'ignored' },
      melee: { damage: 1, reach: 3, cooldownTicks: 20 }, pursuit: { speed: 3, giveUpRange: 24, leashRange: 24, returnToPost: true },
    }));
    const chased = await until(async () => { const n = await inspect('echaser'); return { ok: !near(n, post, 3), x: n.x, z: n.z }; }, 15000, 'never left its post');
    // Folia queues the operation on the NPC's region and reports the result in the log.
    const disengaged = await combatCommand('echaser', 'disengage');
    const dropped = (await until(async () => { const s = await combatOf('echaser'); return { ok: /disengaged/.test(s), s }; }, 5000, 'never disengaged')).s;
    const home = await until(async () => { const n = await inspect('echaser'); return { ok: near(n, post, 1), x: n.x, z: n.z }; }, 20000, 'did not walk back to its post');
    const status = await combatOf('echaser');
    await server.cmd('animus delete echaser', /Deleted/);
    await server.cmd(`kill ${zombie}`, /Killed/).catch(() => {});
    assert(/: (APPLIED|QUEUED)/.test(disengaged), disengaged);
    assert(/target=none/.test(dropped), dropped);
    assert(/target=none/.test(status), status);
    return `left post to ${chased.x.toFixed(1)},${chased.z.toFixed(1)}; ${dropped.slice(0, 80)}; home at ${home.x.toFixed(2)},${home.z.toFixed(2)}`;
  });

  await check('combat edges: arrows credit their archer, hit once each and none stay after a despawn', async () => {
    // The target fights back at whoever hurt it, so its target names the NPC each arrow is credited to.
    await server.npc('edummy', 'minecraft:player', ...at(-10, 20), 'Dummy', combatNpc({
      targeting: { fightBack: true, scanRange: 24, lineOfSight: 'ignored' }, melee: { enabled: false }, pursuit: { closeChase: false, rangedChase: false },
      defense: { maximumHealth: 200, healRateTicks: 0, invulnerableTicks: 0 },
    }));
    const archer = await server.npc('earcher', 'minecraft:player', ...at(-10, 6), 'Archer', combatNpc({
      targets: { npcs: ['edummy'] }, targeting: { scanRange: 24, scanIntervalTicks: 5, lineOfSight: 'ignored' },
      melee: { enabled: false }, pursuit: { closeChase: false, rangedChase: false },
      ranged: { enabled: true, weapon: 'arrow', damage: 3, range: 24, cooldownTicks: 20, velocity: 1.6, inaccuracy: 0 },
    }));
    const samples = [200];
    await until(async () => { const h = (await inspect('edummy')).health; if (h !== samples.at(-1)) samples.push(h); return { ok: samples.length >= 4, samples }; }, 30000, 'fewer than three arrow hits');
    const hits = samples.slice(1).map((h, i) => +(samples[i] - h).toFixed(3));
    const credited = await combatOf('edummy');
    const owners = folia ? 'not read on Folia' : plain(await server.capture('data get entity @e[type=arrow,limit=1] Owner', /following entity data[^\n]*|No entity[^\n]*|Found no[^\n]*/)).slice(0, 120);
    await until(async () => ({ ok: /helpers=[1-9]/.test(await helpersLine()) }), 8000, 'never saw an arrow in flight');
    await server.cmd('animus delete earcher', /Deleted/);
    const after = await until(async () => { const line = await helpersLine(); return { ok: /helpers=0\b/.test(line), line }; }, 5000, 'arrows stayed tracked');
    const left = folia ? null : await t.entityCount('@e[type=arrow]');
    const health = (await inspect('edummy')).health;
    await delay(2000);
    const later = (await inspect('edummy')).health;
    await server.cmd('animus delete edummy', /Deleted/);
    // Hits on samples are 3 each; two arrows landing between samples read as 6.
    assert(hits.every(d => d > 0 && Math.abs(d % 3) < .01), `hits ${hits}`);
    assert(credited.includes(`target=NPC:${archer}`), `hits not credited to the archer: ${credited}`);
    assert(folia || /Found no elements|No entity/.test(owners), `arrow has an owner: ${owners}`);
    assert(left === null || left === 0, `${left} arrows after the despawn`);
    assert.equal(later, health, `the dummy was hit after the archer despawned: ${health} -> ${later}`);
    return `health ${samples.join(' -> ')}; ${credited.slice(0, 90)}; arrow owner: ${owners.slice(0, 50)}; ${after.line.match(/helpers=\d+/)[0]}; arrows left ${left ?? 'n/a'}`;
  });

  await check('combat edges: a respawn away from the post comes back whole and walks home', async () => {
    const post = at(12, -6), elsewhere = at(18, -6);
    const frail = await server.npc('efrail', 'minecraft:player', ...post, 'Frail', combatNpc({
      targeting: { fightBack: false }, defense: { maximumHealth: 2, healRateTicks: 0, invulnerableTicks: 0 }, pursuit: { speed: 3, returnToPost: true },
      respawn: { delayTicks: 40, locations: [{ world: 'world', x: elsewhere[0], y: elsewhere[1], z: elsewhere[2] }] },
    }));
    await owner.tp(...at(12, -3));
    for (let i = 0; i < 4 && !/DEAD/.test(await combatOf('efrail')); i++) { await click(owner, frail, 'attack'); await delay(500); }
    const dead = await combatOf('efrail');
    const seen = [];
    const home = await until(async () => {
      const n = await inspect('efrail');
      if (n.health > 0) seen.push(`${n.x.toFixed(1)},${n.z.toFixed(1)}`);
      return { ok: n.health === 2 && near(n, post, 1), health: n.health, x: n.x, z: n.z };
    }, 20000, 'never respawned and walked home');
    const status = await combatOf('efrail');
    await server.cmd('animus delete efrail', /Deleted/);
    assert(/DEAD/.test(dead), dead);
    assert(seen.some(p => Math.abs(Number(p.split(',')[0]) - post[0]) > 1.5), `never stood away from its post: ${seen.join(' ')}`);
    assert(/target=none/.test(status), status);
    return `${dead.slice(0, 60)}; positions ${[...new Set(seen)].slice(0, 6).join(' ')}; home with health ${home.health}`;
  });
  await owner.close();
  await server.cmd('difficulty peaceful', /difficulty/i);
}
