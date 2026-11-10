// A Citizens save with a waypoint route, a Sentinel guard, a Citizens shop and a command NPC goes through the drop-in
// import with preview and apply only: right after apply the NPC walks its route, the guard fights a zombie, the shop
// opens on click and the command runs, then rollback removes all of it. Driven by live-features.mjs.
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const SAVES = `npc:
  '0':
    name: Walker
    uuid: 0a1b2c3d-0000-4000-8000-000000000a00
    traits:
      type: PLAYER
      spawned: true
      location: {world: world, x: -3.5, y: 61.0, z: 12.5, yaw: 0.0, pitch: 0.0}
      waypoints:
        provider: linear
        linear:
          points:
          - location: {world: world, x: -4, y: 60, z: 12}
          - location: {world: world, x: 4, y: 60, z: 12}
  '1':
    name: Warden
    uuid: 0a1b2c3d-0000-4000-8000-000000000a01
    traits:
      type: PLAYER
      spawned: true
      location: {world: world, x: 12.5, y: 61.0, z: -8.5, yaw: 0.0, pitch: 0.0}
      sentinel: {health: 40, damage: 6, range: 12, targets: [MONSTERS], invincible: true}
  '2':
    name: Trader
    uuid: 0a1b2c3d-0000-4000-8000-000000000a02
    traits:
      type: PLAYER
      spawned: true
      location: {world: world, x: 6.5, y: 61.0, z: 4.5, yaw: 0.0, pitch: 0.0}
      shop: {rightClickShop: Harbor}
  '3':
    name: Herald
    uuid: 0a1b2c3d-0000-4000-8000-000000000a03
    traits:
      type: PLAYER
      spawned: true
      location: {world: world, x: -6.5, y: 61.0, z: 4.5, yaw: 0.0, pitch: 0.0}
      commandtrait:
        commands:
          '0': {command: 'say import-command-ran-for-<p>', hand: RIGHT, experienceCost: 1}
`;
const SHOPS = `global:
  Harbor:
    title: Harbor Goods
    type: DEFAULT
    pages:
      '0':
        items:
          '13':
            cost:
              '0': {type: items, items: {'0': {type: EMERALD, amount: 1}}}
            result:
              '0': {type: items, items: {'0': {type: BREAD, amount: 2}}}
            display: {type: BREAD, amount: 2}
`;

