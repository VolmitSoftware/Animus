// A Citizens save with a shop, a drops trait, a charged and limited command and a CitizensCMD add-on line goes
// through the drop-in import on a real server: the converted shop trades, the NPC drops its items when killed, and
// the command charges its cost, stops at its use limit and runs the add-on line. Driven by live-features.mjs.
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { delay } from '../protocol-client/client.mjs';

const SAVES = `npc:
  '0':
    name: Trader
    uuid: 0a1b2c3d-0000-4000-8000-000000000900
    metadata: {protected: false}
    traits:
      type: PLAYER
      spawned: true
      location: {world: world, x: 6.5, y: 61.0, z: 6.5, yaw: 0.0, pitch: 0.0}
      shop: {rightClickShop: Harbor}
      dropstrait:
        drops:
          '0': {chance: 1.0, drop: {type: GOLD_INGOT, amount: 3}}
  '1':
    name: Toll
    uuid: 0a1b2c3d-0000-4000-8000-000000000901
    traits:
      type: PLAYER
      spawned: true
      location: {world: world, x: -6.5, y: 61.0, z: 6.5, yaw: 0.0, pitch: 0.0}
      commandtrait:
        commands:
          '0':
            command: 'give <p> minecraft:diamond 1'
            hand: RIGHT
            n: 2
            experienceCost: 1
            itemCost:
              '0': {type: EMERALD, amount: 1}
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
const CITIZENSCMD = `npc-data:
  npc-1:
    cooldown: 0
    price: 0.0
    right-click-commands: ['[console] say toll-addon-ran-for-%p%']
    left-click-commands: []
