// Movement styles as a viewer sees them: an NPC sneak-walks a route with the sneaking flag and crouching pose, a
// swimmer crosses water in the swimming pose, and an NPC with automatic crawling crawls through a gap one block high
// and stands up after it; an NPC in style fly flies its route to a point in the air with the normal pose, and glide covers
// the same route faster with the elytra flag. Driven by live-features.mjs (--scenarios movestyles).
import assert from 'node:assert/strict';
import { delay } from '../protocol-client/client.mjs';

/** Entity data indexes: the shared flags byte and the pose. */
const SHARED_FLAGS = 0, POSE = 6;
const SNEAKING = 0x02, SWIMMING = 0x10, GLIDING = 0x80;

/** Runs the sneak, swim and crawl checks; `t` carries live-features' server, client and check helpers. */
export async function movementStyles(t) {
  const { server, join, check, inspect, until, OWNER } = t;
  const owner = await join(OWNER.name, 'creative');
  const ground = 61, sx = -30, sz = -30;
  await owner.tp(sx + 6, ground, sz + 22);
  const route = (x, z, extra = {}) => ({ modules: { 'animus:navigation': { enabled: true, speed: 2, waitTicks: 0, points: [{ x, y: ground, z }], ...extra } } });
  const looks = (body, from) => owner.events.slice(from).filter(e => e.event === 'metadata' && e.id === body.id).flatMap(e => e.fields);
  const flagSeen = (body, from, bit) => looks(body, from).some(field => field.id === SHARED_FLAGS && (field.value & bit) !== 0);
  const poseSeen = (body, from, pose) => looks(body, from).some(field => field.id === POSE && field.value === pose);
  const lastPose = (body, from) => looks(body, from).filter(field => field.id === POSE).at(-1)?.value;
  const arrived = async (id, x) => until(async () => {
    const at = await inspect(id);
    return { ok: at.line.includes('navigation=arrived') && Math.abs(at.x - x) < 0.6, x: at.x, line: at.line.slice(0, 200) };
  }, 90_000, `${id} reaches x=${x}`);

  await check('styles: an NPC sneak-walks its route at sneak speed with the sneaking flag and crouching pose', async () => {
    const from = owner.events.length;
    const uuid = await server.npc('msneak', 'minecraft:player', sx + .5, ground, sz + .5, 'Sneaker', { movement: { style: 'sneak' }, ...route(sx + 12, sz) });
    const body = await owner.body(uuid);
    await until(async () => ({ ok: flagSeen(body, from, SNEAKING) && poseSeen(body, from, 'CROUCHING') }), 20_000, 'sneaking metadata');
    const start = await inspect('msneak');
    await delay(5_000);
    const later = await inspect('msneak');
    const speed = (later.x - start.x) / 5;
    await arrived('msneak', sx + 12);
    await delay(500);
    const standing = lastPose(body, from);
    await server.cmd('animus delete msneak', /Deleted/);
    assert(speed > 0.3 && speed < 1, `sneak speed ${speed.toFixed(2)} blocks/second, expected about 0.6`);
    assert.equal(standing, 'STANDING', 'the definition pose returns once the route ends');
    return `flag 0x02 and CROUCHING seen, ${speed.toFixed(2)} blocks/second, STANDING after arrival`;
  });

  await check('styles: a swimmer crosses water in the swimming pose', async () => {
    const z = sz + 8;
    await server.cmd(`fill ${sx + 4} ${ground - 3} ${z - 4} ${sx + 8} ${ground - 1} ${z + 4} water`, /Successfully filled/);
    const from = owner.events.length;
    const uuid = await server.npc('mswim', 'minecraft:player', sx + .5, ground, z + .5, 'Swimmer', { movement: { style: 'swim' }, ...route(sx + 12, z) });
    const body = await owner.body(uuid);
    await arrived('mswim', sx + 12);
    await delay(500);
    const swam = poseSeen(body, from, 'SWIMMING') && flagSeen(body, from, SWIMMING);
    const after = lastPose(body, from);
    await server.cmd('animus delete mswim', /Deleted/);
    await server.cmd(`fill ${sx + 4} ${ground - 3} ${z - 4} ${sx + 8} ${ground - 1} ${z + 4} stone`, /Successfully filled/);
    assert(swam, 'no SWIMMING pose with the swimming flag while crossing');
    assert.equal(after, 'STANDING');
    return 'SWIMMING pose and flag 0x10 in the water, STANDING on the far bank';
  });

  await check('styles: automatic crawling takes an NPC through a gap one block high and stands it up after', async () => {
    const z = sz + 16;
    // A wall five blocks thick with one tunnel one block high; only a crawling body passes the tunnel.
    await server.cmd(`fill ${sx + 4} ${ground} ${z - 8} ${sx + 8} ${ground + 3} ${z + 8} stone`, /Successfully filled/);
    await server.cmd(`fill ${sx + 4} ${ground} ${z} ${sx + 8} ${ground} ${z} air`, /Successfully filled/);
    const from = owner.events.length;
    const uuid = await server.npc('mcrawl', 'minecraft:player', sx + .5, ground, z + .5, 'Crawler', { movement: { auto: { crawl: true } }, ...route(sx + 12, z) });
    const body = await owner.body(uuid);
    await arrived('mcrawl', sx + 12);
    await delay(500);
    const crawled = poseSeen(body, from, 'SWIMMING');
    const after = lastPose(body, from);
    await server.cmd('animus delete mcrawl', /Deleted/);
    await server.cmd(`fill ${sx + 4} ${ground} ${z - 8} ${sx + 8} ${ground + 3} ${z + 8} air`, /Successfully filled/);
    assert(crawled, 'no crawling pose in the gap');
    assert.equal(after, 'STANDING', 'stands up after the gap');
    return 'SWIMMING (crawl) pose inside the gap, STANDING after it';
  });
  await check('styles: fly flies a route to a point in the air, and glide covers it faster with the elytra flag', async () => {
    const z = sz + 26, high = ground + 6.5, far = sx + 20;
    const air = { modules: { 'animus:navigation': { enabled: true, speed: 2, waitTicks: 0, points: [{ x: far, y: high, z }] } } };
    const timed = async (id, style) => {
      const from = owner.events.length;
      const uuid = await server.npc(id, 'minecraft:player', sx + .5, ground, z + .5, id, { movement: { style }, ...air });
      const body = await owner.body(uuid);
      const start = Date.now();
      const end = await until(async () => {
        const at = await inspect(id);
        return { ok: at.line.includes('navigation=arrived') && Math.abs(at.x - far) < 0.6 && Math.abs(at.y - high) < 0.6, x: at.x, y: at.y, line: at.line.slice(0, 200) };
      }, 90_000, `${id} reaches the air point`);
      const seconds = (Date.now() - start) / 1000;
      const glided = flagSeen(body, from, GLIDING);
      await server.cmd(`animus delete ${id}`, /Deleted/);
      return { seconds, glided, y: end.y };
    };
    const fly = await timed('mfly', 'fly');
    const glide = await timed('mglide', 'glide');
    assert(!fly.glided, 'fly keeps the normal pose');
    assert(glide.glided, 'glide shows the elytra flag');
    assert(glide.seconds < fly.seconds * 0.75, `glide ${glide.seconds.toFixed(1)}s against fly ${fly.seconds.toFixed(1)}s`);
    return `fly reached y=${fly.y.toFixed(1)} in ${fly.seconds.toFixed(1)}s, glide in ${glide.seconds.toFixed(1)}s with flag 0x80`;
  });
  await owner.close();
}
