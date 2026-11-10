// Quest plugins as a player meets them. A quest made for new Animus NPCs runs start to finish: take it from a giver,
// talk to a helper, hand bread to the giver and kill a bandit. Then a quest file written for Citizens NPCs 7 and 8 is
// linked to the NPCs a Citizens import installed, played, and rolled back. Needs --extra-plugins with the Quests
// (PikaMug) jar for the quests scenario or the BeautyQuests jar for the beautyquests scenario. Driven by live-features.mjs.
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { delay } from '../protocol-client/client.mjs';

const OLD_BAKER = '00000000-0000-4000-8000-0000000c0007', OLD_BANDIT = '00000000-0000-4000-8000-0000000c0008';
const SAVES = `npc:
  '7':
    uuid: ${OLD_BAKER}
    name: Old Baker
    traits:
      type: PLAYER
      spawned: true
      location: {world: world, x: 6.5, y: 61, z: 4.5, yaw: 0, pitch: 0}
  '8':
    uuid: ${OLD_BANDIT}
    name: Old Bandit
    traits:
      type: PLAYER
      spawned: true
      location: {world: world, x: -6.5, y: 61, z: 8.5, yaw: 0, pitch: 0}
`;

const questsFile = (giver, helper, bandit) => `quests:
  animuslive:
    name: Animus Live
    ask-message: Help the giver.
    finish-message: Thanks.
    npc-giver-uuid: ${giver}
    stages:
      ordered:
        '1':
          npc-uuids-to-talk-to:
          - ${helper}
        '2':
          items-to-deliver:
          - ==: org.bukkit.inventory.ItemStack
            type: BREAD
          npc-delivery-uuids:
          - ${giver}
          delivery-messages:
          - Bread, thanks.
        '3':
          npc-uuids-to-kill:
          - ${bandit}
          npc-kill-amounts:
          - 1
  citizenslegacy:
    name: Citizens Legacy
    ask-message: Help the old baker.
    finish-message: Thanks again.
    npc-giver-id: 7
    stages:
      ordered:
        '1':
          npc-ids-to-talk-to:
          - 7
        '2':
          npc-ids-to-kill:
          - 8
          npc-kill-amounts:
          - 1
`;

const beautyLive = `id: 900
name: Animus Live
starterNPC: animus#bqgiver
manager:
  branches:
    '0':
      stages:
        '0':
          stageType: NPC
          npcID: animus#bqhelper
        '1':
          stageType: ITEMS
          npcID: animus#bqgiver
          items:
          - ==: org.bukkit.inventory.ItemStack
            type: BREAD
        '2':
          stageType: MOBS
          objects:
            '0':
              amount: 1
              object:
                factoryName: animusNPC
                value: bqbandit
`;
const beautyLegacy = `id: 901
name: Citizens Legacy
starterNPC: citizens#7
manager:
  branches:
    '0':
      stages:
        '0':
          stageType: NPC
          npcID: citizens#7
        '1':
          stageType: MOBS
          objects:
            '0':
              amount: 1
              object:
                factoryName: citizensNPC
                value: '8'
`;

