// Movement and looking a player sees: a guard that only follows walks after the player, a route point's speed and
// teleport change the walk, a chat waypoint behavior speaks on arrival, a route pauses for a nearby player, an NPC
// returns home, and look-close turns only the head or looks at each player at once. Driven by live-features.mjs.
import assert from 'node:assert/strict';
import { delay } from '../protocol-client/client.mjs';

/** Degrees between two yaws, the short way round. */
const apart = (a, b) => Math.abs(((((a - b) % 360) + 540) % 360) - 180);
/** The yaw from a point toward another, as Animus faces. */
const yawTo = (from, to) => Math.atan2(-(to.x - from.x), to.z - from.z) * 180 / Math.PI;

/** Runs the motion checks; `t` carries live-features' server, client and check helpers. */
export async function motion(t) {
  const { server, join, check, inspect, until, folia, OWNER, PEER } = t;
  const Y = 61;
  const owner = await join(OWNER.name, 'creative');
  await t.floor(owner);

  await check('follow: a guard that does not protect walks after its player', async () => {
    await owner.tp(-30.5, Y, -30.5);
    await server.npc('hound', 'minecraft:player', -34.5, Y, -30.5, 'Hound', {
      modules: { 'animus:combat': { enabled: true, targeting: { fightBack: false }, guard: { player: OWNER.uuid, followDistance: 2, pointRange: 1, protect: false } } },
    });
    await owner.tp(-20.5, Y, -30.5);
    const near = await until(async () => {
      const n = await inspect('hound');
      return { ok: Math.hypot(n.x + 20.5, n.z + 30.5) <= 3.5, x: n.x, z: n.z };
    }, 20_000, 'hound did not follow').catch(async error => {
      const status = await server.capture('animus combat status npc=hound', /Combat hound:[^\n]*|Unknown[^\n]*/);
      throw new Error(`${error.message}; ${status.replace(/\u001b\[[0-9;]*m/g, '').slice(-400)}`);
    });
    await owner.tp(-20.5, Y, -18.5);
    const again = await until(async () => {
      const n = await inspect('hound');
      return { ok: Math.hypot(n.x + 20.5, n.z + 18.5) <= 3.5, x: n.x, z: n.z };
    }, 20_000, 'hound did not follow the second time');
    await server.cmd('animus delete hound', /Deleted/);
    return `followed to ${near.x.toFixed(1)},${near.z.toFixed(1)} then ${again.x.toFixed(1)},${again.z.toFixed(1)}`;
  });

  await check('route: a point speed and a teleport change the walk, and a chat behavior speaks on arrival', async () => {
    await owner.tp(-4.5, Y, -20.5);
    server.behavior('live:ferry', {
      entry: 'point-1',
      routines: { 'point-1': { type: 'action', capability: 'animus:message', arguments: { text: 'ferry-crossed', to: 'nearby', radius: 64 } } },
      triggers: [{ id: 'point-1', event: 'waypoint', waypoint: 1, routine: 'point-1' }],
    });
    await server.reload();
    const from = owner.events.length;
    await server.npc('ferry', 'minecraft:player', -10.5, Y, -30.5, 'Ferryman', {
      behaviors: ['live:ferry'],
      modules: { 'animus:navigation': { enabled: true, speed: 1, waitTicks: 20, loop: false, points: [
        { x: -10.5, y: Y, z: -30.5, speed: 5 },
        { x: -10.5, y: Y, z: -18.5, teleport: { x: 10.5, y: Y, z: -24.5, yaw: -90 } },
        { x: 14.5, y: Y, z: -24.5 },
      ] } },
    });
    // 12 blocks at the route's 1 block a second take 12 s; at point 0's 5 they take under 3.
    const started = Date.now();
    let leftAt = null;
    const teleported = await until(async () => {
      const n = await inspect('ferry');
      if (leftAt === null && n.z > -29.5) leftAt = Date.now();
      return { ok: n.x > 9, x: n.x, z: n.z };
    }, 25_000, 'ferry never teleported');
    const walk = (Date.now() - (leftAt ?? started)) / 1000;
    const chat = await owner.chat(/^ferry-crossed$/, from, 10_000);
    const done = await until(async () => {
      const n = await inspect('ferry');
      return { ok: Math.abs(n.x - 14.5) < .6 && Math.abs(n.z + 24.5) < .6, x: n.x, z: n.z };
    }, 20_000, 'ferry did not walk on from the teleport');
    await server.cmd('animus delete ferry', /Deleted/);
    assert(walk < 6, `12 blocks took ${walk.toFixed(1)} s, so the point's speed did not apply`);
    return `12 blocks in ${walk.toFixed(1)} s, teleported to ${teleported.x.toFixed(1)},${teleported.z.toFixed(1)}, chat "${chat.text}", walked on to ${done.x.toFixed(1)},${done.z.toFixed(1)}`;
  });

  await check('pause: a route stands still while a player is near', async () => {
    await owner.tp(20.5, Y, -25.5);
    await server.npc('porter', 'minecraft:player', 20.5, Y, -35.5, 'Porter', {
      modules: { 'animus:navigation': { enabled: true, speed: 1, waitTicks: 0, order: 'pingpong', pause: { playerRange: 4, ticks: 40 }, points: [
        { x: 20.5, y: Y, z: -35.5 }, { x: 20.5, y: Y, z: -15.5 },
      ] } },
    });
    // The porter walks toward the player at 1 block a second and stops within 4 blocks of them.
    const stopped = await until(async () => {
      const n = await inspect('porter');
      return { ok: n.z > -30.5 && n.z < -25.5, z: n.z };
    }, 20_000, 'porter never came near');
    await delay(3000);
    const held = await inspect('porter');
    const status = await server.capture('animus navigation status porter', /Navigation porter:[^\n]*/);
    await owner.tp(28.5, Y, -29.5);
    const moved = await until(async () => {
      const n = await inspect('porter');
      return { ok: n.z > held.z + 2, z: n.z };
    }, 15_000, 'porter did not go on once the player left');
    await server.cmd('animus delete porter', /Deleted/);
    assert(held.z - stopped.z < 1.6, `walked ${(held.z - stopped.z).toFixed(1)} blocks while the player stood near`);
    return `stood at z=${held.z.toFixed(1)} for 3 s (${/paused/.test(status) ? 'status paused' : status.trim().slice(0, 80)}), then went on to z=${moved.z.toFixed(1)}`;
  });

  await check('home: an NPC walked away teleports home after its delay', async () => {
    await owner.tp(-30.5, Y, 20.5);
    await server.npc('clerk', 'minecraft:player', -30.5, Y, 10.5, 'Clerk', {
      modules: { 'animus:home': { x: -30.5, y: Y, z: 10.5, yaw: 180, delayTicks: 40 } },
    });
    await server.cmd('animus navigation go clerk -24 61 10 mode=walk speed=4', /./);
    await until(async () => {
      const n = await inspect('clerk');
      return { ok: n.x > -26.5, x: n.x };
    }, 15_000, 'clerk never walked away');
    const home = await until(async () => {
      const n = await inspect('clerk');
      return { ok: Math.abs(n.x + 30.5) < .2 && Math.abs(n.z - 10.5) < .2, x: n.x, z: n.z };
    }, 15_000, 'clerk did not go home');
    await server.cmd('animus delete clerk', /Deleted/);
    return `back at ${home.x.toFixed(1)},${home.z.toFixed(1)}`;
  });

  await check('look: head-only look-close turns the head and leaves the body', async () => {
    await owner.tp(0.5, Y, 16.5);
    const uuid = await server.npc('lookout', 'minecraft:player', 0.5, Y, 10.5, 'Lookout', {
      modules: { 'animus:look-close': { range: 12, headOnly: true } },
    });
    const body = await owner.body(uuid);
    await delay(800);
    const from = owner.events.length;
    // Walking keeps the body; a teleport rebuilds what the player sees.
    await owner.walk(6.5, 10.5);
    const head = await owner.wait(e => e.event === 'head' && e.id === body.id && apart(e.yaw, yawTo({ x: 0.5, z: 10.5 }, { x: 6.5, z: 10.5 })) < 12, from, 6000)
      .catch(error => { throw new Error(`${error.message.slice(0, 120)}; seen ${JSON.stringify(owner.events.slice(from).filter(e => e.id === body.id).slice(0, 6))}`); });
    await delay(800);
    // Moves carry the body's yaw; with the head turned 90 degrees, the body still faces its definition's 0.
    const bodies = owner.events.slice(from).filter(e => (e.event === 'move' || e.event === 'rotation') && e.id === body.id);
    await server.cmd('animus delete lookout', /Deleted/);
    assert(bodies.length > 0, 'no body move was sent with the head turn');
    assert(bodies.every(e => apart(e.yaw, 0) < 3), `the body turned: ${JSON.stringify(bodies.slice(0, 3))}`);
    return `head yaw ${head.yaw.toFixed(1)} toward the player; body yaw stayed ${bodies.at(-1).yaw.toFixed(1)} in ${bodies.length} moves`;
  });

  await check('look: per-player look-close looks at each player at once', async () => {
    const peer = await join(PEER.name, 'creative');
    await owner.tp(20.5, Y, 26.5);
    await peer.tp(20.5, Y, 14.5);
    const uuid = await server.npc('herald', 'minecraft:player', 20.5, Y, 20.5, 'Herald', {
      modules: { 'animus:look-close': { range: 12, perPlayer: true } },
    });
    const mine = await owner.body(uuid), theirs = await peer.body(uuid);
    const toOwner = yawTo({ x: 20.5, z: 20.5 }, { x: 20.5, z: 26.5 }), toPeer = yawTo({ x: 20.5, z: 20.5 }, { x: 20.5, z: 14.5 });
    const seen = await owner.wait(e => e.event === 'head' && e.id === mine.id && apart(e.yaw, toOwner) < 12, 0, 8000);
    const other = await peer.wait(e => e.event === 'head' && e.id === theirs.id && apart(e.yaw, toPeer) < 12, 0, 8000);
    await server.cmd('animus delete herald', /Deleted/);
    await peer.close();
    return `the owner saw head yaw ${seen.yaw.toFixed(1)} and the peer ${other.yaw.toFixed(1)} at the same time`;
  });

  await check('animate: a behavior swings the off hand and lays the NPC down to sleep', async () => {
    await owner.tp(-10.5, Y, 20.5);
    server.behavior('live:gestures', {
      routines: { main: { type: 'sequence', steps: [
        { type: 'action', capability: 'animus:animate', arguments: { animation: 'swing_offhand' } },
        { type: 'action', capability: 'animus:animate', arguments: { animation: 'sleep' } },
      ] } },
      triggers: [],
    });
    await server.reload();
    const uuid = await server.npc('sleeper', 'minecraft:player', -10.5, Y, 14.5, 'Sleeper', { behaviors: ['live:gestures'] });
    const body = await owner.body(uuid);
    const from = owner.events.length;
    await server.runConsole('sleeper', 'live:gestures');
    const swing = await owner.wait(e => e.event === 'animate' && e.id === body.id && e.action === 3, from, 8000);
    const pose = await owner.wait(e => e.event === 'metadata' && e.id === body.id && e.fields.some(f => f.value === 'SLEEPING'), from, 8000);
    await server.cmd('animus delete sleeper', /Deleted/);
    return `off-hand swing (animate action ${swing.action}) and pose SLEEPING at event ${pose.sequence}`;
  });

  await check('anchors: saved by command, walked to and teleported to in another world', async () => {
    await owner.tp(10.5, Y, 20.5);
    await server.cmd('execute in minecraft:the_nether run forceload add 0 0', /Marked|already|No chunks/);
    // The forced chunk loads over the next ticks; a fill before that reports the position as not loaded.
    await until(async () => {
      const reply = await server.capture('execute in minecraft:the_nether run fill -2 100 -2 2 100 2 minecraft:stone', /filled|No blocks|not loaded/);
      return { ok: /filled|No blocks/.test(reply), reply: reply.slice(-120) };
    }, 15_000, 'the nether platform never loaded');
    await server.cmd('execute in minecraft:the_nether run fill -2 101 -2 2 104 2 minecraft:air', /filled|No blocks/);
    await server.npc('surveyor', 'minecraft:player', 10.5, Y, 14.5, 'Surveyor', { behaviors: ['live:survey', 'live:away'] });
    await server.cmd('animus npc anchor surveyor action=add name=dock x=16.5 y=61 z=14.5 world=world', /saved anchor dock|Usage[^\n]*|Cannot[^\n]*/);
    await server.cmd('animus npc anchor surveyor action=add name=pier x=0.5 y=101 z=0.5 world=world_nether', /saved anchor pier|Usage[^\n]*|Cannot[^\n]*/);
    const listed = await server.capture('animus npc anchor surveyor', /surveyor anchors:[^\n]*|has no anchors[^\n]*/);
    assert(/dock/.test(listed) && /pier world_nether/.test(listed), listed.slice(-300));
    server.behavior('live:survey', {
      routines: { main: { type: 'action', capability: 'animus:navigate', arguments: { anchor: 'dock', speed: 4, wait: true } } },
      triggers: [],
    });
    server.behavior('live:away', { routines: { main: { type: 'action', capability: 'animus:teleport', arguments: { target: 'npc', anchor: 'pier' } } }, triggers: [] });
    await server.reload();
    await server.runConsole('surveyor', 'live:survey');
    const walked = await until(async () => { const n = await inspect('surveyor'); return { ok: Math.abs(n.x - 16.5) < .6 && Math.abs(n.z - 14.5) < .6, x: n.x, z: n.z }; }, 15_000, 'surveyor never reached the dock');
    const away = await server.runConsole('surveyor', 'live:away');
    if (folia) {
      // Folia keeps every NPC in its own world: the teleport is refused and the surveyor stays at the dock.
      const { state, text } = await server.finish(away);
      const stayed = await inspect('surveyor');
      assert.notEqual(state, 'COMPLETED', text.slice(0, 300));
      assert(Math.abs(stayed.x - 16.5) < .6 && Math.abs(stayed.y - 61) < .2, stayed.line.slice(0, 200));
      await server.cmd('animus delete surveyor', /Deleted/);
      return `walked to ${walked.x.toFixed(1)},${walked.z.toFixed(1)}; nether teleport ${state} on Folia: ${(text.match(/Behavior diagnostic:[^\n]*/)?.[0] ?? text).replace(/\u001b\[[0-9;]*m/g, '').slice(0, 160)}`;
    }
    const moved = await until(async () => { const n = await inspect('surveyor'); return { ok: Math.abs(n.x - .5) < .2 && Math.abs(n.y - 101) < .2, x: n.x, y: n.y }; }, 10_000, 'surveyor never reached the pier');
    await server.cmd('animus delete surveyor', /Deleted/);
    return `${listed.replace(/\u001b\[[0-9;]*m/g, '').match(/surveyor anchors:[^\n]*/)?.[0]}; walked to ${walked.x.toFixed(1)},${walked.z.toFixed(1)}; teleported to the nether at ${moved.x.toFixed(1)},${moved.y.toFixed(1)}`;
  });

  await check('route: a teleport to another world moves the NPC there and the route goes on', async () => {
    await owner.tp(-20.5, Y, 30.5);
    const uuid = await server.npc('portal', 'minecraft:player', -20.5, Y, 26.5, 'Portal', {
      modules: { 'animus:navigation': { enabled: true, speed: 4, waitTicks: 10, loop: false, points: [
        { x: -20.5, y: Y, z: 26.5, teleport: { x: 0.5, y: 101, z: 0.5, world: 'world_nether' } }, { x: 1.5, y: 101, z: 1.5 },
      ] } },
    });
    const body = await owner.body(uuid);
    if (folia) {
      // Folia refuses a route teleport into another world; the NPC stays in the overworld where it arrived.
      const refused = await server.waitLog(/NPC portal could not teleport to world world_nether[^\n]*/, 0, 15_000);
      const stayed = await inspect('portal');
      await server.cmd('animus delete portal', /Deleted/);
      assert(Math.abs(stayed.y - Y) < .2, stayed.line.slice(0, 200));
      return `refused on Folia: ${refused[0].slice(0, 140)}`;
    }
    const gone = await owner.wait(e => e.event === 'remove' && e.ids.includes(body.id), 0, 15_000);
    // A route with no player near sleeps, so the player follows it into the nether.
    await server.cmd(`execute in minecraft:the_nether run tp ${OWNER.name} 0.5 101 -1.5`, /Teleported/);
    const there = await until(async () => { const n = await inspect('portal'); return { ok: Math.abs(n.x - 1.5) < .6 && Math.abs(n.z - 1.5) < .6 && Math.abs(n.y - 101) < .2, x: n.x, y: n.y, z: n.z }; }, 15_000, 'portal did not walk on in the nether');
    await server.cmd('animus delete portal', /Deleted/);
    await server.cmd(`execute in minecraft:overworld run tp ${OWNER.name} 30.5 ${Y} 26.5`, /Teleported/);
    return `left the overworld for the player at event ${gone.sequence}, walked on to ${there.x.toFixed(1)},${there.y.toFixed(1)},${there.z.toFixed(1)}`;
  });

  await check('look: random target switching turns between two players', async () => {
    const peer = await join(PEER.name, 'creative');
    await owner.tp(30.5, Y, 26.5);
    await peer.tp(30.5, Y, 14.5);
    const uuid = await server.npc('crier', 'minecraft:player', 30.5, Y, 20.5, 'Crier', { modules: { 'animus:look-close': { range: 12, switchTicks: 10 } } });
    const body = await owner.body(uuid);
    await delay(6000);
    const yaws = owner.events.filter(e => e.event === 'head' && e.id === body.id).map(e => e.yaw);
    const atOwner = yaws.filter(y => apart(y, 0) < 12).length, atPeer = yaws.filter(y => apart(y, 180) < 12).length;
    await server.cmd('animus delete crier', /Deleted/);
    await peer.close();
    assert(atOwner > 0 && atPeer > 0, `head yaws ${JSON.stringify(yaws)}`);
    return `${atOwner} turns to the owner and ${atPeer} to the peer in 6 s`;
  });

  await check('mirror: each viewer sees its own name and equipment on the NPC', async () => {
    const peer = await join(PEER.name, 'creative');
    await owner.tp(40.5, Y, 26.5);
    await peer.tp(40.5, Y, 14.5);
    // Run by the player, so Folia runs them on the player's own region; Folia has no /item, so an emptied inventory
    // takes the sword into the selected first hotbar slot.
    const asked = await owner.command('clear @s');
    await owner.command('give @s minecraft:diamond_sword');
    await delay(500);
    const itemReply = owner.events.slice(asked).filter(e => e.event === 'chat').map(e => e.text).join(' | ');
    const uuid = await server.npc('mirror', 'minecraft:player', 40.5, Y, 20.5, 'Mirror', { modules: { 'animus:mirror': { skin: true, name: true, equipment: true } } });
    const mine = await owner.body(uuid), theirs = await peer.body(uuid);
    // A player body shows its name through its profile, which carries the body's UUID.
    const named = (client, name) => client.wait(e => e.event === 'player_info' && e.uuid === uuid && e.name === name, 0, 8000);
    const ownName = await named(owner, OWNER.name), peerName = await named(peer, PEER.name);
    const sword = await owner.wait(e => e.event === 'equipment' && e.id === mine.id && Object.values(e.slots).includes('minecraft:diamond_sword'), 0, 8000).catch(error => {
      const held = owner.events.filter(e => (e.event === 'container_slot' || e.event === 'set_slot') && JSON.stringify(e).includes('diamond_sword')).length;
      const shown = owner.events.filter(e => e.event === 'equipment' && e.id === mine.id).map(e => JSON.stringify(e.slots));
      throw new Error(`${error.message.slice(0, 100)} | item reply: ${itemReply.slice(0, 200)} | sword slot packets to the owner: ${held} | NPC equipment: ${shown.join(' ').slice(0, 300)}`);
    });
    // The NPC is created before its module is saved, so its first body may carry its own name; the peer's never shows.
    const crossed = owner.events.some(e => e.event === 'player_info' && e.uuid === uuid && e.name === PEER.name);
    await server.cmd('animus delete mirror', /Deleted/);
    await peer.close();
    assert(!crossed, 'the owner saw the peer\'s name on the NPC');
    return `owner saw "${OWNER.name}" (event ${ownName.sequence}) and the sword (event ${sword.sequence}); peer saw "${PEER.name}" (event ${peerName.sequence}) on body ${theirs.id}`;
  });

  await check('home: a guard following its player is never pulled home', async () => {
    await owner.tp(-30.5, Y, 36.5);
    await server.npc('warden', 'minecraft:player', -34.5, Y, 36.5, 'Warden', {
      modules: {
        'animus:combat': { enabled: true, targeting: { fightBack: false }, guard: { player: OWNER.uuid, followDistance: 2, pointRange: 1, protect: false } },
        'animus:home': { x: -34.5, y: Y, z: 36.5, delayTicks: 20 },
      },
    });
    await owner.tp(-20.5, Y, 36.5);
    await until(async () => { const n = await inspect('warden'); return { ok: Math.hypot(n.x + 20.5, n.z - 36.5) <= 3.5, x: n.x }; }, 20_000, 'warden did not follow');
    await delay(4000);
    const n = await inspect('warden');
    await server.cmd('animus delete warden', /Deleted/);
    assert(Math.hypot(n.x + 20.5, n.z - 36.5) <= 3.5, `warden went home to ${n.x},${n.z}`);
    return `still beside the player at ${n.x.toFixed(1)},${n.z.toFixed(1)} after 4 s with a 1 s home delay`;
  });
  await owner.close();
}
