// Integration looks a player sees, and the commands that only show with their plugin. A Model Engine model drawn over
// an NPC answers attacks on its hitbox with attack and damage behaviors and plays its attack state on a swing; a Mythic
// mob with a model skill becomes a modelled NPC; LibsDisguises falling block, item and display disguises draw those
// entities. Needs --extra-plugins with ModelEngine, MythicMobs, LibsDisguises and packetevents jars and --model with a
// .bbmodel holding a hitbox bone and an `attack` animation. Without the plugins (--scenarios hidden) the integration
// commands stay out of help and completion and answer that they need their plugin. Driven by live-features.mjs.
import assert from 'node:assert/strict';
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { delay } from '../protocol-client/client.mjs';

/** Commands that only exist while their plugin runs, as /animus <words>. */
const GATED = [['ModelEngine', 'npc model'], ['LibsDisguises', 'npc disguise'], ['MythicMobs', 'mythic'],
  ['Quests', 'import quests'], ['BeautyQuests', 'import beautyquests'], ['BetonQuest', 'import betonquest']];
const MOB = `AnimusMeg:
  Type: ZOMBIE
  Display: 'Modelled'
  Skills:
  - model{mid=qacube} @self ~onSpawn
`;

const near = (e, x, z, radius = 3) => Math.abs(e.x - x) < radius && Math.abs(e.z - z) < radius;
/** Rotations the client received for `ids` since `from`, as [x, y, z, w]. */
const rotations = (client, from, ids) => client.events.slice(from).filter(e => e.event === 'metadata' && ids.has(e.id))
  .flatMap(e => e.fields.filter(f => f.quaternion).map(f => f.quaternion));
const plain = text => String(text).replace(/§./g, '');

async function suggestions(client, text) {
  const id = Math.floor(Math.random() * 1e6), from = client.events.length;
  client.send('suggest', { id, text });
  return (await client.wait(e => e.event === 'suggestions' && e.id === id, from, 8000)).texts;
}
/** Every chat line /animus <group> help prints, over all its pages. */
async function help(client, group) {
  const lines = [];
  for (let page = 1; page <= 6; page++) {
    const from = client.events.length;
    await client.command(`animus ${group ? `${group} ` : ''}help ${page}`);
    await delay(900);
    const got = client.events.slice(from).filter(e => e.event === 'chat').map(e => plain(e.text));
    lines.push(...got);
    if (!got.some(line => /next|»|page/i.test(line)) || got.length === 0) break;
  }
  return lines.join('\n');
}

/** Each gated command is shown in help and completion, or hidden there and refused, as `present` says. */
export async function gatedCommands(t, present) {
  const { join, check, OWNER } = t;
  const owner = await join(OWNER.name, 'creative');
  for (const [plugin, words] of GATED) {
    const shown = present(plugin);
    await check(`commands: /animus ${words} ${shown ? 'shown' : 'hidden'} with ${plugin} ${shown ? 'present' : 'absent'}`, async () => {
      const [first, second] = words.split(' ');
      const group = second ? first : '';
      const leaf = second ?? first;
      const listing = await help(owner, group);
      const inHelp = new RegExp(`(^|[\\s/])${leaf}\\b`, 'm').test(listing.split('\n').filter(line => !/Usage|Unknown/.test(line)).join('\n'));
      const completed = await suggestions(owner, `/animus ${group ? `${group} ` : ''}`);
      const inCompletion = completed.includes(leaf);
      if (shown) {
        assert(inCompletion, `completion lacks ${leaf}: ${completed.join(',')}`);
        assert(inHelp, `help lacks ${leaf}`);
        return `help and completion list ${leaf}`;
      }
      assert(!inCompletion, `completion offers ${leaf}: ${completed.join(',')}`);
      assert(!inHelp, `help lists ${leaf}`);
      const reply = await owner.command(`animus ${words} x`, /needs |not available|Unknown command/i, 8000);
      assert.match(plain(reply.text), new RegExp(`needs ${plugin}`));
      return `hidden; typed: ${plain(reply.text)}`;
    });
  }
  await owner.close();
}

