// MythicMobs on a real server: an NPC made from a Mythic mob takes its look, the mob's ~onInteract, ~onDamaged and
// ~onTimer skills run with the NPC as caster, mythic:cast-skill casts from the NPC or as the player, and Mythic skill
// files reach NPCs through @AnimusNPC and animusnpc. Needs --extra-plugins with a MythicMobs jar. Driven by live-features.mjs.
import assert from 'node:assert/strict';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { delay } from '../protocol-client/client.mjs';

const MOBS = `AnimusKing:
  Type: SKELETON
  Display: 'Skeleton King'
  Equipment:
  - GOLDEN_HELMET:4
  - BOW HAND
  Options:
    Glowing: true
  Skills:
  - message{m="king-greets <trigger.name>"} @trigger ~onInteract
  - message{m="king-damaged-by <trigger.name>"} @trigger ~onDamaged
  - message{m="king-tick"} @PlayersInRadius{r=24} ~onTimer:40
AnimusModeled:
  Type: ZOMBIE
  Display: 'Modeled'
  Skills:
  - model{mid=qa_model} @self ~onSpawn
AnimusStand:
  Type: ARMOR_STAND
  Display: 'Stand'
  Equipment:
  - IRON_CHESTPLATE CHEST
`;
const SKILLS = `AnimusBless:
  Skills:
  - message{m="blessed-by <caster.name>"} @trigger
  - potion{type=SPEED;duration=200;level=0} @trigger
AnimusSelf:
  Skills:
  - message{m="self-cast-by <caster.name>"} @self
AnimusWho:
  Conditions:
  - animusnpc{npc=king} true
  Skills:
  - message{m="caster-is-king"} @trigger
AnimusMark:
  Skills:
  - setblock{type=GOLD_BLOCK} @AnimusNPC{npc=king}
`;

const cast = (skill, extra = {}) => ({ type: 'action', capability: 'mythic:cast-skill', arguments: { skill, ...extra } });