/** `t` carries live-features' server, join, check, inspect, summon, window and wait helpers. */
export async function importApply(t) {
  const { server, join, check, data, inspect, click, windowOpened, closeWindow, summon, until, plain, OWNER } = t;
  const imports = path.join(data, 'imports');
  mkdirSync(imports, { recursive: true });
  writeFileSync(path.join(imports, 'saves.yml'), SAVES);
  writeFileSync(path.join(imports, 'shops.yml'), SHOPS);
  const walker = 'citizens-0', warden = 'citizens-1', trader = '0a1b2c3d-0000-4000-8000-000000000a02', herald = '0a1b2c3d-0000-4000-8000-000000000a03';

  await check('import: preview then apply alone turns on everything that converted', async () => {
    const preview = plain(await server.capture('animus import citizens preview', /Next: \/animus import citizens apply|refused|failed/i, 1500));
    assert(/4 NPCs, 4 valid/.test(preview), preview);
    assert(/apply allows citizens in \[behaviors\.actions\] commandNamespaces/.test(preview), preview);
    const applied = plain(await server.capture('animus import citizens apply', /Next: \/animus import citizens review[^\n]*/, 3000));
    assert(/NPCs spawn with their patrols, schedules, combat, riding, shops and builds on/.test(applied), applied);
    assert(/Commands: added citizens/.test(applied), applied);
    assert(!/accept/i.test(applied), applied);
    const listed = plain(await server.capture('animus list', /citizens-3[^\n]*/, 800));
    return [applied.match(/Installed import[^\n]*/)?.[0], applied.match(/Commands:[^\n]*/)?.[0], listed.match(/citizens-0[^\n]*/)?.[0]].join(' / ');
  });

  const owner = await join(OWNER.name, 'survival');
  await owner.tp(0.5, 61, 0.5);

  await check('route: the imported NPC walks its Citizens waypoints after apply', async () => {
    const east = await until(async () => { const n = await inspect(walker); return { ok: n.x > 2.5, x: n.x, z: n.z }; }, 60_000, 'the walker never reached the east point');
    const west = await until(async () => { const n = await inspect(walker); return { ok: n.x < -2.5, x: n.x, z: n.z }; }, 60_000, 'the walker never walked back west');
    return `east x=${east.x.toFixed(1)} z=${east.z.toFixed(1)}, back west x=${west.x.toFixed(1)}`;
  });

  await check('guard: the Sentinel guard attacks a zombie after apply', async () => {
    await owner.tp(8.5, 61, -4.5);
    await server.cmd('difficulty easy', /difficulty|Difficulty/);
    const zombie = await summon(77, 'minecraft:zombie', 15.5, 61, -8.5, 'NoAI:1b,attributes:[{id:"minecraft:max_health",base:200}],Health:200f');
    const health = async () => Number(plain(await server.capture(`data get entity ${zombie} Health`, /has the following entity data|No entity/)).match(/data: ([\d.]+)f/)?.[1] ?? -1);
    const hurt = await until(async () => { const h = await health(); return { ok: h >= 0 && h < 200, h }; }, 30_000, 'the guard never hurt the zombie');
    const runtime = plain((await inspect(warden)).line);
    await server.cmd('kill @e[type=minecraft:zombie]', /Killed|No entity/);
    return `zombie health 200 -> ${hurt.h}; ${runtime.slice(0, 160)}`;
  });

  await check('shop: the imported Citizens shop opens on click after apply', async () => {
    await owner.tp(6.5, 61, 1.5);
    const from = await click(owner, trader);
    const open = await windowOpened(owner, from);
    assert.equal(open.title, 'Harbor Goods', JSON.stringify(open));
    assert.equal(open.items.find(item => item.slot === 13)?.item, 'minecraft:bread', JSON.stringify(open.items));
    await closeWindow(owner, open.container);
    return `${open.menu} "${open.title}" with bread in slot 13`;
  });

  const log = path.join(data, '../../logs/latest.log');
  const logAt = () => existsSync(log) ? readFileSync(log, 'utf8').length : 0;
  const levels = async () => Number(plain(await server.capture(`xp query ${OWNER.name} levels`, /has \d+ experience levels/)).match(/has (\d+) experience levels/)[1]);
  const RAN = /import-command-ran-for-AnimusProofQA/;
  await check('command: the imported command NPC charges its cost, then runs its command, after apply', async () => {
    await owner.tp(-6.5, 61, 1.5);
    await server.cmd(`xp set ${OWNER.name} 3 levels`, /experience levels/);
    const from = logAt();
    await click(owner, herald);
    const ran = await server.waitLog(RAN, from, 10_000);
    assert.equal(await levels(), 2, 'one level charged');
    return `${ran[0]}; 3 -> 2 levels`;
  });
  await check('command: a command the allow-list refuses charges nothing', async () => {
    const config = path.join(data, 'config.toml'), allowed = readFileSync(config, 'utf8');
    const refused = allowed.replace(/^(\s*commandNamespaces\s*=.*)"citizens",?\s*/m, '$1');
    assert.notEqual(refused, allowed, 'citizens is in commandNamespaces');
    let from = logAt();
    writeFileSync(config, refused);
    await server.waitLog(/Hotloaded config\.toml/, from, 15_000);
    from = logAt();
    await click(owner, herald);
    await new Promise(resolve => setTimeout(resolve, 2500));
    const after = await levels();
    const ranAnyway = RAN.test(readFileSync(log, 'utf8').slice(from));
    from = logAt();
    writeFileSync(config, allowed);
    await server.waitLog(/Hotloaded config\.toml/, from, 15_000);
    assert.equal(ranAnyway, false, 'the refused command did not run');
    assert.equal(after, 2, 'no level charged');
    return 'no command and still 2 levels while citizens is off commandNamespaces';
  });

  await owner.close();
  await check('rollback: one command removes the import and the namespace it allowed', async () => {
    const undone = plain(await server.capture('animus import citizens rollback', /Citizens import undone[^\n]*|Rollback stopped[^\n]*/, 3000));
    assert(/Citizens import undone/.test(undone), undone);
    assert(/Removed citizens from \[behaviors\.actions\] commandNamespaces/.test(undone), undone);
    const listed = plain(await server.capture('animus list', /NPCs?[^\n]*|No NPCs[^\n]*/, 800));
    assert(!/citizens-[0-3]\b/.test(listed), listed);
    return undone.match(/Removed citizens[^\n]*/)?.[0];
  });
}