export async function looks(t) {
  const { server, join, check, instance, model, OWNER } = t;
  await check('looks: Model Engine loads the test model', async () => {
    const blueprints = path.join(instance, 'plugins/ModelEngine/blueprints');
    mkdirSync(blueprints, { recursive: true });
    copyFileSync(model, path.join(blueprints, 'qacube.bbmodel'));
    await server.cmd('meg reload', null);
    await delay(8000);
    return 'meg reload sent';
  });
  server.behavior('live:hits', {
    routines: {
      main: { type: 'action', capability: 'animus:say', arguments: { text: 'hit-interact', audience: 'nearby', radius: 32 } },
      attacked: { type: 'action', capability: 'animus:say', arguments: { text: 'hit-attack', audience: 'nearby', radius: 32 } },
      damaged: { type: 'action', capability: 'animus:say', arguments: { text: 'hit-damage', audience: 'nearby', radius: 32 } },
    },
    triggers: [
      { id: 'click', event: 'interaction', action: 'interact', cooldownTicks: 0 },
      { id: 'attack', event: 'interaction', action: 'attack', routine: 'attacked', cooldownTicks: 0 },
      { id: 'damage', event: 'damage', routine: 'damaged', cooldownTicks: 0 },
    ],
  });
  server.behavior('live:swing', { routines: { main: { type: 'action', capability: 'animus:animate', arguments: { animation: 'swing' } } } });
  await server.reload();
  const owner = await join(OWNER.name, 'creative');
  await owner.tp(0.5, 61, 0.5, 0, 0);

  // A plain body first: the same behavior answers a hit on the body, so a model hit has a baseline.
  const hit = async (target, label, from) => {
    owner.send('attack', { id: target });
    const said = [];
    for (const text of ['hit-attack', 'hit-damage']) said.push(await owner.chat(text, from, 6000).then(() => text, () => `no ${text}`));
    const fired = plain(await server.capture('animus behaviors triggers', /⇀ Server triggers[^\n]*/)).split('\n').filter(line => /#attack |\(On damage/.test(line));
    return `${label}: ${said.join(', ')} | ${fired.map(line => line.slice(line.indexOf('⇀'), line.indexOf('⇀') + 130)).join(' ; ')}`;
  };
  await check('looks: attack and damage behaviors answer a hit on a plain body', async () => {
    const uuid = await server.npc('plainbody', 'minecraft:player', 0.5, 61, 3.5, 'Plain', { behaviors: ['live:hits'], modules: { 'animus:health': { killable: true } } });
    const body = await owner.body(uuid);
    await owner.look(0.5, 62, 3.5);
    const result = await hit(body.id, `body #${body.id}`, owner.events.length);
    assert(!/no hit/.test(result), result);
    await server.cmd('animus delete plainbody', /Deleted/);
    return result;
  });

  let drake = null;
  await check('looks: a Model Engine model is drawn over the NPC with a hitbox', async () => {
    const uuid = await server.npc('drake', 'minecraft:player', 0.5, 61, 4.5, 'Drake', { behaviors: ['live:hits', 'live:swing'], modules: { 'animus:health': { killable: true } } });
    await owner.body(uuid);
    const from = owner.events.length;
    for (const [part, to] of [['model', 'qacube'], ['nametag-bone', 'tag_name'], ['attack', 'attack'], ['interact', 'wave']])
      await server.cmd(`animus npc model drake ${part} ${to}`, /drake[^\n]*(model|nametag|attack|interact)/);
    await owner.wait(e => e.event === 'spawn' && e.type === 'minecraft:interaction' && near(e, 0.5, 4.5), from, 15_000);
    await delay(1500);
    const spawned = owner.events.slice(from).filter(e => e.event === 'spawn' && near(e, 0.5, 4.5));
    const displays = new Set(spawned.filter(e => e.type.endsWith('_display')).map(e => e.id));
    const box = spawned.filter(e => e.type === 'minecraft:interaction').at(-1);
    assert(displays.size >= 2, `only ${displays.size} displays`);
    drake = { uuid, displays, box };
    return `${displays.size} bone displays, hitbox #${box.id}, kinds ${[...new Set(spawned.map(e => e.type))].join(',')}`;
  });
  await check('looks: a hit on the model hitbox runs attack and damage behaviors and lowers health', async () => {
    assert(drake, 'no model');
    await owner.look(0.5, 62, 4.5);
    const before = plain(await server.capture('animus inspect drake', /Runtime drake:[^\n]*/)).match(/health=([0-9.]+)/)?.[1];
    const result = await hit(drake.box.id, `hitbox #${drake.box.id}`, owner.events.length);
    await delay(500);
    const after = plain(await server.capture('animus inspect drake', /Runtime drake:[^\n]*/)).match(/health=([0-9.]+)/)?.[1];
    assert(!/no hit/.test(result), `${result}; health ${before} -> ${after}`);
    assert(Number(after) < Number(before), `health ${before} -> ${after}`);
    return `${result}; health ${before} -> ${after}`;
  });
  await check('looks: a swing plays the model attack state', async () => {
    assert(drake, 'no model');
    await delay(1500);
    const from = owner.events.length;
    await server.runConsole('drake', 'live:swing');
    await delay(1200);
    // The attack animation tips the body bone 45 degrees about x; idle only turns the head about y.
    const tipped = rotations(owner, from, drake.displays).filter(([x, y, z]) => Math.abs(x) > 0.3 && Math.abs(z) < 0.05);
    assert(tipped.length > 0, `no attack rotation among ${rotations(owner, from, drake.displays).length} updates`);
    return `${tipped.length} attack rotations, peak x=${Math.max(...tipped.map(q => Math.abs(q[0]))).toFixed(3)}`;
  });
  await server.cmd('animus delete drake', /Deleted/);

  await check('looks: a Mythic mob with a model skill becomes a modelled NPC', async () => {
    mkdirSync(path.join(instance, 'plugins/MythicMobs/Mobs'), { recursive: true });
    writeFileSync(path.join(instance, 'plugins/MythicMobs/Mobs/animus-looks.yml'), MOB);
    await server.cmd('mm reload', null);
    let reply = '';
    for (let i = 0; i < 60 && !/Created megmob/.test(reply); i++) {
      reply = plain(await server.capture('animus mythic megmob AnimusMeg world=world x=-3.5 y=61 z=4.5 folder=live', /Created megmob[^\n]*|has no mob named[^\n]*|already exists[^\n]*/, 100));
      if (!/Created megmob/.test(reply)) await delay(500);
    }
    assert.match(reply, /Created megmob/);
    const from = owner.events.length;
    await server.cmd('animus spawn megmob', /Spawning megmob|already/).catch(() => {});
    const shown = await owner.wait(e => e.event === 'spawn' && e.type.endsWith('_display') && near(e, -3.5, 4.5), Math.max(0, from - 400), 15_000);
    return `${reply.match(/Created megmob[^\n]*/)[0].slice(0, 200)}; display #${shown.id} drawn`;
  });
  await server.cmd('animus delete megmob', /Deleted/).catch(() => {});

  for (const [id, disguise, kind, x] of [
    ['sandy', 'falling_block sand', 'minecraft:falling_block', -6.5],
    ['gem', 'dropped_item diamond', 'minecraft:item', -4.5],
    ['cube', 'block_display setBlock stone', 'minecraft:block_display', -2.5],
    ['shown', 'item_display setItemStack emerald', 'minecraft:item_display', 2.5],
    ['sign', 'text_display setText hello', 'minecraft:text_display', 4.5],
  ]) await check(`looks: a ${disguise.split(' ')[0]} disguise draws ${kind}`, async () => {
    const uuid = await server.npc(id, 'minecraft:player', x, 61, 7.5, id);
    await owner.body(uuid);
    const from = owner.events.length;
    const text = plain(await server.capture(`animus npc disguise ${id} "${disguise}"`, new RegExp(`${id}: disguised[^\\n]*|${id}: disguise removed|Error[^\\n]*`)));
    const reply = text.match(new RegExp(`${id}: disguised[^\\n]*|Error[^\\n]*`))?.[0] ?? text.slice(-200);
    assert.match(reply, /disguised as/, reply);
    const drawn = await owner.wait(e => e.event === 'spawn' && e.type === kind && near(e, x, 7.5), from, 10_000);
    return `${reply.slice(0, 120)}; client got ${drawn.type} #${drawn.id}`;
  });
  for (const id of ['sandy', 'gem', 'cube', 'shown', 'sign']) await server.cmd(`animus delete ${id}`, /Deleted/).catch(() => {});
  await owner.close();
}

/**
 * A player-name disguise with LibsDisguises' SaveGameProfiles off: the skin LibsDisguises fetches reaches the NPC after
 * one reload and stays, instead of each reload fetching it again. Needs --extra-plugins with LibsDisguises and packetevents
 * jars and a server that can reach Mojang. Driven by live-features.mjs.
 */
export async function disguiseSkins(t) {
  const { server, check, instance, data } = t;
  const settings = path.join(instance, 'plugins/LibsDisguises/configs/libsdisguises.yml');
  await check('disguise skins: LibsDisguises runs with SaveGameProfiles off', async () => {
    await server.stop();
    writeFileSync(settings, readFileSync(settings, 'utf8').replace(/^SaveGameProfiles: true$/m, 'SaveGameProfiles: false'));
    await server.start();
    return readFileSync(settings, 'utf8').match(/^SaveGameProfiles: \w+$/m)[0];
  });
  const skins = () => existsSync(path.join(data, 'skins')) ? readdirSync(path.join(data, 'skins')).filter(file => file.endsWith('.json')) : [];
  const before = new Set(skins());
  await check('disguise skins: a player-name disguise takes its fetched skin and keeps it', async () => {
    await server.npc('notchy', 'minecraft:player', 0.5, 61, 9.5, 'notchy');
    const text = plain(await server.capture('animus npc disguise notchy "player Notch"', /notchy: disguised[^\n]*|Error[^\n]*/));
    assert.match(text, /notchy: disguised as minecraft:player/, text.slice(-200));
    let added = [];
    for (let i = 0; i < 100 && added.length === 0; i++) { await delay(200); added = skins().filter(file => !before.has(file)); }
    assert.equal(added.length, 1, 'the fetched skin was never saved');
    const definition = () => readFileSync(path.join(data, 'npcs/live/notchy.json'), 'utf8');
    const settled = definition();
    await delay(8000);
    assert.deepEqual(skins().filter(file => !before.has(file)), added, 'no further skin saves');
    assert.equal(definition(), settled, 'the definition stays as it settled');
    return `skin ${added[0]} saved once and unchanged for 8 s`;
  });
  await server.cmd('animus delete notchy', /Deleted/).catch(() => {});
}