/** `t` carries live-features' server, join and check helpers. */
export async function mythic(t) {
  const { server, join, check, data, instance, OWNER } = t;
  await check('mythic: support enabled at startup', async () => {
    const found = await server.waitLog(/MythicMobs [^\n]* support enabled/, 0, 5000);
    return found[0];
  });
  await check('mythic: test mobs and skills load', async () => {
    for (const [folder, text] of [['Mobs', MOBS], ['Skills', SKILLS]]) {
      mkdirSync(path.join(instance, 'plugins/MythicMobs', folder), { recursive: true });
      writeFileSync(path.join(instance, 'plugins/MythicMobs', folder, 'animus-qa.yml'), text);
    }
    await server.cmd('mm reload', null);
    for (let i = 0; i < 80; i++) {
      const reply = await server.capture('animus mythic king AnimusKing world=world x=0.5 y=61 z=4.5 folder=live', /Created king[^\n]*|MythicMobs has no mob named[^\n]*|already exists[^\n]*/, 100);
      const line = reply.match(/Created king[^\n]*/);
      if (line) return line[0];
      await delay(250);
    }
    throw new Error('AnimusKing never loaded');
  });
  await check('mythic: the NPC takes the mob look and binding', async () => {
    const king = JSON.parse(readFileSync(path.join(data, 'npcs/live/king.json'), 'utf8'));
    // The mob's own type is the body: every living type is a body now.
    assert.equal(king.type, 'minecraft:skeleton');
    assert.equal(king.name, 'Skeleton King');
    assert.equal(king.glowing, true);
    assert.equal(king.equipment.head.id, 'minecraft:golden_helmet');
    assert.equal(king.equipment.main_hand.id, 'minecraft:bow');
    assert.deepEqual(king.modules['mythic:mob'], { mob: 'AnimusKing' });
    const reply = await server.capture('animus mythic stand AnimusStand world=world x=-3.5 y=61 z=4.5 folder=live', /Created stand[^\n]*/);
    const stand = JSON.parse(readFileSync(path.join(data, 'npcs/live/stand.json'), 'utf8'));
    assert.equal(stand.type, 'minecraft:armor_stand');
    assert.equal(stand.equipment.chest.id, 'minecraft:iron_chestplate');
    return `king=${JSON.stringify({ type: king.type, name: king.name, glowing: king.glowing, equipment: king.equipment, modules: king.modules })} | ${reply.match(/Created stand[^\n]*/)[0]}`;
  });
  await check('mythic: a Model Engine model skill names the model in animus:modelengine', async () => {
    const reply = await server.capture('animus mythic modeled AnimusModeled world=world x=6.5 y=61 z=4.5 folder=live spawned=false', /Created modeled[^\n]*/);
    const modeled = JSON.parse(readFileSync(path.join(data, 'npcs/live/modeled.json'), 'utf8'));
    assert.deepEqual(modeled.modules['animus:modelengine'], { model: 'qa_model' });
    await server.cmd('animus delete modeled', /Deleted/);
    return `${JSON.stringify(modeled.modules)} | ${reply.match(/Created modeled[^\n]*/)[0]}`;
  });
  await check('mythic: an unknown mob is refused', async () => (await server.cmd('animus mythic ghost NoSuchMob world=world x=0 y=61 z=0', /MythicMobs has no mob named NoSuchMob/))[0]);

  server.behavior('live:mythic-stand', {
    routines: { main: cast('AnimusBless'), self: cast('AnimusSelf', { caster: 'player' }), who: cast('AnimusWho'), mark: cast('AnimusMark') },
    triggers: [
      { id: 'bless', event: 'interaction', action: 'interact', routine: 'main', playerCooldownTicks: 0 },
      { id: 'self', event: 'interaction', action: 'sneak_interact', routine: 'self', playerCooldownTicks: 0 },
      { id: 'who', event: 'interaction', action: 'attack', routine: 'who', playerCooldownTicks: 0 },
      { id: 'mark', event: 'interaction', action: 'swap_hands', routine: 'mark', playerCooldownTicks: 0 },
    ],
  });
  server.behavior('live:mythic-king', {
    routines: { main: cast('AnimusWho'), hit: { type: 'action', capability: 'animus:say', arguments: { text: 'king-attacked' } } },
    triggers: [{ id: 'who', event: 'interaction', action: 'sneak_interact', playerCooldownTicks: 0 }, { id: 'hit', event: 'interaction', action: 'attack', routine: 'hit', playerCooldownTicks: 0 }],
  });
  // mythic:cast-skill can run commands through skills, so it needs the command allow-list like animus:command.
  await server.stop();
  server.config('[behaviors.actions]\ncommandBehaviors = ["live:mythic-stand", "live:mythic-king"]\ncommandNamespaces = []\n');
  await server.start();
  await server.reload();
  await server.cmd('animus edit stand {"behaviors":["live:mythic-stand"]}', /Saved[^;]*; tx /);
  await server.cmd('animus edit king {"behaviors":["live:mythic-king"]}', /Saved[^;]*; tx /);

  const owner = await join(OWNER.name, 'survival');
  await owner.tp(-1.5, 61, 1.5);
  const kingUuid = JSON.parse(readFileSync(path.join(data, 'npcs/live/king.json'), 'utf8')).uuid;
  const standUuid = JSON.parse(readFileSync(path.join(data, 'npcs/live/stand.json'), 'utf8')).uuid;
  let king = await owner.body(kingUuid), stand = await owner.body(standUuid);
  const sneak = async on => { await owner.keys(on ? { shift: true } : {}); await delay(200); };

  await owner.look(king.x, king.y + 1, king.z);
  await check('mythic: a right-click runs the mob ~onInteract skill with the player as trigger', async () => {
    const from = owner.events.length;
    owner.send('use', { id: king.id });
    return (await owner.chat(`king-greets ${OWNER.name}`, from, 8000)).text;
  });
  await check('mythic: a left-click runs the mob ~onDamaged skill', async () => {
    const from = owner.events.length;
    await delay(300);
    owner.send('attack', { id: king.id });
    await owner.chat('king-attacked', from, 8000);
    return (await owner.chat(`king-damaged-by ${OWNER.name}`, from, 8000)).text;
  });
  await check('mythic: ~onTimer:40 skills run while the NPC is spawned and stop when it despawns', async () => {
    const from = owner.events.length;
    await owner.chat('king-tick', from, 6000);
    await owner.chat('king-tick', owner.events.length, 6000);
    await server.cmd('animus despawn king', /Despawn/i);
    await delay(500);
    const after = owner.events.length;
    await owner.noChat('king-tick', after, 4500);
    await server.cmd('animus spawn king', /Spawning king/);
    king = await owner.body(kingUuid);
    return 'ticked twice, then nothing for 4.5 s while despawned';
  });
  await check('mythic: animusnpc holds when the king casts', async () => {
    await owner.look(king.x, king.y + 1, king.z);
    const from = owner.events.length;
    await sneak(true);
    try { owner.send('use', { id: king.id, sneak: true }); } finally { await sneak(false); }
    return (await owner.chat('caster-is-king', from, 8000)).text;
  });

  await owner.look(stand.x, stand.y + 1, stand.z);
  await check('mythic: mythic:cast-skill casts from the NPC at the player, with the NPC as caster', async () => {
    const from = owner.events.length;
    owner.send('use', { id: stand.id });
    const line = await owner.chat('blessed-by Stand', from, 8000);
    const effect = await server.capture(`execute if entity @a[name=${OWNER.name},nbt={active_effects:[{id:"minecraft:speed"}]}]`, /Test (passed|failed)[^\n]*/);
    assert.match(effect, /Test passed/);
    return `${line.text}; speed applied`;
  });
  await check('mythic: caster player casts as the player', async () => {
    const from = owner.events.length;
    await sneak(true);
    try { owner.send('use', { id: stand.id, sneak: true }); } finally { await sneak(false); }
    return (await owner.chat(`self-cast-by ${OWNER.name}`, from, 8000)).text;
  });
  await check('mythic: animusnpc fails when another NPC casts', async () => {
    const from = owner.events.length;
    owner.send('attack', { id: stand.id });
    await owner.noChat('caster-is-king', from, 2500);
    return 'no message from the stand';
  });
  await check('mythic: @AnimusNPC targets where the king stands', async () => {
    await server.cmd('setblock 0 61 4 minecraft:air', /Changed the block|Could not set/);
    owner.send('swap', {});
    for (let i = 0; i < 40; i++) {
      const reply = await server.capture('execute if block 0 61 4 minecraft:gold_block', /Test (passed|failed)[^\n]*/, 50);
      if (/Test passed/.test(reply)) { await server.cmd('setblock 0 61 4 minecraft:air', /Changed the block/); return 'gold block placed at the king'; }
      await delay(150);
    }
    throw new Error('no gold block at the king');
  });

  await owner.close();
  for (const id of ['king', 'stand']) await server.cmd(`animus delete ${id}`, /Deleted/);
}