`;

/** `t` carries live-features' server, join, check, window and inventory helpers. */
export async function citizensEconomy(t) {
  const { server, join, check, data, click, windowOpened, clickSlot, closeWindow, itemCount, entityCount, until, plain, OWNER } = t;
  const imports = path.join(data, 'imports');
  mkdirSync(path.join(imports, 'citizenscmd'), { recursive: true });
  writeFileSync(path.join(imports, 'saves.yml'), SAVES);
  writeFileSync(path.join(imports, 'shops.yml'), SHOPS);
  writeFileSync(path.join(imports, 'citizenscmd', 'saves.yml'), CITIZENSCMD);

  await check('import: preview, apply and accept the save with its shops and add-on file', async () => {
    const preview = plain(await server.capture('animus import citizens preview', /Next: \/animus import citizens apply|refused|failed/i, 1500));
    assert(/2 NPCs, 2 valid/.test(preview), preview);
    const applied = plain(await server.capture('animus import citizens apply', /Installed import[^\n]*/, 2500));
    const accepted = plain(await server.capture('animus import citizens accept all', /accept|Accepted[^\n]*/i, 2000));
    const commands = plain(await server.capture('animus import citizens accept commands', /Commands:[^\n]*/, 2000));
    assert(/citizens/.test(commands), commands);
    const listed = plain(await server.capture('animus shop list', /Shops \(\d+\)/, 600));
    assert(/citizens:harbor/.test(listed), listed);
    return [applied.match(/Installed import[^\n]*/)?.[0], accepted.split('\n').find(line => /ccept/.test(line)), commands.match(/Commands:[^\n]*/)?.[0], listed.match(/citizens:harbor[^\n]*/)?.[0]].join(' / ');
  });

  const owner = await join(OWNER.name, 'survival');
  await owner.tp(0.5, 61, 0.5);
  const trader = '0a1b2c3d-0000-4000-8000-000000000900', toll = '0a1b2c3d-0000-4000-8000-000000000901';

  await check('shop: the converted Citizens shop trades an emerald for two bread', async () => {
    await server.cmd(`clear ${OWNER.name}`, /Removed|No items/);
    await server.cmd(`give ${OWNER.name} minecraft:emerald 1`, /Gave/);
    await owner.tp(6.5, 61, 3.5);
    let from = await click(owner, trader);
    const open = await windowOpened(owner, from);
    assert.equal(open.title, 'Harbor Goods', JSON.stringify(open));
    assert.equal(open.items.find(item => item.slot === 13)?.item, 'minecraft:bread', JSON.stringify(open.items));
    from = await clickSlot(owner, open.container, 13);
    const traded = await owner.chat('Traded for', from, 8000);
    const counts = await until(async () => { const bread = await itemCount(OWNER.name, 'minecraft:bread'), emerald = await itemCount(OWNER.name, 'minecraft:emerald'); return { ok: bread === 2 && emerald === 0, bread, emerald }; }, 5000, 'the trade did not move the items');
    await closeWindow(owner, open.container);
    return `${open.menu} "${open.title}"; "${traded.text}" -> bread=${counts.bread} emerald=${counts.emerald}`;
  });

  await check('command: charged per use, refused without the cost, stopped at its use limit, add-on line runs', async () => {
    await server.cmd(`clear ${OWNER.name}`, /Removed|No items/);
    await server.cmd(`xp set ${OWNER.name} 5 levels`, /experience|levels/i);
    await server.cmd(`give ${OWNER.name} minecraft:emerald 1`, /Gave/);
    await owner.tp(-6.5, 61, 3.5);
    const state = async () => ({ diamond: await itemCount(OWNER.name, 'minecraft:diamond'), emerald: await itemCount(OWNER.name, 'minecraft:emerald') });
    const levels = async () => Number(plain(await server.capture(`xp query ${OWNER.name} levels`, /has \d+ experience levels/)).match(/has (\d+) experience levels/)[1]);
    const steps = [];
    let from = await click(owner, toll);
    const addon = await server.waitLog(/toll-addon-ran-for-AnimusProofQA/, 0, 8000);
    let now = await until(async () => { const s = await state(); return { ok: s.diamond === 1 && s.emerald === 0, ...s }; }, 6000, 'first use');
    steps.push(`use 1: diamond=${now.diamond} emerald=${now.emerald} levels=${await levels()}`);
    await delay(1500);
    from = await click(owner, toll);
    const missing = await owner.chat('Missing 1 Emerald', from, 6000);
    steps.push(`no emerald: "${missing.text}" diamond=${(await state()).diamond}`);
    await server.cmd(`give ${OWNER.name} minecraft:emerald 3`, /Gave/);
    await delay(1500);
    from = await click(owner, toll);
    now = await until(async () => { const s = await state(); return { ok: s.diamond === 2 && s.emerald === 2, ...s }; }, 6000, 'second use');
    steps.push(`use 2: diamond=${now.diamond} emerald=${now.emerald} levels=${await levels()}`);
    await delay(1500);
    from = await click(owner, toll);
    const limit = await owner.chat('You have reached the maximum number of uses (2).', from, 6000);
    await delay(800);
    now = await state();
    assert.deepEqual({ diamond: now.diamond, emerald: now.emerald }, { diamond: 2, emerald: 2 }, 'the third use charged or gave something');
    assert.equal(await levels(), 3, 'two uses take one level each');
    steps.push(`use 3: "${limit.text}" diamond=${now.diamond} emerald=${now.emerald}`);
    return `${addon[0]}; ${steps.join('; ')}`;
  });

  await check('drops: killing the vulnerable Citizens NPC drops its drops-trait items', async () => {
    await server.cmd('kill @e[type=minecraft:item]', /Killed|No entity/);
    await server.cmd(`item replace entity ${OWNER.name} weapon.mainhand with minecraft:netherite_sword`, /Replaced/);
    await owner.tp(6.5, 61, 4.5);
    const gold = '@e[type=minecraft:item,nbt={Item:{id:"minecraft:gold_ingot",count:3}}]';
    const dead = await until(async () => {
      await click(owner, trader, 'attack');
      await delay(700);
      const count = await entityCount(gold);
      return { ok: count > 0, count };
    }, 20_000, 'no gold ingots dropped');
    return `gold ingot stacks of 3 on the ground: ${dead.count}`;
  });

  await owner.close();
  for (const npc of ['citizens-0', 'citizens-1']) await server.cmd(`animus delete ${npc}`, /Deleted|Unknown|No NPC/);
}
