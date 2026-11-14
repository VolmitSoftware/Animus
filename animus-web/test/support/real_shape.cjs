// Real servers carry data the fixtures do not, and it broke the frame: a
// long MOTD as the server's name (the top bar), dozens of NPCs with a route
// of their own (Movement's tabs), NPCs named only with a colour code (a blank
// heading), behaviors and shops imported with raw ids (pickers showing ids),
// holograms drawn in block characters (solid bars for names), a 34-point
// route and an armor stand (previews), a guard with every combat section set
// (a page of advanced settings), a 60-frame text animation (an endless tab)
// and a pairing revoked while open. Checks that pair a fake server give it
// these shapes and visit the screens they broke.

/** A server name as long as a real MOTD, accents included. */
const longServerName = 'Welcome to the Oakridge survival network: towns, quests, markets and more, season four';

/** NPCs with their own route; with this many the Movement list outgrows the page. */
const walkers = 40;

/** The NPC whose name is only a colour code. */
const blankNpc = 'realshape/blank-name';

/** An NPC running two Denizen behaviors and a Shopkeepers shop, by raw id. */
const talkerNpc = 'realshape/talker';
const denizenBehaviors = ['denizen:world-npc/gate_keeper_greeting', 'denizen:world-npc/gate_keeper_intercept'];
const rawShop = 'shopkeepers:shop-54';

/** A guard with a 34-point route and every combat section set. */
const guardNpc = 'realshape/guard';

/** An armor stand body. */
const standNpc = 'realshape/stand';

/** A hologram of 16 lines of full-block pixel art. */
const artHologram = 'realshape:art';

/** A text animation of 60 colour frames. */
const longAnimation = 'realshape:rainbow';

async function control(fake, route, body) {
  const response = await fetch(`${fake}/__fake/${route}`, {method: 'POST', headers: {'content-type': 'application/json'}, body: JSON.stringify(body || {})});
  if (!response.ok) throw new Error(`fake ${route}: ${response.status} ${await response.text()}`);
  return response.json();
}

function npc(id, name, index, route) {
  const hex = (index + 1).toString(16).padStart(12, '0');
  const x = 10.5 + index * 3, z = -40.5 + (index % 7) * 2;
  return {
    schemaVersion: 2,
    uuid: `0f0e0d0c-0b0a-4000-8000-${hex}`,
    id: id.split('/').pop(),
    group: id.split('/').slice(0, -1).join('/'),
    name,
    type: 'minecraft:player',
    spawned: true,
    position: {world: 'world', x, y: 64, z, yaw: 0, pitch: 0},
    modules: route ? {
      'animus:navigation': {
        enabled: true, mode: 'walk', speed: 4.3, order: 'sequence', loop: true, waitTicks: 0,
        points: [0, 1, 2, 3].map(point => ({x: x + point * 4, y: 64, z: z + (point % 2) * 3})),
      },
    } : {},
  };
}

/** The combat module an imported Sentinel guard carries: every section set. */
const heavyCombat = {
  enabled: true,
  targets: {categories: ['monsters'], entityTypes: ['minecraft:zombie', 'minecraft:skeleton'], players: ['Outlaw'], healthBelow: 10, permissions: ['town.wanted']},
  ignores: {categories: ['animals'], players: ['Mayor'], owner: true, healthAbove: 2},
  avoids: {entityTypes: ['minecraft:creeper'], names: ['Boom']},
  targeting: {scanRange: 24, scanIntervalTicks: 10, reactionTicks: 5, memoryTicks: 200, retainTarget: true, fightBack: true, lineOfSight: 'required'},
  avoidance: {range: 6, fleeWhenAttacked: true},
  squad: {name: 'watch', alertRange: 32},
  guard: {followDistance: 4, pointRange: 12, assist: true, catchUpDistance: 20, replanDistance: 3, protect: true},
  pursuit: {closeChase: true, rangedChase: true, speed: 1.3, giveUpRange: 48, leashRange: 64, returnToPost: true, teleportWhenStuck: false, replanTicks: 20},
  melee: {enabled: true, damage: 7, reach: 3.5, cooldownTicks: 20, knockback: 0.6},
  ranged: {enabled: true, damage: 4, range: 24, minimumRange: 4, cooldownTicks: 40, velocity: 1.8},
  greetings: {friendly: '&aStay safe out there.', hostile: '&cHalt!', range: 8, cooldownTicks: 600},
  targetableByMobs: false,
  victimDrops: false,
};

function hexColour(at) {
  const h = at * 6, sector = Math.floor(h) % 6, rise = Math.round((h - Math.floor(h)) * 255);
  const rgb = [[255, rise, 0], [255 - rise, 255, 0], [0, 255, rise], [0, 255 - rise, 255], [rise, 0, 255], [255, 0, 255 - rise]][sector];
  return rgb.map(part => part.toString(16).padStart(2, '0')).join('').toUpperCase();
}

