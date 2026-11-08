// How /animus feels to a player, as in Iris: the chime while typing, the ding for an answer or a help page, the low
// tone for a refusal, the shared help menu and its page bar, coordinates from where the player stands, and
// /animus select glowing its NPC for the selecting player. Driven by live-features.mjs as the `commandfeel` scenario.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { delay } from '../protocol-client/client.mjs';

const SUCCESS = [['minecraft:block.amethyst_cluster.break', 0.77, 1.65], ['minecraft:block.respawn_anchor.charge', 0.125, 2.99]];
const FAILURE = [['minecraft:block.amethyst_cluster.break', 0.77, 0.25], ['minecraft:block.beacon.deactivate', 0.2, 0.45]];
const close = (a, b) => Math.abs(a - b) < 0.01;
/** Shared entity flags are entity data index 0; 0x40 is glowing. */
const SHARED_FLAGS = 0, GLOWING = 0x40;

/** `t` carries live-features' server, join, check, data and results helpers. */
export async function commandFeel(t) {
  const { server, join, check, data, results, OWNER } = t;
  const evidence = {};
  const owner = await join(OWNER.name, 'creative');
  await owner.tp(40.5, 61, 40.5);
  await delay(500);

  const sounds = from => owner.events.slice(from).filter(e => e.event === 'sound').map(e => [e.sound, e.volume, e.pitch]);
  const chats = from => owner.events.slice(from).filter(e => e.event === 'chat').map(e => ({ text: e.text, actions: e.actions }));
  async function heard(expected, from, timeout = 5000) {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      const got = sounds(from);
      if (expected.every(([sound, volume, pitch]) => got.some(([s, v, p]) => s === sound && close(v, volume) && close(p, pitch)))) return got;
      await delay(50);
    }
    throw new Error(`expected ${JSON.stringify(expected)}, heard ${JSON.stringify(sounds(from))}`);
  }
  /** Runs a command and returns what the player saw and heard once the expected sounds arrive. */
  async function run(label, command, match, expected) {
    const from = await owner.command(command);
    await owner.wait(e => e.event === 'chat' && match.test(e.text), from);
    const heardNow = await heard(expected, from);
    await delay(300);
    evidence[label] = { command, chat: chats(from), sounds: heardNow };
    return evidence[label];
  }

  await check('feel: /animus help shows the shared menu and dings', async () => {
    const seen = await run('help', '/animus help', /❭/, SUCCESS);
    const lines = seen.chat.map(c => c.text);
    assert(lines.some(l => /\(\(\( \/animus \)\)\)/.test(l)), `banner: ${lines.slice(0, 3)}`);
    assert(lines.some(l => l.startsWith('NPCs [Docs]')), 'topic heading');
    assert(lines.some(l => l.startsWith('⇀ create [id] ⊰type⊱')), 'entry chips');
    const create = seen.chat.find(c => c.text.startsWith('⇀ create'));
    assert(create.actions.some(a => a.startsWith('suggest /animus create \t') && a.includes('✎ Save a new despawned NPC')), JSON.stringify(create.actions));
    const bar = seen.chat.find(c => /Page 2 ❭$/.test(c.text));
    assert(bar && bar.actions.some(a => a.startsWith('run /animus page=2\tNext page')), JSON.stringify(bar));
    return `${lines.length} lines; bar "${bar.text.trim()}"`;
  });

  await check('feel: the page bar turns to page 2 with the ding', async () => {
    const seen = await run('page2', '/animus page=2', /〈 Page 1/, SUCCESS);
    const bar = seen.chat.find(c => c.text.startsWith('〈 Page 1'));
    assert(bar && bar.actions.some(a => a.startsWith('run /animus page=1\tPrevious page')), JSON.stringify(seen.chat.at(-1)));
    return `bar "${bar.text.trim()}"`;
  });

  await check('feel: typing chimes and offers commands and ~ ~ ~', async () => {
    let from = owner.events.length;
    owner.send('suggest', { id: 7, text: '/animus cr' });
    const names = await owner.wait(e => e.event === 'suggestions' && e.id === 7, from);
    const chime = await owner.wait(e => e.event === 'sound' && e.sound === 'minecraft:block.amethyst_block.chime', from);
    assert(names.texts.includes('create') && close(chime.volume, 0.25) && chime.pitch >= 0.125 && chime.pitch < 1.96, JSON.stringify([names, chime]));
    from = owner.events.length;
    owner.send('suggest', { id: 8, text: '/animus create feel ' });
    const position = await owner.wait(e => e.event === 'suggestions' && e.id === 8, from);
    // The client sorts suggestions, as vanilla does, so the keys come before ~ ~ ~.
    assert(position.texts.includes('~ ~ ~'), JSON.stringify(position.texts));
    evidence.typing = { names: names.texts, chime: [chime.sound, chime.volume, chime.pitch], position: position.texts };
    return `create offered, chime pitch ${chime.pitch.toFixed(2)}, then ${JSON.stringify(position.texts)}`;
  });

  await check('feel: create without coordinates places the NPC where the player stands, with the ding', async () => {
    await run('success', '/animus create feel', /Created dormant feel/, SUCCESS);
    const at = JSON.parse(readFileSync(path.join(data, 'npcs', 'feel.json'), 'utf8')).position;
    assert(at.world === 'world' && close(at.x, owner.position.x) && close(at.y, owner.position.y) && close(at.z, owner.position.z), JSON.stringify([at, owner.position]));
    await run('relative', '/animus create feel2 ~2 ~ ~-1', /Created dormant feel2/, SUCCESS);
    const moved = JSON.parse(readFileSync(path.join(data, 'npcs', 'feel2.json'), 'utf8')).position;
    assert(close(moved.x, owner.position.x + 2) && close(moved.z, owner.position.z - 1), JSON.stringify(moved));
    return `feel at ${at.x} ${at.y} ${at.z}; feel2 at ${moved.x} ${moved.y} ${moved.z}`;
  });

  await check('feel: a failing command shows red and sounds low', async () => {
    const seen = await run('failure', '/animus inspect nosuchnpc', /Unknown NPC/, FAILURE);
    return seen.chat[0].text;
  });

  await check('feel: /animus select picks the looked-at NPC and glows it for this player only', async () => {
    await server.cmd('animus delete feel', /Deleted/);
    await server.cmd('animus delete feel2', /Deleted/);
    const uuid = await server.npc('feelsel', 'minecraft:player', 40.5, 61, 45.5, 'Selectable');
    await server.npc('feelnear', 'minecraft:player', 38.5, 61, 40.5, 'Nearer');
    const body = await owner.body(uuid);
    await owner.look(body.x, body.y + 0.9, body.z);
    await delay(300);
    const from = owner.events.length;
    const seen = await run('select', '/animus select', /Selected feelsel/, SUCCESS);
    const flags = () => owner.events.slice(from).filter(e => e.event === 'metadata' && e.id === body.id)
      .flatMap(e => e.fields.filter(f => f.id === SHARED_FLAGS).map(f => ({ value: f.value, sequence: e.sequence })));
    const lit = await owner.wait(e => e.event === 'metadata' && e.id === body.id && e.fields.some(f => f.id === SHARED_FLAGS && (f.value & GLOWING)), from);
    const cleared = await owner.wait(e => e.event === 'metadata' && e.id === body.id && e.fields.some(f => f.id === SHARED_FLAGS && !(f.value & GLOWING)), lit.sequence + 1, 5000);
    const copy = seen.chat[0].actions.find(a => a.startsWith('copy feelsel'));
    assert(copy, JSON.stringify(seen.chat[0]));
    evidence.select.flags = flags();
    return `glow on at event ${lit.sequence}, off at ${cleared.sequence}; reply "${seen.chat[0].text}"`;
  });

  await check('feel: /animus select with nothing in range refuses with the low tone', async () => {
    await owner.tp(140.5, 61, 140.5);
    await delay(800);
    const seen = await run('selectNone', '/animus select', /No NPC in view or within 16 blocks\./, FAILURE);
    await server.cmd('animus delete feelsel', /Deleted/);
    await server.cmd('animus delete feelnear', /Deleted/);
    return seen.chat[0].text;
  });

  writeFileSync(path.join(results, 'command-feel-evidence.json'), JSON.stringify(evidence, null, 1) + '\n');
  await owner.close();
}
