// Citizens-era presentation features as a client receives them: mob body settings in the spawn metadata, a boss bar,
// a viewers filter that hides an NPC from one player, and a click redirect. Driven by live-features.mjs.
import assert from 'node:assert/strict';
import { delay } from '../protocol-client/client.mjs';

/**
 * The body of an NPC once the edit after its create has been shown: a console create spawns the NPC before the edit,
 * so the first body the client sees may be replaced.
 */
async function settled(client, uuid) {
  const first = await client.body(uuid);
  await client.wait(e => e.event === 'remove' && e.ids.includes(first.id), first.sequence, 8000).catch(() => {});
  return client.body(uuid);
}

/** Every metadata field the client received for one entity id since `from`. */
function fields(client, id, from = 0) {
  return client.events.slice(from).filter(e => e.event === 'metadata' && e.id === id).flatMap(e => e.fields);
}

export async function presentation(t) {
  const { server, join, check, inspect, combatNpc, OWNER, PEER } = t;
  server.behavior('live:herald', {
    routines: { main: { type: 'action', capability: 'animus:say', arguments: { text: 'herald-answers' } } },
    triggers: [{ id: 'click', event: 'interaction', cooldownTicks: 0 }],
  });
  await server.reload();
  const owner = await join(OWNER.name, 'creative');
  const peer = await join(PEER.name, 'creative');
  await owner.tp(0, 61, 0);
  await peer.tp(1, 61, 0);

  await check('presentation: a red sheared sheep shows its wool byte', async () => {
    const uuid = await server.npc('lookssheep', 'minecraft:sheep', 3.5, 61, 4.5, 'Sheep', { sheep: { color: 'lime', sheared: true } });
    const body = await owner.body(uuid);
    assert.equal(body.type, 'minecraft:sheep');
    await owner.wait(e => e.event === 'metadata' && e.id === body.id, body.sequence, 8000);
    const wool = fields(owner, body.id, body.sequence).find(f => f.value === 5 + 0x10);
    assert(wool, `no lime sheared wool in ${JSON.stringify(fields(owner, body.id, body.sequence))}`);
    return `${body.type} wool=${wool.value}`;
  });

  await check('presentation: a villager shows its biome, profession and level', async () => {
    const uuid = await server.npc('looksvillager', 'minecraft:villager', 5.5, 61, 4.5, 'Librarian', { villager: { type: 'snow', profession: 'librarian', level: 'expert' } });
    const body = await owner.body(uuid);
    await owner.wait(e => e.event === 'metadata' && e.id === body.id, body.sequence, 8000);
    const data = fields(owner, body.id, body.sequence).find(f => typeof f.raw === 'string' && f.raw.includes('villager_profession'));
    assert(data && data.raw.includes('minecraft:snow') && data.raw.includes('minecraft:librarian') && data.raw.endsWith(' 4'), JSON.stringify(data));
    return data.raw;
  });

  await check('presentation: a boss bar NPC shows its bar to players in range', async () => {
    const from = owner.events.length;
    await server.npc('looksking', 'minecraft:player', -3.5, 61, 4.5, 'King', { bossBar: { title: '&6The King', color: 'yellow', style: 'segmented_6', range: 20, flags: ['darken_sky'] } });
    const bar = await owner.wait(e => e.event === 'boss_event' && e.operation === 'add', from, 10_000);
    assert.equal(bar.text, 'The King');
    assert.equal(bar.color, 'YELLOW');
    assert.equal(bar.darken, true);
    const left = owner.events.length;
    await owner.tp(0, 61, -40);
    await owner.wait(e => e.event === 'boss_event' && e.operation === 'remove' && e.bar === bar.bar, left, 10_000);
    await owner.tp(0, 61, 0);
    return `${bar.text} ${bar.color} removed out of range`;
  });

  await check('presentation: a viewers filter hides an NPC from one player only', async () => {
    // A console create spawns the NPC before the edit adds the filter, so the filtered player may see it briefly first.
    const hidden = await server.npc('lookshidden', 'minecraft:fox', -5.5, 61, 4.5, 'Secret', { viewers: { players: [PEER.uuid] }, fox: { type: 'snow', sleeping: true } });
    const control = await server.npc('lookscontrol', 'minecraft:fox', -7.5, 61, 4.5, 'Open');
    await peer.body(control);
    const settled = Date.now() + 10_000;
    while (peer.current(hidden) && Date.now() < settled) await delay(200);
    assert.equal(peer.current(hidden), null, 'the filtered player still has the NPC');
    const fromPeer = peer.events.length;
    await delay(2000);
    assert(!peer.events.slice(fromPeer).some(e => e.event === 'spawn' && e.uuid === hidden), 'the filtered player was sent the NPC again');
    const seen = owner.current(hidden) ?? await owner.body(hidden);
    const flags = fields(owner, seen.id, seen.sequence).find(f => f.value === 0x20);
    assert(flags, 'no sleeping fox flags');
    return `owner sees ${seen.type} id=${seen.id} sleeping; ${PEER.name} sees only the control fox`;
  });

  await check('presentation: clicking an NPC that redirects runs the target NPC triggers', async () => {
    const herald = await server.npc('looksherald', 'minecraft:player', 7.5, 61, 4.5, 'Herald', { behaviors: ['live:herald'] });
    const page = await server.npc('lookspage', 'minecraft:armor_stand', 1.5, 61, 3.5, 'Page', { clickRedirect: herald });
    await settled(owner, page);
    // A teleport shows the player every NPC again, so the body to click is read after the move.
    await owner.tp(0, 61, 0);
    await owner.walk(1.5, 1.5);
    await delay(1000);
    const body = await owner.body(page);
    await owner.look(body.x, body.y + 1, body.z);
    const from = owner.events.length;
    owner.send('use', { id: body.id });
    try { return (await owner.chat('Herald » herald-answers', from)).text; }
    catch (error) {
      const triggers = await server.capture('animus behaviors triggers', /⇀ Server triggers[^\n]*/);
      throw new Error(`${error.message.slice(0, 120)} | body ${JSON.stringify(body)} at ${JSON.stringify(owner.position)} | ${triggers.slice(0, 900)}`);
    }
  });

  await check('presentation: a speech bubble shows over the NPC and leaves when its time ends', async () => {
    server.behavior('live:bubble', {
      routines: { main: { type: 'action', capability: 'animus:say', arguments: { text: 'bubble-line', bubbleTicks: 60, chat: false } } },
      triggers: [{ id: 'click', event: 'interaction', cooldownTicks: 0 }],
    });
    await server.reload();
    const uuid = await server.npc('looksbubble', 'minecraft:villager', 9.5, 61, 4.5, 'Talker', { behaviors: ['live:bubble'] });
    const body = await settled(owner, uuid);
    await owner.walk(9.5, 2.5);
    await owner.look(body.x, body.y + 1, body.z);
    const from = owner.events.length;
    owner.send('use', { id: body.id });
    const shown = await owner.wait(e => e.event === 'metadata' && e.fields.some(f => typeof f.text === 'string' && f.text.includes('bubble-line')), from, 10_000);
    const gone = await owner.wait(e => e.event === 'metadata' && e.sequence > shown.sequence && e.fields.some(f => typeof f.text === 'string' && f.text.includes('Talker') && !f.text.includes('bubble-line')), from, 15_000);
    await owner.noChat('bubble-line', from, 200);
    // The bubble is text on a row the body already has: the body and its rows are never removed or spawned again for it.
    const churn = owner.events.slice(from).filter(e => (e.event === 'remove' && e.ids.includes(body.id)) || (e.event === 'spawn' && e.uuid === uuid));
    assert.deepEqual(churn, [], `the body was respawned for its bubble: ${JSON.stringify(churn)}`);
    assert.equal(owner.current(uuid)?.id, body.id, 'the same body is still shown');
    return `bubble shown on row ${shown.id}, then ${JSON.stringify(gone.fields.find(f => typeof f.text === 'string').text)}; body ${body.id} never despawned or respawned`;
  });

  await check('presentation: an attack through a redirect hurts only the NPC that was hit', async () => {
    server.behavior('live:struck', {
      routines: { main: { type: 'action', capability: 'animus:say', arguments: { text: 'struck-heard', audience: 'nearby', radius: 64 } } },
      triggers: [{ id: 'hit', event: 'interaction', action: 'attack', cooldownTicks: 0 }],
    });
    await server.reload();
    const far = await server.npc('lookstarget', 'minecraft:player', 30.5, 61, 30.5, 'Target', combatNpc({ targeting: { fightBack: false } }, ['live:struck'], { maximumHealth: 20, invulnerableTicks: 0 }));
    const shield = await server.npc('looksshield', 'minecraft:villager', 1.5, 61, -3.5, 'Shield', { clickRedirect: far });
    await owner.tp(0, 61, 0);
    await owner.walk(1.5, -1.5);
    const body = await settled(owner, shield);
    const before = await inspect('lookstarget');
    await owner.look(body.x, body.y + 1, body.z);
    const from = owner.events.length;
    for (let i = 0; i < 3; i++) { owner.send('attack', { id: body.id }); await delay(600); }
    await owner.chat('struck-heard', from);
    const after = await inspect('lookstarget');
    assert.equal(after.health, before.health, `the far target was hurt: ${before.health} -> ${after.health}`);
    return `target 28 blocks away heard the attack and kept health ${after.health}`;
  });

  await check('presentation: an operator sees an NPC a deny-groups filter hides from group members', async () => {
    const outcast = await server.npc('looksoutcast', 'minecraft:villager', -1.5, 61, -3.5, 'Gatekeeper', { viewers: { groups: ['outcasts'], permissions: ['court.banned'] } });
    const seen = await settled(owner, outcast);
    const alsoPeer = await peer.body(outcast);
    return `operator ${OWNER.name} sees body ${seen.id}; ${PEER.name}, also outside the group, sees ${alsoPeer.id}`;
  });

  await check('presentation: an NPC hidden by its viewers filter neither greets nor takes redirected clicks', async () => {
    server.behavior('live:hush', {
      routines: {
        main: { type: 'action', capability: 'animus:say', arguments: { text: 'hush-answers' } },
        greet: { type: 'action', capability: 'animus:say', arguments: { text: 'hush-greets' } },
      },
      triggers: [{ id: 'click', event: 'interaction', cooldownTicks: 0 }, { id: 'near', event: 'proximity', phase: 'enter', radius: 5, routine: 'greet', cooldownTicks: 0 }],
    });
    server.behavior('live:door', {
      routines: { main: { type: 'action', capability: 'animus:say', arguments: { text: 'door-answers' } } },
      triggers: [{ id: 'click', event: 'interaction', cooldownTicks: 0 }],
    });
    await server.reload();
    await owner.tp(0, 61, 30);
    await peer.tp(0, 61, 30);
    const hush = await server.npc('lookshush', 'minecraft:villager', -2.5, 61, -10.5, 'Hush', { behaviors: ['live:hush'], viewers: { players: [PEER.uuid] } });
    const door = await server.npc('looksdoor', 'minecraft:armor_stand', 2.5, 61, -10.5, 'Door', { behaviors: ['live:door'], clickRedirect: hush });
    const peerFrom = peer.events.length, ownerFrom = owner.events.length;
    await peer.tp(0, 61, -8);
    await owner.tp(-1, 61, -8);
    await owner.chat('hush-greets', ownerFrom, 10_000);
    await peer.noChat('hush-greets', peerFrom, 2000);
    const body = await settled(peer, door);
    assert.equal(peer.current(hush), null, 'the filtered player was sent the hidden NPC');
    await peer.look(body.x, body.y + 1, body.z);
    const clickFrom = peer.events.length;
    peer.send('use', { id: body.id });
    await peer.chat('door-answers', clickFrom);
    await peer.noChat('hush-answers', clickFrom, 1500);
    return `${OWNER.name} was greeted; ${PEER.name} was not, and a click on the door stayed with the door`;
  });

  await check('presentation: block, item and text bodies spawn with their content', async () => {
    const block = await owner.body(await server.npc('looksblock', 'minecraft:falling_block', 11.5, 62, 4.5, 'Block', { fallingBlock: { block: 'minecraft:gold_block' }, gravity: false }));
    assert.equal(block.type, 'minecraft:falling_block');
    assert.notEqual(block.data, 0, 'no block state id in the spawn data');
    const frame = await owner.body(await server.npc('looksframe', 'minecraft:glow_item_frame', 13.5, 62, 4.5, 'Frame', { glowItemFrame: { item: { id: 'minecraft:diamond' }, rotation: 2, facing: 'north' } }));
    assert.equal(frame.data, 2, 'the frame faces north');
    await owner.wait(e => e.event === 'metadata' && e.id === frame.id && e.fields.some(f => f.item === 'minecraft:diamond'), frame.sequence, 8000);
    const sign = await owner.body(await server.npc('lookssign', 'minecraft:text_display', 15.5, 62, 4.5, 'Sign', { textDisplay: { text: '&eWelcome', billboard: 'center' } }));
    await owner.wait(e => e.event === 'metadata' && e.id === sign.id && e.fields.some(f => f.text === 'Welcome'), sign.sequence, 8000);
    const painting = await owner.body(await server.npc('lookspainting', 'minecraft:painting', 17.5, 62, 4.5, 'Art', { painting: { variant: 'minecraft:kebab', facing: 'south' } }));
    await owner.wait(e => e.event === 'metadata' && e.id === painting.id && e.fields.some(f => typeof f.raw === 'string' && f.raw.includes('minecraft:kebab')), painting.sequence, 8000);
    return `falling block data=${block.data}, frame facing=${frame.data} holds a diamond, text display says Welcome, painting kebab`;
  });

  for (const id of ['lookssheep', 'looksvillager', 'looksking', 'lookshidden', 'lookscontrol', 'looksherald', 'lookspage', 'looksbubble', 'lookstarget', 'looksshield', 'looksoutcast', 'lookshush', 'looksdoor', 'looksblock', 'looksframe', 'lookssign', 'lookspainting']) await server.cmd(`animus delete ${id}`, /Deleted/);
  await peer.close();
  await owner.close();
}