export async function questPlugins(t) {
  const { server, join, check, data, instance, flavor, OWNER } = t;
  const beauty = flavor === 'beautyquests';
  // Each plugin links its own files: /animus import quests or /animus import beautyquests.
  const link = `animus import ${flavor}`;
  const prefix = beauty ? 'bq' : 'q';
  const plugins = path.join(instance, 'plugins');
  const reload = () => beauty ? server.cmd('beautyquests reload data', /Successfully reloaded plugin data|went horribly wrong/, 60_000)
    : server.cmd('questadmin reload', /Quest data reloaded|Loaded \d+ Quest/, 60_000);
  const killable = { modules: { 'animus:combat': { enabled: true }, 'animus:health': { killable: true, maximumHealth: 1 } } };

  await check(`${flavor}: the quest plugin linked Animus NPCs`, async () => {
    const found = readFileSync(path.join(instance, 'logs/latest.log'), 'utf8').match(/Linked (Quests|BeautyQuests) [^\n]*/);
    assert(found, 'no link line in the startup log');
    return found[0];
  });

  // The NPC is made by command, the way an owner makes one, then edited and spawned.
  const npc = async (id, type, x, y, z, display, edit = {}) => {
    await server.cmd(`animus create ${id} type=${type} world=world x=${x} y=${y} z=${z} folder=live name=${display}`, new RegExp(`Created (?:dormant )?${id}`));
    if (Object.keys(edit).length) await server.cmd(`animus edit ${id} ${JSON.stringify(edit)}`, /Saved[^;]*; tx /);
    await server.cmd(`animus spawn ${id}`, new RegExp(`Spawning ${id}|${id}[^\\n]*(?:already|unchanged|no change)`, 'i'));
    return JSON.parse(readFileSync(path.join(data, 'npcs/live', `${id}.json`), 'utf8')).uuid;
  };
  const ids = {};
  ids.giver = await npc(`${prefix}giver`, 'minecraft:player', 2.5, 61, 4.5, 'Quest_Giver');
  ids.helper = await npc(`${prefix}helper`, 'minecraft:player', -2.5, 61, 4.5, 'Helper');
  ids.bandit = await npc(`${prefix}bandit`, 'minecraft:player', 0.5, 61, 8.5, 'Bandit', killable);

  // The quest files exist before the import, the way a server switching from Citizens has them.
  const questFile = beauty ? path.join(plugins, 'BeautyQuests/quests/901.yml') : path.join(plugins, 'Quests/storage/quests.yml');
  let legacyText;
  await check(`${flavor}: quest files written for Animus and for Citizens NPCs`, async () => {
    if (beauty) {
      mkdirSync(path.join(plugins, 'BeautyQuests/quests'), { recursive: true });
      writeFileSync(path.join(plugins, 'BeautyQuests/quests/900.yml'), beautyLive);
      writeFileSync(questFile, beautyLegacy);
    } else {
      const config = path.join(plugins, 'Quests/config.yml');
      assert(existsSync(config), 'Quests wrote no config.yml');
      writeFileSync(config, readFileSync(config, 'utf8').replace(/confirm-accept: true/, 'confirm-accept: false'));
      writeFileSync(questFile, questsFile(ids.giver, ids.helper, ids.bandit));
    }
    legacyText = readFileSync(questFile, 'utf8');
    const reloaded = await reload();
    return reloaded[0];
  });

  await check(`${flavor}: Citizens import installs NPCs 7 and 8 with their Citizens UUIDs`, async () => {
    mkdirSync(path.join(plugins, 'Citizens'), { recursive: true });
    writeFileSync(path.join(plugins, 'Citizens/saves.yml'), SAVES);
    await server.cmd('animus import citizens preview', /Next: \/animus import citizens apply/, 60_000);
    await server.cmd('animus import citizens apply', /Next: \/animus import citizens review/, 60_000);
    await delay(1000);
    await server.cmd('animus npc health citizens-8 killable on', /Saved|killable/);
    await server.cmd('animus npc health citizens-8 maximum 1', /Saved|maximum/);
    const baker = JSON.parse(readFileSync(path.join(data, 'npcs/world/citizens-7.json'), 'utf8'));
    assert.equal(baker.uuid, OLD_BAKER);
    return `citizens-7 ${baker.uuid}`;
  });

  await check(`${flavor}: import preview reports the Citizens references`, async () => {
    const text = await server.capture(`${link} preview`, /would be relinked[^\n]*/, 800);
    assert.match(text, /3 references in 1 file would be relinked/);
    assert.equal(readFileSync(questFile, 'utf8'), legacyText, 'preview changed the quest file');
    return text.match(/[^\n]*would be relinked[^\n]*/)[0];
  });

  await check(`${flavor}: import apply points the quest at the imported NPCs`, async () => {
    const text = await server.capture(`${link} apply`, /Relinked \d+ references[^\n]*/, 800);
    assert.match(text, /Relinked 3 references in 1 file/);
    const linked = readFileSync(questFile, 'utf8');
    if (beauty) { assert.match(linked, /starterNPC: animus#citizens-7/); assert.match(linked, /factoryName: animusNPC\n\s+value: citizens-8/); }
    else { assert.match(linked, new RegExp(`npc-giver-uuid: '${OLD_BAKER}'`)); assert.match(linked, new RegExp(`npc-uuids-to-kill:\\n\\s+- '${OLD_BANDIT}'`)); }
    await reload();
    return text.match(/Relinked[^\n]*/)[0];
  });

  const owner = await join(OWNER.name, 'survival');
  await server.cmd(`give ${OWNER.name} minecraft:bread 1`, /Gave/);
  const transcript = [];
  const said = from => owner.events.slice(from).filter(e => e.event === 'chat').map(e => e.text);
  // Each click stands two blocks in front of the NPC, inside both use and attack reach.
  const use = async (uuid, op = 'use') => {
    let body = await owner.body(uuid);
    if (Math.hypot(owner.position.x - body.x, owner.position.z - body.z) > 2.5) {
      // A teleport sends every body again under new entity ids.
      await owner.tp(body.x, body.y, body.z - 2);
      await delay(500);
      body = await owner.body(uuid);
    }
    await owner.look(body.x, body.y + 1.4, body.z);
    const from = owner.events.length;
    owner.send(op, { id: body.id });
    return from;
  };
  // Hits until the quest plugin answers the kill; the bandit has one health point, so the first landed hit kills it.
  const kill = async (uuid, done) => {
    const from = owner.events.length;
    for (let hit = 0; hit < 8 && !owner.has(e => e.event === 'chat' && done.test(e.text), from); hit++) {
      await use(uuid, 'attack');
      await delay(700);
    }
    return from;
  };
  const expectChat = async (pattern, from, timeout = 10_000) => {
    const found = await owner.wait(e => e.event === 'chat' && pattern.test(e.text), from, timeout).catch(async error => {
      const status = (await server.capture('animus status', /NPC runtime[^\n]*/)).match(/NPC runtime[^\n]*/)?.[0];
      throw new Error(`${error.message}; ${status}`);
    });
    transcript.push(found.text);
    return found.text;
  };

  await check(`${flavor}: a new Animus NPC gives a quest, takes talk, delivery and kill objectives`, async () => {
    await owner.tp(0.5, 61, 2.5);
    let from = await use(ids.giver);
    await expectChat(beauty ? /started the quest .*Animus Live/ : /Quest accepted: .*Animus Live/, from);
    from = await use(ids.helper);
    await delay(1000);
    transcript.push(...said(from));
    from = await use(ids.giver);
    await delay(1000);
    transcript.push(...said(from));
    const finished = beauty ? /finished the quest .*Animus Live/ : /QUEST COMPLETE.*Animus Live|Animus Live.*complete/i;
    from = await kill(ids.bandit, finished);
    await expectChat(finished, from, 15_000);
    return transcript.slice(-4).join(' | ');
  });

  if (!beauty) await check('quests: /questlog opens the Animus journal while Quests owns /quests', async () => {
    const own = await server.cmd('questlog', /Only a player has a quest journal|Unknown command[^\n]*/);
    assert.match(own[0], /Only a player has a quest journal/);
    const namespaced = await server.cmd('animus:quests', /Only a player has a quest journal|Unknown command[^\n]*/);
    assert.match(namespaced[0], /Only a player has a quest journal/);
    return 'questlog and animus:quests reach Animus';
  });

  await check(`${flavor}: the linked Citizens quest runs on the imported NPCs`, async () => {
    await owner.tp(0.5, 61, 6.5);
    let from = await use(OLD_BAKER);
    await expectChat(beauty ? /started the quest .*Citizens Legacy/ : /Quest accepted: .*Citizens Legacy/, from);
    await delay(500);
    from = await use(OLD_BAKER);
    await delay(1000);
    transcript.push(...said(from));
    const finished = beauty ? /finished the quest .*Citizens Legacy/ : /QUEST COMPLETE.*Citizens Legacy|Citizens Legacy.*complete/i;
    from = await kill(OLD_BANDIT, finished);
    await expectChat(finished, from, 15_000);
    return transcript.slice(-3).join(' | ');
  });

  await check(`${flavor}: import rollback restores the Citizens quest file`, async () => {
    const text = await server.capture(`${link} rollback`, /Restored \d+ files?[^\n]*/, 800);
    assert.equal(readFileSync(questFile, 'utf8'), legacyText);
    return text.match(/Restored[^\n]*/)[0];
  });

  writeFileSync(path.join(t.results, `${flavor}-chat.txt`), transcript.join('\n') + '\n');
  await owner.close();
  for (const id of [`${prefix}giver`, `${prefix}helper`, `${prefix}bandit`]) await server.cmd(`animus delete ${id}`, /Deleted|Unknown/);
}
