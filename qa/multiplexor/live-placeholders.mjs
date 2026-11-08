// PlaceholderAPI filling as a player receives it: an NPC named %player_name% and hologram lines with %server_online%
// arrive filled in the text display metadata. Needs --extra-plugins with a PlaceholderAPI jar and --papi-expansions with
// the Player and Server expansion jars. Driven by live-features.mjs.
import assert from 'node:assert/strict';
import { copyFileSync, mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { delay } from '../protocol-client/client.mjs';

/** Every text component in the metadata the client received for one entity id since `from`. */
function texts(client, id, from = 0) {
  return client.events.slice(from).filter(e => e.event === 'metadata' && e.id === id).flatMap(e => e.fields.filter(f => typeof f.text === 'string').map(f => f.text));
}

/** Text display ids riding a body, from the passengers packet the spawn sends. */
async function riders(client, body) {
  const ride = await client.wait(e => e.event === 'passengers' && e.vehicle === body.id && e.ids.length > 0, body.sequence, 8000);
  return ride.ids;
}

export async function placeholders(t) {
  const { server, join, check, data, instance, expansions, OWNER } = t;
  await check('placeholders: PlaceholderAPI expansions installed', async () => {
    // PlaceholderAPI's own /papi ecloud download is refused on many servers, so the expansion jars are copied in.
    const folder = path.join(instance, 'plugins/PlaceholderAPI/expansions');
    mkdirSync(folder, { recursive: true });
    for (const jar of expansions) copyFileSync(jar, path.join(folder, path.basename(jar)));
    const registered = await server.cmd('papi reload', /\d+ placeholder hook\(s\) registered/i, 30_000);
    return registered[0];
  });

  const owner = await join(OWNER.name, 'creative');
  await owner.tp(0, 61, 0);
  await check('placeholders: an armadillo named %player_name% shows the viewer name and online count', async () => {
    const uuid = await server.npc('papiarmadillo', 'minecraft:armadillo', 2.5, 61, 4.5, '%player_name%', { hologram: { lines: ['Online %server_online%'] } });
    const body = await owner.body(uuid);
    const ids = await riders(owner, body);
    await owner.wait(e => e.event === 'metadata' && ids.includes(e.id) && e.fields.some(f => typeof f.text === 'string'), body.sequence, 8000);
    const shown = ids.flatMap(id => texts(owner, id, body.sequence)).join('\n');
    assert(shown.includes(OWNER.name), `name not filled: ${JSON.stringify(shown)}`);
    assert(/Online 1\b/.test(shown), `online count not filled: ${JSON.stringify(shown)}`);
    assert(!shown.includes('%'), `a placeholder reached the client: ${JSON.stringify(shown)}`);
    return JSON.stringify(shown);
  });

  await check('placeholders: a player NPC named %player_name% shows the filled name on its text display', async () => {
    const uuid = await server.npc('papiplayer', 'minecraft:player', -2.5, 61, 4.5, '%player_name%');
    const body = await owner.body(uuid);
    const ids = await riders(owner, body);
    await owner.wait(e => e.event === 'metadata' && ids.includes(e.id) && e.fields.some(f => typeof f.text === 'string'), body.sequence, 8000);
    const shown = ids.flatMap(id => texts(owner, id, body.sequence)).join('\n');
    assert.equal(shown, OWNER.name);
    return JSON.stringify(shown);
  });

  await check('placeholders: a standalone hologram line is filled', async () => {
    const from = owner.events.length;
    const file = path.join(data, 'gui/holograms/papi/board.json');
    mkdirSync(path.dirname(file), { recursive: true });
    writeFileSync(file, JSON.stringify({ schemaVersion: 3, revision: 1, anchor: { world: 'world', position: [0.5, 63, 6.5] }, lines: ['Players online: %server_online%'] }) + '\n');
    const seen = await owner.wait(e => e.event === 'metadata' && e.fields.some(f => typeof f.text === 'string' && f.text.includes('Players online')), from, 20_000);
    const text = seen.fields.find(f => typeof f.text === 'string').text;
    assert.equal(text, 'Players online: 1');
    return JSON.stringify(text);
  });

  await server.cmd('animus delete papiarmadillo', /Deleted/);
  await server.cmd('animus delete papiplayer', /Deleted/);
  await owner.close();
}