function behavior(id) {
  return {schemaVersion: 1, revision: 1, id, entry: 'main', routines: {main: {type: 'sequence', steps: [{type: 'action', capability: 'animus:say', arguments: {text: 'Halt, who goes there?'}}]}}, triggers: [{id: 'click', event: 'interaction'}]};
}

/** Before pairing: the long name. */
async function nameServer(fake) {
  await control(fake, 'name', {name: longServerName});
}

/** After the usual walk: the walkers and the colour-only name, all at once. */
async function addRealShape(fake) {
  for (let index = 0; index < walkers; index++) {
    const id = `realshape/walker-${index + 1}`;
    await control(fake, 'edit', {kind: 'npc', id, source: npc(id, `&aWalker ${index + 1}`, index, true)});
  }
  await control(fake, 'edit', {kind: 'npc', id: blankNpc, source: npc(blankNpc, '&f', walkers, false)});
  for (const id of denizenBehaviors) await control(fake, 'edit', {kind: 'behavior', id, source: behavior(id)});
  await control(fake, 'edit', {kind: 'shop', id: rawShop, source: {schemaVersion: 1, revision: 1, id: rawShop, title: '&6Gate stall', pages: [{rows: 3, buy: {'13': {id: 'bread', type: 'buy', item: {id: 'minecraft:bread', count: 4}, price: 5}}}]}});
  const talker = npc(talkerNpc, 'Gate keeper', walkers + 1, false);
  await control(fake, 'edit', {kind: 'npc', id: talkerNpc, source: {...talker, behaviors: denizenBehaviors, modules: {'animus:shop': {shop: rawShop, openOn: 'right'}}}});
  const guard = npc(guardNpc, 'Gate guard', walkers + 2, true);
  guard.modules['animus:navigation'].points = Array.from({length: 34}, (_, point) => ({
    x: guard.position.x + 15 * Math.cos(point * 2 * Math.PI / 34) + point % 3, y: 64, z: guard.position.z + 15 * Math.sin(point * 2 * Math.PI / 34),
  }));
  guard.modules['animus:combat'] = heavyCombat;
  await control(fake, 'edit', {kind: 'npc', id: guardNpc, source: guard});
  await control(fake, 'edit', {kind: 'npc', id: standNpc, source: {...npc(standNpc, 'Training dummy', walkers + 3, false), type: 'minecraft:armor_stand'}});
  await control(fake, 'edit', {kind: 'hologram', id: artHologram, source: {
    schemaVersion: 3, revision: 1, anchor: {world: 'world', position: [12, 66, -30]},
    lines: Array.from({length: 16}, (_, row) => Array.from({length: 4}, (_, block) => `[${hexColour(((row * 4 + block) % 24) / 24)}]\u2588\u2588`).join('')),
  }});
  await control(fake, 'edit', {kind: 'text-animation', id: longAnimation, source: {
    schemaVersion: 1, revision: 1, mode: 'ascend', frameIntervalMs: 50, frames: Array.from({length: 60}, (_, frame) => `[${hexColour(frame / 60)}]`),
  }});
}

/** Ends every pairing of the fake server, as `/animus editor revoke all` does. */
async function revokeAll(fake) {
  await control(fake, 'revoke', {tokenId: 'all'});
}

/**
 * The screens real-shaped data broke, as [name, hash]: Movement with many
 * routes, the NPC named only by colour codes, and the GUIs list.
 */
function realShapeScreens(workspace) {
  return [
    ['server real movement', `#/workspace/${workspace}/list/route`],
    ['server real blank npc', `#/workspace/${workspace}/npc/${encodeURIComponent(blankNpc)}`],
    ['server real guis', `#/workspace/${workspace}/list/inventory`],
    ['server real holograms', `#/workspace/${workspace}/list/hologram`],
    ['server real art hologram', `#/workspace/${workspace}/hologram/${encodeURIComponent(artHologram)}`],
    ['server real talker npc', `#/workspace/${workspace}/npc/${encodeURIComponent(talkerNpc)}`],
    ['server real guard npc', `#/workspace/${workspace}/npc/${encodeURIComponent(guardNpc)}`],
    ['server real stand npc', `#/workspace/${workspace}/npc/${encodeURIComponent(standNpc)}`],
    ['server real rainbow', `#/workspace/${workspace}/text-animation/${encodeURIComponent(longAnimation)}`],
    ['server real presets', `#/workspace/${workspace}/list/presets`],
  ];
}

module.exports = {
  longServerName, walkers, blankNpc, talkerNpc, denizenBehaviors, rawShop, guardNpc, standNpc, artHologram, longAnimation,
  nameServer, addRealShape, revokeAll, realShapeScreens,
};
