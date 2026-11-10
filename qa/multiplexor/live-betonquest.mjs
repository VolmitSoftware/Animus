// BetonQuest 3 with Animus NPCs, as a player clicks them. Needs --extra-plugins with the BetonQuest jar.
// A Citizens save is imported first, so the quest files that still name Citizens NPC 7 are relinked with /animus import.
// Driven by live-features.mjs.
import assert from 'node:assert/strict';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { delay } from '../protocol-client/client.mjs';

const SAVES = `npc:
  '7':
    uuid: 00000000-0000-4000-8000-000000000007
    name: Innkeeper
    traits:
      type: PLAYER
      spawned: true
      location: {world: world, x: 4.5, y: 61, z: 6.5, yaw: 180, pitch: 0}
`;

const PACKAGE = `npcs:
  innkeeper: "citizens 7"
  guard: "animus bqguard"
npc_conversations:
  innkeeper: innTalk
conversations:
  innTalk:
    quester: Innkeeper
    conversationIO: simple
    first: hello
    NPC_options:
      hello:
        text: "Welcome back to the inn"
objectives:
  talkGuard: "npcinteract guard actions:talkDone"
  killGuard: "animusnpckill guard amount:1 actions:killDone"
  killInn: "npckill innkeeper"
actions:
  talkDone: "command say BQ-TALK-DONE"
  killDone: "command say BQ-KILL-DONE"
  hideGuard: "tag add hide_guard"
  showGuard: "tag delete hide_guard"
  moveGuard: "npcteleport guard 2.5;61;9.5;world"
conditions:
  nearGuard: "npcdistance guard 4"
  hidden: "tag hide_guard"
hide_npcs:
  guard: hidden
`;

export async function quests(t) {
  const { server, join, check, instance, OWNER, click, logText } = t;
  const plugins = path.join(instance, 'plugins');

  await check('quests: each integration starts', async () => {
    const log = logText();
    for (const line of ['BetonQuest: animus NPC type and animusnpckill objective registered', 'Enabled compatibility from plugin \'Animus\''])
      assert(log.includes(line), `missing startup line: ${line}`);
    return 'BetonQuest registered';
  });

  await check('quests: a Citizens save imports as citizens-7', async () => {
    mkdirSync(path.join(plugins, 'Citizens'), { recursive: true });
    writeFileSync(path.join(plugins, 'Citizens/saves.yml'), SAVES);
    await server.cmd('animus import citizens preview', /Next:/, 60_000);
    await server.cmd('animus import citizens apply', /Next:/, 60_000);
    await server.cmd('animus spawn citizens-7', /Spawning citizens-7/);
    return 'imported and spawned citizens-7';
  });

  await server.cmd('animus create bqguard type=minecraft:player world=world x=0.5 y=61 z=6.5 folder=live name=Guard', /Created bqguard/);
  await server.cmd(`animus edit bqguard ${JSON.stringify({ modules: { 'animus:combat': { enabled: true, targeting: { fightBack: false } }, 'animus:health': { killable: true, maximumHealth: 1, invulnerableTicks: 0 } } })}`, /Saved[^;]*; tx /);
  await server.cmd('animus spawn bqguard', /Spawning bqguard/);
  const guard = JSON.parse(readFileSync(path.join(plugins, 'Animus/npcs/live/bqguard.json'), 'utf8')).uuid;
  const owner = await join(OWNER.name, 'survival');
  await owner.tp(2.5, 61, 3.5);
  const inn = JSON.parse(readFileSync(path.join(plugins, 'Animus/npcs/world/citizens-7.json'), 'utf8')).uuid;

  await check('quests: BetonQuest files relink from Citizens 7 with a report and rollback', async () => {
    const pkg = path.join(plugins, 'BetonQuest/QuestPackages/animusqa/package.yml');
    mkdirSync(path.dirname(pkg), { recursive: true });
    writeFileSync(pkg, PACKAGE);
    const preview = await server.capture('animus import betonquest preview', /would be relinked/, 400);
    assert.match(preview, /BetonQuest: 2 references in 1 file/);
    assert.equal(readFileSync(pkg, 'utf8'), PACKAGE, 'preview changed the file');
    await server.cmd('animus import betonquest apply', /Relinked 2 references/);
    await server.cmd('animus import betonquest rollback', /Restored 1 file/);
    assert.equal(readFileSync(pkg, 'utf8'), PACKAGE, 'rollback did not restore the original');
    await server.cmd('animus import betonquest apply', /Relinked 2 references/);
    assert.match(readFileSync(pkg, 'utf8'), /innkeeper: "animus citizens-7"/);
    assert.match(readFileSync(pkg, 'utf8'), /killInn: "animusnpckill innkeeper"/);
    const report = JSON.parse(readFileSync(path.join(plugins, 'Animus/imports/links/betonquest/report.json'), 'utf8'));
    assert.equal(report.changes.length, 2, JSON.stringify(report));
    await server.cmd('bq reload', /reloaded|Reloaded/i, 60_000);
    await delay(3000);
    return preview.match(/BetonQuest: [^\n]*/)[0];
  });

  await check('quests: BetonQuest starts the npc_conversations conversation on the imported innkeeper', async () => {
    const from = await click(owner, inn);
    return (await owner.chat('Welcome back to the inn', from, 10_000)).text;
  });

  await check('quests: BetonQuest npcinteract and npcdistance work on an Animus NPC', async () => {
    await server.cmd(`bq objective ${OWNER.name} add animusqa>talkGuard`, /./);
    await delay(800);
    const condition = await server.capture(`bq condition ${OWNER.name} animusqa>nearGuard`, /nearGuard|true|false/i, 300);
    assert.match(condition, /true/i, condition.slice(-200));
    await delay(1200);
    await click(owner, guard);
    return (await server.waitLog(/BQ-TALK-DONE/, 0, 10_000))[0] + ' | ' + condition.trim().split('\n').at(-1).slice(-80);
  });

  await check('quests: BetonQuest hide_npcs hides the NPC from that player only, then shows it again', async () => {
    const before = owner.current(guard);
    assert(before, 'guard not visible to start with');
    await server.cmd(`bq action ${OWNER.name} animusqa>hideGuard`, /./);
    await owner.wait(e => e.event === 'remove' && e.ids.includes(before.id), 0, 20_000);
    await server.cmd(`bq action ${OWNER.name} animusqa>showGuard`, /./);
    const back = await owner.body(guard, 20_000);
    return `removed body ${before.id}, shown again as ${back.id}`;
  });

  await check('quests: BetonQuest npcteleport moves the Animus NPC', async () => {
    await server.cmd(`bq action ${OWNER.name} animusqa>moveGuard`, /./);
    const moved = await server.waitLog(/./, 0, 1000).then(async () => {
      for (let i = 0; i < 40; i++) {
        const saved = JSON.parse(readFileSync(path.join(plugins, 'Animus/npcs/live/bqguard.json'), 'utf8')).position;
        if (Math.abs(saved.x - 2.5) < .01 && Math.abs(saved.z - 9.5) < .01) return saved;
        await delay(250);
      }
      throw new Error('position not saved');
    });
    return JSON.stringify(moved);
  });

  await check('quests: animusnpckill completes when the player kills the Animus NPC', async () => {
    await server.cmd(`bq objective ${OWNER.name} add animusqa>killGuard`, /./);
    await owner.tp(2.5, 61, 8.0);
    await delay(800);
    for (let i = 0; i < 5; i++) {
      await click(owner, guard, 'attack');
      const done = await server.waitLog(/BQ-KILL-DONE/, 0, 1500).catch(() => null);
      if (done) return done[0];
    }
    throw new Error('kill objective did not complete');
  });

  await owner.close();
}
