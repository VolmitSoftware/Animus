#!/usr/bin/env node
// Runs triggers, durable state, actions, mounts, riding and dragons on one real isolated managed server,
// acting as players through the native-codec client. Prints one line per check; details go to --results.
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { appendFileSync, copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, realpathSync, rmSync, unlinkSync, writeFileSync } from 'node:fs';
import { createServer, connect } from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { NativeClient, prepareNative, parseArgs, delay } from '../protocol-client/client.mjs';

const args = parseArgs();
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const project = args.get('project') ?? 'paper', version = args.get('version') ?? '26.3';
const results = path.resolve(args.get('results') ?? path.join(root, '.qa', 'live-features'));
// The one shared Multiplexor workspace (VolmitSoftware/AGENTS.md): the nearest ServerMultiplexor sibling, also from a nested worktree.
function mainWorkspace() {
  for (let parent = path.dirname(root); ; parent = path.dirname(parent)) {
    if (existsSync(path.join(parent, 'ServerMultiplexor', 'multiplexor'))) return path.join(parent, 'ServerMultiplexor');
    if (parent === path.dirname(parent)) return path.join(path.dirname(root), 'ServerMultiplexor');
  }
}
const workspace = args.get('workspace') === 'main' || !args.get('workspace') ? mainWorkspace() : path.resolve(args.get('workspace'));
const binary = path.resolve(args.get('binary') ?? path.join(mainWorkspace(), 'multiplexor'));
const rootArgs = ['--root', workspace];
// Multiplexor runs from its own workspace folder so a missing root can never create a stray workspace elsewhere.
const MAIN = true;
const buildHome = args.get('build-home') ? path.resolve(args.get('build-home')) : null;
const plugin = path.resolve(args.get('plugin'));
const fixture = args.get('fixture') ? path.resolve(args.get('fixture')) : null;
const javaHome = args.get('java-home') ?? '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home';
// Fabric, NeoForge and Forge run the mod jar on a vanilla server: data lives in config/animus and no Bukkit fixture exists.
const MOD = ['fabric', 'neoforge', 'forge'].includes(project);
const consumer = MOD ? project : 'plugin';
const FULL = 'triggers,state,actions,mounts,riding,dragon,terrain,combat,packs,appearance,building,shops';
const defaultScenarios = project === 'folia' ? 'folia' : MOD ? (version === '26.3' ? `${FULL},worlds` : 'smoke') : FULL;
const scenarios = (args.get('scenarios') ?? defaultScenarios).split(',');
const dashed = version.replaceAll('.', '-');
const name = args.get('instance-name') ?? (MOD ? `animus-mod-${project}-${dashed}` : `animus-live-${project}-${dashed}`);
assert(new RegExp(MOD ? `^animus-mod-${project}-${dashed}$` : `^animus-(?:live|gate-live|guarddebug)-${project}-${dashed}$`).test(name), 'Instance name must bind the requested platform/version');
assert(!MOD || !fixture, 'The QA event fixture is a Bukkit plugin; mod servers run without it');
const instances = path.join(workspace, `consumers/${MOD ? `${project}-mod` : 'plugin'}-consumers/instances`);
const pluginInstances = path.join(workspace, 'consumers/plugin-consumers/instances');
const instance = path.join(instances, name), data = path.join(instance, MOD ? 'config/animus' : 'plugins/Animus'), logFile = path.join(instance, 'logs/latest.log');
const session = `mc-${consumer}-${name}`;
// The native client needs vanilla's libraries; a mod server keeps them inside its loader, so borrow the exact Paper template's.
const serverLibraries = MOD ? path.join(pluginInstances, `animus-baseline-paper-${dashed}`, 'libraries') : path.join(instance, project === 'spigot' ? 'bundler/libraries' : 'libraries');
const LOADERS = JSON.parse(readFileSync(path.join(root, 'gradle/targets.json'), 'utf8'))[version];
const loaderVersion = { fabric: LOADERS.fabricLoader, neoforge: LOADERS.neoforge, forge: LOADERS.forge?.replace(`${version}-`, '') }[project];
const OWNER = { uuid: 'b2b7d4ea-c517-3a19-8a16-ee03e26cbe9f', name: 'AnimusProofQA' };
const PEER = { uuid: '262b16de-d06b-325b-b115-4ad978f962f2', name: 'AnimusProofPeer' };
// A detach to a player who already left is an ordinary failed send, never a warning.
const FORBIDDEN = /moved too quickly|moved wrongly|Flying is not enabled|was kicked|Rider detach failed/i;
mkdirSync(results, { recursive: true });
const driverLog = path.join(results, 'driver.log');
const note = text => appendFileSync(driverLog, `${new Date().toISOString()} ${text}\n`);
let passedCount = 0, failedCount = 0;
const failures = [];

async function check(label, body) {
  try {
    const detail = await body();
    passedCount++;
    const line = `PASS ${label}${detail ? ` | ${String(detail).slice(0, 300)}` : ''}`;
    console.log(line); note(line);
    return true;
  } catch (error) {
    failedCount++;
    failures.push(label);
    const line = `FAIL ${label} | ${String(error.message ?? error).slice(0, 700)}`;
    console.log(line); note(line);
    return false;
  }
}

function muxEnvironment() {
  const environment = { ...process.env, JAVA_HOME: javaHome };
  if (buildHome) environment.MULTIPLEXOR_BUILD_HOME = buildHome;
  return environment;
}
// Multiplexor serializes workspace commands; a concurrent target's command makes this one retry briefly.
const BUSY = /Another network command is running|Another project operation owns the workspace/;
function mux(...command) {
  const deadline = Date.now() + 30_000;
  let done = spawnSync(binary, [...rootArgs, '--consumer', consumer, ...command], { encoding: 'utf8', env: muxEnvironment(), cwd: MAIN ? workspace : undefined });
  while (done.status && BUSY.test(done.stderr + done.stdout) && Date.now() < deadline) {
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 250);
    done = spawnSync(binary, [...rootArgs, '--consumer', consumer, ...command], { encoding: 'utf8', env: muxEnvironment(), cwd: MAIN ? workspace : undefined });
  }
  if (done.status) throw new Error(`multiplexor ${command.slice(0, 2).join(' ')}: ${(done.stderr || done.stdout).slice(0, 400)}`);
  return done.stdout;
}
const freePort = () => new Promise(resolve => { const s = createServer(); s.listen(0, '127.0.0.1', () => { const { port } = s.address(); s.close(() => resolve(port)); }); });
const portOpen = port => new Promise(resolve => { const s = connect(port, '127.0.0.1'); s.on('connect', () => { s.destroy(); resolve(true); }); s.on('error', () => resolve(false)); });
const logText = () => existsSync(logFile) ? readFileSync(logFile, 'utf8') : '';

class Server {
  port = 0;
  logs = 0;
  revisions = new Map();
  // Terrain a scenario keeps across restarts; start() clears the test area and then rebuilds this.
  floor = null;
  provision() {
    const template = args.get('template') ? path.resolve(args.get('template')) : path.join(pluginInstances, `animus-baseline-${MOD ? 'paper' : project}-${dashed}`);
    if (!existsSync(instance)) {
      if (MOD) mux('server', 'create', name, '--type', project, '--mc', version, '--loader', loaderVersion, '--auto-build', '--isolated');
      else mux('server', 'create', name, '--jar', realpathSync(path.join(template, 'server.jar')), '--type', project, '--mc', version, '--isolated');
      mux('runtime', 'settings', 'set-java', path.join(javaHome, 'bin/java'), '--instance', name);
      mux('runtime', 'settings', 'set-heap', '1G', '--instance', name);
      mux('runtime', 'settings', 'set-preset', 'vanilla', '--instance', name);
      mux('gameplay', 'prepare', name);
    }
    assert.equal(mux('instance', 'isolated', name).trim(), 'true', 'Refusing a shared instance');
    writeFileSync(path.join(instance, 'eula.txt'), existsSync(path.join(template, 'eula.txt')) ? readFileSync(path.join(template, 'eula.txt')) : 'eula=true\n');
    const plugins = path.join(instance, MOD ? 'mods' : 'plugins');
    // Every run starts from empty plugin data so no earlier revision, checkpoint or state answers for this run.
    assert(data.startsWith(instances) && /^animus-(?:(?:gate-)?live|guarddebug|mod)-/.test(path.basename(instance)));
    rmSync(data, { recursive: true, force: true });
    mkdirSync(data, { recursive: true });
    mkdirSync(plugins, { recursive: true });
    for (const entry of readdirSync(plugins)) if (/^Animus.*\.jar$/.test(entry)) unlinkSync(path.join(plugins, entry));
    copyFileSync(plugin, path.join(plugins, path.basename(plugin)));
    // The QA event fixture adds animusqa commands that qa/combat-client.mjs needs.
    if (fixture) copyFileSync(fixture, path.join(plugins, path.basename(fixture)));
    const cached = path.join(process.env.HOME, '.gradle/caches/fabric-loom', version, 'minecraft-server.jar');
    if (!MOD) mkdirSync(path.join(instance, 'cache'), { recursive: true });
    if (!MOD && existsSync(cached)) copyFileSync(cached, path.join(instance, 'cache', `mojang_${version}.jar`));
    writeFileSync(path.join(instance, 'ops.json'), JSON.stringify([{ ...OWNER, level: 4, bypassesPlayerLimit: false }]) + '\n');
    writeFileSync(path.join(instance, 'whitelist.json'), JSON.stringify([OWNER, PEER]) + '\n');
    this.config();
  }
  config(extra = '') {
    writeFileSync(path.join(data, 'config.toml'), `statusMessage = "Live QA"\n${extra}`);
  }
  async properties() {
    this.port = await freePort();
    mux('instance', 'port', name, String(this.port));
    const file = path.join(instance, 'server.properties');
    const overrides = { 'server-ip': '127.0.0.1', 'online-mode': 'false', 'allow-flight': 'false', 'level-type': 'minecraft:flat', 'generator-settings': JSON.stringify({ layers: [{ block: 'minecraft:bedrock', height: 1 }, { block: 'minecraft:stone', height: 59 }, { block: 'minecraft:grass_block', height: 1 }], biome: 'minecraft:plains' }), 'generate-structures': 'false', 'view-distance': '3', 'simulation-distance': '3', 'max-players': '8', 'spawn-protection': '0', 'pause-when-empty-seconds': '-1', 'server-port': String(this.port) };
    const existing = existsSync(file) ? readFileSync(file, 'utf8').split('\n') : [];
    writeFileSync(file, existing.filter(line => !(line.split('=')[0] in overrides)).join('\n') + '\n' + Object.entries(overrides).map(([k, v]) => `${k}=${v}`).join('\n') + '\n');
  }
  async start() {
    if (existsSync(logFile)) { copyFileSync(logFile, path.join(results, `${name}-${++this.logs}.log`)); unlinkSync(logFile); }
    mux('runtime', 'start', name, '--no-console');
    await this.waitLog(/Done \([\d.]+s\)!/, 0, 180_000);
    if (project === 'folia') return;
    await this.cmd('gamerule minecraft:spawn_mobs false', /spawn_mobs/);
    await this.cmd('gamerule minecraft:advance_time false', /advance_time/);
    await this.cmd('time set day', /time/i);
    await this.cmd('difficulty peaceful', /difficulty/i);
    await this.cmd('forceload add -48 -48 48 48', /Marked|already|No chunks/);
    await delay(1000);
    await this.cmd('fill -40 60 -40 40 60 40 stone', /Successfully filled|No blocks were filled|No blocks/);
    for (const [low, high] of [[61, 64], [65, 68]]) await this.cmd(`fill -40 ${low} -40 40 ${high} 40 air`, /Successfully filled|No blocks were filled|No blocks/);
    if (this.floor) await this.cmd(this.floor, /Successfully filled|No blocks were filled/);
    await this.cmd('setworldspawn 0 61 0', /world spawn/i);
  }
  waitLog(pattern, from = 0, timeout = 20_000) {
    const deadline = Date.now() + timeout;
    return new Promise((resolve, reject) => {
      const poll = () => {
        const found = logText().slice(from).match(pattern);
        if (found) return resolve(found);
        if (Date.now() > deadline) return reject(new Error(`log timeout ${pattern}; tail: ${logText().slice(-300)}`));
        setTimeout(poll, 75);
      };
      poll();
    });
  }
  async cmd(text, pattern, timeout = 20_000) {
    const from = logText().length;
    spawnSync('tmux', ['send-keys', '-t', session, '-l', text]);
    spawnSync('tmux', ['send-keys', '-t', session, 'Enter']);
    if (!pattern) { await delay(150); return null; }
    return this.waitLog(pattern, from, timeout);
  }
  async capture(text, pattern, settle = 300) {
    const from = logText().length;
    await this.cmd(text, pattern);
    await delay(settle);
    return logText().slice(from);
  }
  pidOf() { return spawnSync('lsof', ['-ti', `tcp:${this.port}`, '-sTCP:LISTEN'], { encoding: 'utf8' }).stdout.trim(); }
  async stop() {
    const from = logText().length, pid = this.pidOf();
    // Look at the live JVM right after plugin disable: an Animus task or thread left behind would show here.
    const threads = new Promise(resolve => {
      const poll = setInterval(() => {
        // Bukkit logs the plugin disable; a mod server closes Animus on SERVER_STOPPING, before the players are saved.
        if (MOD ? /Saving players/.test(logText().slice(from)) : logText().slice(from).includes('Disabling Animus')) {
          clearInterval(poll);
          setTimeout(() => {
            const dump = spawnSync(path.join(javaHome, 'bin/jcmd'), [pid, 'Thread.print'], { encoding: 'utf8' }).stdout ?? '';
            const names = dump.split('\n').filter(line => line.startsWith('"')).map(line => line.split('"')[1]);
            // A small mod server can finish exiting before the dump attaches; an exited JVM left nothing behind.
            resolve(names.length ? { jvmThreads: names.length, animus: names.filter(threadName => /animus/i.test(threadName)) } : { jvmExited: true });
          }, MOD ? 50 : 400);
        }
      }, 25);
      setTimeout(() => { clearInterval(poll); resolve(null); }, 90_000);
    });
    const child = spawn(binary, [...rootArgs, '--consumer', consumer, 'runtime', 'stop', name, '--graceful'], { env: muxEnvironment(), stdio: 'ignore', cwd: MAIN ? workspace : undefined });
    const closed = new Promise(resolve => child.on('exit', resolve));
    const leftover = await threads;
    await closed;
    note(`threads after disable: ${JSON.stringify(leftover)}`);
    this.lastThreads = leftover;
    assert.equal(await portOpen(this.port), false, 'Server port still open after stop');
    return logText();
  }
  async kill9() {
    const pid = this.pidOf();
    assert.match(pid, /^\d+$/, 'No listening pid to kill');
    process.kill(Number(pid), 'SIGKILL');
    for (let i = 0; i < 100 && await portOpen(this.port); i++) await delay(100);
    try { mux('runtime', 'stop', name, '--force'); } catch (error) { note(`force stop: ${error.message}`); }
    return pid;
  }
  behavior(id, document) {
    const [namespace, ...rest] = id.split(':');
    const file = path.join(data, 'behaviors', namespace, ...`${rest.join(':')}.json`.split('/'));
    mkdirSync(path.dirname(file), { recursive: true });
    const revision = (this.revisions.get(id) ?? 0) + 1;
    this.revisions.set(id, revision);
    writeFileSync(file, JSON.stringify({ schemaVersion: 1, revision, id, entry: 'main', ...document }, null, 1) + '\n');
    return file;
  }
  async reload() {
    for (let i = 0; i < 20; i++) {
      const found = await this.cmd('animus behaviors reload', /Behaviors reload: (\w+)[^\n]*/);
      if (found[1] !== 'BUSY') return found;
      await delay(250);
    }
    throw new Error('reload stayed busy');
  }
  async npc(id, type, x, y, z, display, edit = {}, spawned = true) {
    await this.cmd(`animus create ${id} ${type} world ${x} ${y} ${z} live ${display}`, new RegExp(`Created dormant ${id}`));
    if (Object.keys(edit).length) await this.cmd(`animus edit ${id} ${JSON.stringify(edit)}`, /Saved; transaction/);
    if (spawned) await this.cmd(`animus spawn ${id}`, new RegExp(`Spawn requested for ${id}`));
    return JSON.parse(readFileSync(path.join(data, 'npcs/live', `${id}.json`), 'utf8')).uuid;
  }
  async finish(execution, timeout = 30_000) {
    const deadline = Date.now() + timeout;
    let text = '';
    while (Date.now() < deadline) {
      text = await this.capture(`animus behaviors inspect ${execution}`, new RegExp(`Behavior (execution=${execution}|inspect:)[^\n]*`), 120);
      const state = text.match(/\| state=(\w+)/)?.[1];
      if (state && state !== 'RUNNING') return { state, text };
      await delay(150);
    }
    throw new Error(`execution ${execution} did not finish: ${text.slice(0, 300)}`);
  }
  async runConsole(npcId, behavior) {
    const found = await this.cmd(`animus behaviors run ${npcId} ${behavior}`, /Behavior run: (\w+)[^\n]*/);
    return found[0].match(/execution=([0-9a-f-]{36})/)?.[1] ?? found[0];
  }
}

const server = new Server();
let harness;

class Player extends NativeClient {
  constructor(username) { super(harness, username); }
  current(uuid) {
    const spawned = this.events.filter(e => e.event === 'spawn' && e.uuid === uuid).at(-1);
    if (!spawned) return null;
    return this.events.some(e => e.event === 'remove' && e.sequence > spawned.sequence && e.ids.includes(spawned.id)) ? null : spawned;
  }
  async body(uuid, timeout = 20_000) {
    await this.wait(e => e.event === 'spawn' && e.uuid === uuid && this.current(uuid)?.id === e.id, 0, timeout);
    return this.current(uuid);
  }
  async tp(x, y, z, yaw = 0, pitch = 0) {
    const from = this.events.length;
    await server.cmd(`tp ${this.username} ${x.toFixed(1)} ${y.toFixed(1)} ${z.toFixed(1)} ${yaw} ${pitch}`, /Teleported/);
    await this.wait(e => e.event === 'position' && Math.abs(e.x - x) < .01 && Math.abs(e.y - y) < .01 && Math.abs(e.z - z) < .01, from).catch(() => {});
    await delay(200);
  }
  async walk(x, z) {
    for (let guard = 0; guard < 400; guard++) {
      const dx = x - this.position.x, dz = z - this.position.z, length = Math.hypot(dx, dz);
      if (length < .05) return;
      const step = Math.min(.4, length);
      const from = this.events.length;
      this.send('move', { x: this.position.x + dx / length * step, y: this.position.y, z: this.position.z + dz / length * step });
      await this.wait(e => e.event === 'movement_sent', from, 2000);
      this.position = { ...this.position, x: this.position.x + dx / length * step, z: this.position.z + dz / length * step };
      await delay(60);
    }
    throw new Error('walk did not arrive');
  }
  async look(x, y, z) {
    const from = this.events.length;
    this.send('look', { x, y, z });
    await this.wait(e => e.event === 'look', from);
    await delay(150);
  }
  async keys(keys = {}) {
    const from = this.events.length;
    this.send('input', keys);
    await this.wait(e => e.event === 'input', from);
  }
  chat(match, from = 0, timeout = 15_000) {
    return this.wait(e => e.event === 'chat' && (typeof match === 'string' ? e.text.includes(match) : match.test(e.text)), from, timeout);
  }
  async noChat(match, from, settle = 800) {
    await delay(settle);
    const found = this.events.slice(from).find(e => e.event === 'chat' && (typeof match === 'string' ? e.text.includes(match) : match.test(e.text)));
    assert(!found, `Unexpected chat: ${found?.text}`);
  }
  has(test, from = 0) { return this.events.slice(from).some(test); }
  async run(npcId, behavior) {
    const reply = await this.command(`animus behaviors run ${npcId} ${behavior}`, /Behavior run: /);
    const execution = reply.text.match(/execution=([0-9a-f-]{36})/)?.[1];
    assert(execution, reply.text);
    return execution;
  }
}
async function join(username, gamemode = 'creative') {
  const player = new Player(username);
  await player.ready();
  if (gamemode) await server.cmd(`gamemode ${gamemode} ${username}`, /gamemode|game mode|Set /i).catch(() => {});
  return player;
}
const say = (text, extra = {}) => ({ type: 'action', capability: 'animus:say', arguments: { text, audience: 'nearby', radius: 64, ...extra } });
const sayTo = text => ({ type: 'action', capability: 'animus:say', arguments: { text } });
const action = (capability, parameters = {}) => ({ type: 'action', capability, arguments: parameters });
const seq = (...steps) => ({ type: 'sequence', steps });

async function triggers() {
  server.behavior('live:triggers', {
    routines: {
      main: say('manual-fired'), clicked: sayTo('click-fired'), hit: sayTo('hit-fired'),
      near: sayTo('near-fired'), away: sayTo('away-fired'), spawned: say('spawn-fired'),
      timer: say('timer-fired'), arrived: say('waypoint-fired'),
      despawned: seq(action('animus:set-flag', { name: 'despawned', scope: 'global' }), say('despawn-fired')),
    },
    triggers: [
      { id: 'click', event: 'interaction', routine: 'clicked', cooldownTicks: 0 },
      { id: 'hit', event: 'interaction', action: 'attack', routine: 'hit', cooldownTicks: 0 },
      { id: 'near', event: 'proximity', phase: 'enter', radius: 6, routine: 'near', cooldownTicks: 0 },
      { id: 'away', event: 'proximity', phase: 'leave', radius: 6, routine: 'away', cooldownTicks: 0 },
      { id: 'spawn', event: 'spawn', routine: 'spawned' },
      { id: 'despawn', event: 'despawn', routine: 'despawned' },
      { id: 'clock', event: 'timer', intervalTicks: 60, routine: 'timer', cooldownTicks: 0 },
      { id: 'walked', event: 'waypoint', routine: 'arrived', cooldownTicks: 0 },
    ],
  });
  await server.reload();
  const owner = await join(OWNER.name);
  await owner.tp(0, 61, -8);
  const start = owner.events.length;
  const uuid = await server.npc('trig', 'minecraft:player', 0, 61, 10, 'Trigger', { behaviors: ['live:triggers'] });
  const body = await owner.body(uuid);
  await check('trigger spawn', async () => (await owner.chat('Trigger » spawn-fired', start)).text);
  await check('trigger timer', async () => { await owner.chat('Trigger » timer-fired', start, 10_000); const from = owner.events.length; return (await owner.chat('Trigger » timer-fired', from, 10_000)).text; });
  await check('trigger proximity enter', async () => { const from = owner.events.length; await owner.walk(0, 6); return (await owner.chat('Trigger » near-fired', from)).text; });
  await check('trigger proximity leave', async () => { const from = owner.events.length; await owner.walk(0, 2); return (await owner.chat('Trigger » away-fired', from)).text; });
  await owner.walk(0, 7);
  await check('trigger interaction', async () => {
    await owner.look(body.x, body.y + 0.9, body.z);
    const from = owner.events.length; owner.send('use', { id: body.id });
    const fired = await owner.chat('Trigger » click-fired', from);
    await owner.noChat('Trigger: hit-fired', from, 400);
    return fired.text;
  });
  await check('trigger attack action', async () => {
    const from = owner.events.length; owner.send('attack', { id: body.id });
    return (await owner.chat('Trigger » hit-fired', from)).text;
  });
  await check('trigger waypoint arrival', async () => {
    const from = owner.events.length;
    await server.cmd('animus navigate trig 4 61 10 walk 3', /./);
    return (await owner.chat('Trigger » waypoint-fired', from, 15_000)).text;
  });
  await check('behaviors triggers output', async () => {
    const text = await server.capture('animus behaviors triggers', /Behavior triggers: AVAILABLE[^\n]*/);
    note(text);
    const rows = text.split('\n').filter(line => line.includes('Trigger npc=trig'));
    assert.equal(rows.length, 8, `rows: ${rows.length}`);
    for (const id of ['click', 'hit', 'near', 'away', 'spawn', 'clock', 'walked']) {
      const row = rows.find(line => line.includes(`trigger=${id} `));
      assert(row && /fired=[1-9]/.test(row), `${id}: ${row}`);
    }
    return text.match(/Behavior triggers:[^\n]*/)[0];
  });
  await check('trigger despawn', async () => {
    const from = owner.events.length;
    await server.cmd('animus despawn trig', /./);
    await owner.wait(e => e.event === 'remove' && e.ids.includes(body.id), from);
    await delay(800);
    const trace = await server.capture(`animus behaviors trace ${uuid}`, /Behavior trace:[^\n]*/);
    note(trace);
    const row = trace.split('\n').find(line => line.includes('trigger=live:triggers#despawn'));
    assert(row, `no despawn run in trace: ${trace.slice(0, 500)}`);
    assert(await flagStored('global', 'live', 'despawned'), 'flag not stored');
    // The world action after the flag cannot reach a dormant NPC; record how that is reported.
    const execution = row.match(/execution=([0-9a-f-]{36})/)[1];
    const inspect = await server.finish(execution);
    note(inspect.text);
    return `${row.slice(row.indexOf('Behavior recent')).slice(0, 200)} ; say after despawn: ${diagnosticOf(inspect.text).replace(/^.*Behavior diagnostic/, 'diagnostic').slice(0, 200)}`;
  });
  await check('dormant NPC fires nothing', async () => {
    const from = owner.events.length;
    await delay(4000);
    assert(!owner.has(e => e.event === 'chat' && e.text.includes('timer-fired'), from));
  });
  await owner.close();
  await server.cmd('animus delete trig', /Deleted/);
}
async function flagStored(scope, namespace, flag) {
  const deadline = Date.now() + 8000;
  const file = path.join(data, 'state/behavior-state', scope, 'animus', 'flags', `${namespace}.json`);
  while (Date.now() < deadline) {
    if (existsSync(file) && readFileSync(file, 'utf8').includes(flag)) return true;
    await delay(200);
  }
  return false;
}

const VARIABLES = {
  n: { type: 'string', initial: 'unset', scope: 'npc', lifetime: 'persistent' },
  p: { type: 'string', initial: 'unset', scope: 'player', lifetime: 'persistent' },
  r: { type: 'string', initial: 'unset', scope: 'relationship', lifetime: 'persistent' },
  g: { type: 'string', initial: 'unset', scope: 'global', lifetime: 'persistent' },
  t: { type: 'string', initial: 'unset', scope: 'npc', lifetime: 'transient' },
};
const report = variable => ({ type: 'action', capability: 'animus:say', arguments: { text: null }, bindings: { '/text': { variable } } });
const setAll = value => ['n', 'p', 'r', 'g', 't'].map(variable => ({ type: 'set', variable, value }));
function stateDoc() {
  return {
    variables: VARIABLES,
    routines: {
      main: seq(report('n'), report('p'), report('r'), report('g'), report('t'), sayTo('report-end')),
      write: seq(...setAll('one'), sayTo('write-one')),
      killable: seq(...setAll('two'), sayTo('write-two'), { type: 'wait', ticks: 140 }, ...setAll('three'), sayTo('write-three'), { type: 'wait', ticks: 2000 }, sayTo('resumed-after-kill')),
    },
    triggers: [{ id: 'w', event: 'interaction', routine: 'write', cooldownTicks: 0 }, { id: 'k', event: 'interaction', action: 'attack', routine: 'killable', cooldownTicks: 0 }],
  };
}
async function values(owner, npc = 'st') {
  const from = owner.events.length;
  const execution = await owner.run(npc, 'live:state');
  await owner.chat('report-end', from);
  const lines = owner.events.slice(from).filter(e => e.event === 'chat' && e.text.startsWith('State: ')).map(e => e.text.slice(7));
  return { lines: lines.filter(line => line !== 'report-end'), execution };
}
async function state() {
  server.behavior('live:state', stateDoc());
  await server.reload();
  let owner = await join(OWNER.name);
  await owner.tp(0, 61, 0);
  const uuid = await server.npc('st', 'minecraft:player', 0, 61, 3, 'State', { behaviors: ['live:state'] });
  let body = await owner.body(uuid);
  const stateRoot = path.join(data, 'state/behavior-state');
  const files = {
    n: path.join(stateRoot, 'npc', uuid, 'live/state.json'), p: path.join(stateRoot, 'player', OWNER.uuid, 'live/state.json'),
    r: path.join(stateRoot, 'relationship', uuid, OWNER.uuid, 'live/state.json'), g: path.join(stateRoot, 'global/live/state.json'),
  };
  await check('state initial values before any write', async () => { const { lines } = await values(owner); assert.deepEqual(lines, ['unset', 'unset', 'unset', 'unset', 'unset']); });
  await check('state written to disk while running', async () => {
    await owner.look(body.x, body.y + .9, body.z);
    const from = owner.events.length; owner.send('use', { id: body.id });
    await owner.chat('write-one', from);
    const deadline = Date.now() + 9000;
    while (Date.now() < deadline && !Object.values(files).every(existsSync)) await delay(100);
    for (const [key, file] of Object.entries(files)) { assert(existsSync(file), `${key} missing at ${file}`); assert(readFileSync(file, 'utf8').includes('one'), key); }
    return 'npc, player, relationship and global documents on disk before any shutdown';
  });
  await check('transient value not stored', async () => { const stored = Object.values(files).map(file => readFileSync(file, 'utf8')).join(''); assert(!/"t"/.test(stored), stored.slice(0, 200)); });
  await owner.close(); await delay(300);
  await server.stop();
  await server.start();
  owner = await join(OWNER.name);
  await owner.tp(0, 61, 0);
  body = await owner.body(uuid);
  await check('persistent values survive graceful restart, transient does not', async () => { const { lines } = await values(owner); assert.deepEqual(lines, ['one', 'one', 'one', 'one', 'unset']); return lines.join(','); });
  await check('kill -9 mid-run recovers without corruption', async () => {
    await owner.look(body.x, body.y + .9, body.z);
    const from = owner.events.length; owner.send('attack', { id: body.id });
    await owner.chat('write-two', from);
    await owner.chat('write-three', from, 12_000);
    await delay(500);
    owner.closing = true;
    const pid = await server.kill9();
    await owner.close();
    const stored = Object.fromEntries(Object.entries(files).map(([key, file]) => [key, JSON.parse(readFileSync(file, 'utf8'))]));
    note(`stored after kill: ${JSON.stringify(stored).slice(0, 400)}`);
    await server.start();
    owner = await join(OWNER.name);
    await owner.tp(0, 61, 0);
    body = await owner.body(uuid);
    const { lines } = await values(owner);
    assert.equal(lines[4], 'unset');
    assert(lines.slice(0, 4).every(value => value === 'two' || value === 'three'), lines.join(','));
    const status = await server.capture('animus behaviors state status', /Behavior state:[^\n]*/);
    assert(!status.includes('Behavior state invalid'), status);
    await owner.noChat('resumed-after-kill', 0, 300);
    return `killed pid ${pid}; values after: ${lines.join(',')}; ${status.match(/Behavior state:[^\n]*/)[0].replace(/^.*Behavior state: /, '').slice(0, 120)}`;
  });
  await check('state status reports no problem', async () => { const text = await server.capture('animus behaviors state status', /Behavior state:[^\n]*/); note(text); assert(!text.includes('Behavior state invalid')); return text.match(/Behavior state:[^\n]*/)[0]; });
  await check('trace and inspect after a run', async () => {
    const { execution } = await values(owner);
    const trace = await server.capture('animus behaviors trace st', /Behavior trace: npc=st[^\n]*/);
    assert(trace.includes(execution), `trace lacks ${execution}: ${trace.slice(0, 400)}`);
    assert(/state=COMPLETED/.test(trace));
    const { state: terminal, text } = await server.finish(execution);
    assert.equal(terminal, 'COMPLETED');
    note(text);
    return text.match(/Behavior execution=[^\n]*/)[0].slice(0, 200);
  });
  await check('malformed edit keeps last valid while running', async () => {
    const file = server.behavior('live:state', stateDoc());
    writeFileSync(file, readFileSync(file, 'utf8').replace('"entry": "main"', '"entry": "nowhere"'));
    const text = await server.capture('animus behaviors reload', /Behaviors reload: \w+[^\n]*/);
    note(text);
    assert(/nowhere|\$\.entry/.test(text), text.slice(0, 400));
    const { lines } = await values(owner);
    assert.equal(lines.length, 5);
    return text.match(/Behaviors reload:[^\n]*/)[0].slice(0, 160);
  });
  await owner.close(); await delay(300);
  await server.stop();
  await check('malformed edit keeps last valid across restart', async () => {
    const file = server.behavior('live:state', stateDoc());
    writeFileSync(file, '{"schemaVersion": 1, "revision": 99, broken');
    await server.start();
    const startup = logText().split('\n').filter(line => /live:state|live\/state/.test(line)).slice(0, 3).join(' / ');
    assert(startup, 'startup log did not report the bad document');
    owner = await join(OWNER.name);
    await owner.tp(0, 61, 0);
    body = await owner.body(uuid);
    const { lines } = await values(owner);
    assert.equal(lines.length, 5);
    const validate = await server.capture('animus behaviors validate', /Behaviors validate: \w+[^\n]*/);
    note(validate);
    assert(/INVALID|live/.test(validate), validate.slice(0, 300));
    server.revisions.set('live:state', 99);
    server.behavior('live:state', stateDoc());
    assert.match((await server.reload())[0], /APPLIED/);
    return startup.replace(/\[[^\]]*\]: /g, '').slice(0, 240);
  });
  await check('state purge relationship', async () => { await server.capture(`animus behaviors state purge relationship st ${OWNER.uuid}`, /Behavior state purge: \w+[^\n]*/); const { lines } = await values(owner); assert.equal(lines[2], 'unset'); assert.notEqual(lines[0], 'unset'); return lines.join(','); });
  await check('state purge npc', async () => { await server.capture('animus behaviors state purge npc st', /Behavior state purge: \w+[^\n]*/); const { lines } = await values(owner); assert.equal(lines[0], 'unset'); return lines.join(','); });
  await check('state purge player', async () => { await server.capture(`animus behaviors state purge player ${OWNER.uuid}`, /Behavior state purge: \w+[^\n]*/); const { lines } = await values(owner); assert.equal(lines[1], 'unset'); return lines.join(','); });
  await check('state purge behavior', async () => { await server.capture('animus behaviors state purge behavior live:state', /Behavior state purge: \w+[^\n]*/); const { lines } = await values(owner); assert.equal(lines[3], 'unset'); return lines.join(','); });
  await check('state purge all', async () => {
    await owner.look(body.x, body.y + .9, body.z);
    const from = owner.events.length; owner.send('use', { id: body.id }); await owner.chat('write-one', from);
    const text = await server.capture('animus behaviors state purge all confirm', /Behavior state purge: \w+[^\n]*/);
    const { lines } = await values(owner);
    assert.deepEqual(lines, ['unset', 'unset', 'unset', 'unset', 'unset']);
    await delay(6000);
    assert(!Object.values(files).some(existsSync), 'files remain after purge all');
    return text.match(/Behavior state purge:[^\n]*/)[0];
  });
  await owner.close();
  await server.cmd('animus delete st', /Deleted/);
}

async function runDoc(owner, id, routine, extra = {}, npc = 'act') {
  server.behavior(`live:${id}`, { routines: { main: routine }, ...extra });
  await server.reload();
  const execution = await owner.run(npc, `live:${id}`);
  return { execution, ...(await server.finish(execution)) };
}
const diagnosticOf = text => text.match(/Behavior diagnostic:[^\n]*/)?.[0] ?? text.match(/reason=[^\n]*/)?.[0] ?? text.slice(0, 300);
async function actions() {
  let owner = await join(OWNER.name);
  await owner.tp(0, 61, 0);
  const uuid = await server.npc('act', 'minecraft:player', 0, 61, 3, 'Actor');
  let body = await owner.body(uuid);
  await check('say to trigger only, nearby radius', async () => {
    const peer = await join(PEER.name, null);
    await peer.tp(0, 61, 1);
    const ownerFrom = owner.events.length, peerFrom = peer.events.length;
    const result = await runDoc(owner, 'say', action('animus:say', { text: 'hello' }));
    assert.equal(result.state, 'COMPLETED', diagnosticOf(result.text));
    await owner.chat('Actor » hello', ownerFrom);
    await peer.noChat('Actor: hello', peerFrom);
    const nearbyFrom = peer.events.length;
    await runDoc(owner, 'say', action('animus:say', { text: 'all', audience: 'nearby', radius: 16 }));
    await peer.chat('Actor » all', nearbyFrom);
    await peer.tp(0, 61, 30);
    const farFrom = peer.events.length;
    await runDoc(owner, 'say', action('animus:say', { text: 'far', audience: 'nearby', radius: 4 }));
    await peer.noChat('Actor: far', farFrom);
    await peer.close();
  });
  await check('console run without player refused', async () => {
    server.behavior('live:say', { routines: { main: action('animus:say', { text: 'hello' }) } });
    await server.reload();
    const result = await server.finish(await server.runConsole('act', 'live:say'));
    assert(/no-triggering-player/.test(result.text), result.text.slice(0, 400));
    return `${result.state} ${diagnosticOf(result.text).replace(/^.*Behavior diagnostic/, 'diagnostic').slice(0, 160)}`;
  });
  server.behavior('live:choice', {
    routines: {
      main: action('animus:choices', { id: 'greeting', prompt: 'Need directions?', options: [{ id: 'yes', text: 'Yes' }, { id: 'no', text: 'No' }] }),
      directions: sayTo('dialogue-yes-fired'),
    },
    triggers: [{ id: 'asked', event: 'dialogue_choice', dialogue: 'greeting', choice: 'yes', routine: 'directions', cooldownTicks: 0 }],
  });
  await server.reload();
  await server.cmd('animus despawn act', /./); await delay(300);
  await server.cmd('animus edit act {"behaviors":["live:choice"]}', /Saved/);
  await server.cmd('animus spawn act', /Spawn requested/);
  await delay(800);
  const ask = async () => {
    const from = owner.events.length;
    await owner.run('act', 'live:choice');
    const prompt = await owner.wait(e => e.event === 'chat' && e.clicks?.length >= 2, from);
    const yes = prompt.clicks.find(c => c.endsWith(' yes'));
    assert(yes?.startsWith('/animus choice '), JSON.stringify(prompt.clicks));
    return yes.replace(/^\//, '');
  };
  let rejection;
  await check('choices offer clickable run_command options', async () => { const yes = await ask(); return yes.replace(/choice \S+/, 'choice <token>'); });
  await check('forged click from another player refused, prompt stays open', async () => {
    const yes = await ask();
    const peer = await join(PEER.name, null);
    await peer.tp(0, 61, 1);
    const from = peer.events.length;
    peer.send('command', { text: yes });
    await delay(900);
    rejection = peer.events.slice(from).filter(e => e.event === 'chat').map(e => e.text).join('|');
    assert(rejection, 'peer got no rejection message');
    const ownerFrom = owner.events.length;
    await owner.noChat('dialogue-yes-fired', ownerFrom, 300);
    await owner.command(yes);
    await owner.chat('dialogue-yes-fired', ownerFrom);
    await peer.close();
    return rejection;
  });
  await check('stale click after answer refused', async () => {
    const yes = await ask();
    const a = owner.events.length;
    await owner.command(yes);
    await owner.chat('dialogue-yes-fired', a);
    const b = owner.events.length;
    await owner.command(yes);
    await delay(700);
    assert(!owner.has(e => e.event === 'chat' && e.text.includes('dialogue-yes-fired'), b));
    const reply = owner.events.slice(b).filter(e => e.event === 'chat').map(e => e.text).join('|');
    assert.equal(reply, rejection);
    return reply;
  });
  await check('unknown token and option refused alike', async () => {
    const yes = await ask();
    const from = owner.events.length;
    await owner.command(yes.replace(/ yes$/, ' maybe'));
    await owner.command('animus choice 00000000000000000000000000000000 yes');
    await delay(500);
    const replies = owner.events.slice(from).filter(e => e.event === 'chat').map(e => e.text);
    assert.equal(replies.length, 2, replies.join('|'));
    assert(replies.every(text => text === rejection), replies.join('|'));
    const c = owner.events.length;
    await owner.command(yes);
    await owner.chat('dialogue-yes-fired', c);
  });
  await check('replaced prompt token refused', async () => {
    const first = await ask();
    const second = await ask();
    const from = owner.events.length;
    await owner.command(first);
    await delay(500);
    assert(!owner.has(e => e.event === 'chat' && e.text.includes('dialogue-yes-fired'), from));
    await owner.command(second);
    await owner.chat('dialogue-yes-fired', from);
  });
  await check('too-far click refused, then accepted when back', async () => {
    const yes = await ask();
    await owner.tp(0, 61, 73);
    const from = owner.events.length;
    await owner.command(yes);
    await delay(500);
    assert(!owner.has(e => e.event === 'chat' && e.text.includes('dialogue-yes-fired'), from));
    await owner.tp(0, 61, 0);
    await delay(500);
    await owner.command(yes);
    await owner.chat('dialogue-yes-fired', from);
  });
  body = await owner.body(uuid);
  await check('navigate wait completes on arrival', async () => {
    const from = owner.events.length;
    const result = await runDoc(owner, 'nav', seq(action('animus:navigate', { x: 5, y: 61, z: 3, speed: 3, wait: true }), sayTo('arrived')));
    assert.equal(result.state, 'COMPLETED', diagnosticOf(result.text));
    await owner.chat('Actor » arrived', from);
    const moved = owner.events.slice(from).filter(e => e.event === 'move' && e.id === body.id).at(-1);
    assert(moved && Math.abs(moved.x - 5) < .6, JSON.stringify(moved));
    return `final x=${moved.x}`;
  });
  await check('navigate wait fails with no path', async () => {
    await server.cmd('fill 13 61 1 17 63 5 stone hollow', /filled/);
    const result = await runDoc(owner, 'nav', seq(action('animus:navigate', { x: 15, y: 61, z: 3, speed: 3, wait: true }), sayTo('unreachable-arrived')));
    await server.cmd('fill 13 61 1 17 63 5 air', /filled/);
    assert.equal(result.state, 'FAILED', result.text.slice(0, 300));
    assert(/navigation-(blocked|failed)/.test(result.text), diagnosticOf(result.text));
    return diagnosticOf(result.text).replace(/^.*Behavior diagnostic/, 'diagnostic').slice(0, 200);
  });
  await check('navigate wait fails when blocked mid-walk', async () => {
    await server.cmd('animus navigate act 0 61 3 walk 5', /./); await delay(2000);
    server.behavior('live:nav', { routines: { main: seq(action('animus:navigate', { x: 12, y: 61, z: 3, speed: 1, wait: true, timeoutTicks: 600 }), sayTo('walled-arrived')) } });
    await server.reload();
    const execution = await owner.run('act', 'live:nav');
    await delay(1200);
    await server.cmd('fill 6 61 -40 6 66 40 stone', /filled/);
    const result = await server.finish(execution, 45_000);
    await server.cmd('fill 6 61 -40 6 66 40 air', /filled/);
    assert.equal(result.state, 'FAILED', result.text.slice(0, 400));
    return diagnosticOf(result.text).replace(/^.*Behavior diagnostic/, 'diagnostic').slice(0, 200);
  });
  await check('navigate wait times out', async () => {
    await server.cmd('animus navigate act 0 61 3 walk 5', /./); await delay(3000);
    const result = await runDoc(owner, 'nav', action('animus:navigate', { x: 30, y: 61, z: 3, speed: 1, wait: true, timeoutTicks: 20 }));
    assert.equal(result.state, 'FAILED');
    assert(/navigation-timeout/.test(result.text), result.text.slice(0, 400));
    return diagnosticOf(result.text).replace(/^.*Behavior diagnostic/, 'diagnostic').slice(0, 200);
  });
  await check('look at coordinate and player', async () => {
    await server.cmd('animus navigation act stop', /./);
    await delay(400);
    let from = owner.events.length;
    const result = await runDoc(owner, 'look', action('animus:look', { x: 20, y: 62, z: 3 }));
    assert.equal(result.state, 'COMPLETED', diagnosticOf(result.text));
    await owner.wait(e => (e.event === 'head' || e.event === 'rotation') && e.id === body.id, from, 3000);
    await owner.tp(-4, 61, 8);
    body = await owner.body(uuid);
    from = owner.events.length;
    const result2 = await runDoc(owner, 'look', action('animus:look', { target: 'player' }));
    assert.equal(result2.state, 'COMPLETED', diagnosticOf(result2.text));
    const turned = await owner.wait(e => (e.event === 'head' || e.event === 'rotation') && e.id === body.id, from, 3000);
    await owner.tp(0, 61, 0);
    body = await owner.body(uuid);
    return JSON.stringify(turned).slice(0, 120);
  });
  await check('set-appearance name, skin and reset', async () => {
    let from = owner.events.length;
    let result = await runDoc(owner, 'appear', action('animus:set-appearance', { name: 'Renamed' }));
    assert.equal(result.state, 'COMPLETED', diagnosticOf(result.text));
    await owner.wait(e => e.event === 'player_info' && e.uuid === uuid && e.name === 'Renamed', from, 5000);
    const skin = readdirSync(path.join(data, 'skins')).find(file => file.endsWith('.json'))?.replace('.json', '');
    from = owner.events.length;
    result = await runDoc(owner, 'appear', action('animus:set-appearance', { skin }));
    assert.equal(result.state, 'COMPLETED', diagnosticOf(result.text));
    const skinned = await owner.wait(e => e.event === 'player_info' && e.uuid === uuid && e.textures?.length === 1, from, 5000);
    from = owner.events.length;
    result = await runDoc(owner, 'appear', action('animus:set-appearance', { reset: true }));
    assert.equal(result.state, 'COMPLETED', diagnosticOf(result.text));
    const reset = await owner.wait(e => e.event === 'player_info' && e.uuid === uuid && e.name === 'Actor', from, 5000);
    assert.equal(reset.textures.length, 0);
    await delay(500);
    body = await owner.body(uuid);
    return `signed=${skinned.textures[0].signed}`;
  });
  await check('set-equipment packet', async () => {
    let from = owner.events.length;
    let result = await runDoc(owner, 'equip', action('animus:set-equipment', { slot: 'main_hand', item: 'minecraft:iron_sword' }));
    assert.equal(result.state, 'COMPLETED', diagnosticOf(result.text));
    body = await owner.body(uuid);
    const shown = await owner.wait(e => e.event === 'equipment' && e.id === body.id && e.slots.mainhand === 'minecraft:iron_sword', from, 5000);
    const peer = await join(PEER.name, null);
    await peer.tp(0, 61, 1);
    const late = await peer.body(uuid);
    await peer.wait(e => e.event === 'equipment' && e.id === late.id && e.slots.mainhand === 'minecraft:iron_sword', 0, 5000);
    await peer.close();
    from = owner.events.length;
    result = await runDoc(owner, 'equip', action('animus:set-equipment', { slot: 'main_hand' }));
    assert.equal(result.state, 'COMPLETED', diagnosticOf(result.text));
    await owner.wait(e => e.event === 'equipment' && e.id === body.id && e.slots.mainhand === 'empty', from, 5000);
    return `${JSON.stringify(shown.slots)}; late viewer received it; clear delivered`;
  });
  await check('play-sound', async () => {
    const from = owner.events.length;
    const result = await runDoc(owner, 'sound', action('animus:play-sound', { sound: 'minecraft:entity.villager.yes', volume: 1, pitch: 1 }));
    assert.equal(result.state, 'COMPLETED', diagnosticOf(result.text));
    return (await owner.wait(e => e.event === 'sound' && e.sound.includes('villager.yes'), from, 3000)).sound;
  });
  await check('spawn-particle and data-particle refusal', async () => {
    const from = owner.events.length;
    const result = await runDoc(owner, 'part', action('animus:spawn-particle', { particle: 'minecraft:flame', count: 5, spread: 1 }));
    assert.equal(result.state, 'COMPLETED', diagnosticOf(result.text));
    const particle = await owner.wait(e => e.event === 'particle' && e.particle === 'minecraft:flame', from, 3000);
    const bad = await runDoc(owner, 'part', action('animus:spawn-particle', { particle: 'minecraft:dust' }));
    assert.equal(bad.state, 'FAILED');
    assert(/particle-needs-data/.test(bad.text), bad.text.slice(0, 400));
    return `count=${particle.count}; ${diagnosticOf(bad.text).replace(/^.*Behavior diagnostic/, 'diagnostic').slice(0, 120)}`;
  });
  await check('quest state and flags', async () => {
    const from = owner.events.length;
    const result = await runDoc(owner, 'quest', seq(
      action('animus:set-quest-state', { name: 'main', value: 1 }),
      action('animus:advance-quest-state', { name: 'main', by: 5, maximum: 3 }),
      { type: 'condition', capability: 'animus:quest-state', arguments: { name: 'main', equals: 3 }, then: sayTo('quest-three'), else: sayTo('quest-other') },
      action('animus:set-flag', { name: 'met', scope: 'relationship' }),
      { type: 'condition', capability: 'animus:flag-state', arguments: { name: 'met', scope: 'relationship' }, then: sayTo('flag-set'), else: sayTo('flag-unset') },
    ));
    assert.equal(result.state, 'COMPLETED', diagnosticOf(result.text));
    await owner.chat('Actor » quest-three', from); await owner.chat('Actor » flag-set', from);
    const shared = await runDoc(owner, 'other/quest', { type: 'condition', capability: 'animus:quest-state', arguments: { name: 'main', atLeast: 3 }, then: sayTo('shared-in-namespace'), else: sayTo('not-shared') });
    assert.equal(shared.state, 'COMPLETED');
    await owner.chat('Actor » shared-in-namespace', from);
    server.behavior('harbor:quest', { routines: { main: { type: 'condition', capability: 'animus:quest-state', arguments: { name: 'main', atLeast: 1 }, then: sayTo('leaked-namespace'), else: sayTo('own-namespace') } } });
    await server.reload();
    await owner.run('act', 'harbor:quest');
    await owner.chat('Actor » own-namespace', from);
    await delay(5500);
    const file = path.join(data, 'state/behavior-state/player', OWNER.uuid, 'animus/quests/live.json');
    assert(existsSync(file), `${file} missing`);
    return readFileSync(file, 'utf8').replace(/\s+/g, ' ').slice(0, 120);
  });
  await check('command denied by default', async () => {
    server.behavior('live:cmd', { routines: { main: action('animus:command', { command: 'setblock 20 61 20 minecraft:gold_block' }) } });
    const reload = await server.capture('animus behaviors reload', /Behaviors reload: \w+[^\n]*/);
    note(reload);
    const run = await server.capture('animus behaviors run act live:cmd', /Behavior run: \w+[^\n]*/, 600);
    note(run);
    const placed = await server.capture('execute if block 20 61 20 minecraft:gold_block', /passed|failed/i);
    assert(/failed/i.test(placed), placed.slice(0, 200));
    const execution = run.match(/execution=([0-9a-f-]{36})/)?.[1];
    if (!execution) return run.match(/Behavior run:[^\n]*/)[0].replace(/^.*Behavior run/, 'run').slice(0, 200);
    const result = await server.finish(execution);
    assert.notEqual(result.state, 'COMPLETED');
    return `${result.state} ${diagnosticOf(result.text).replace(/^.*Behavior diagnostic/, 'diagnostic').slice(0, 200)}`;
  });
  await owner.close(); await delay(300);
  await server.stop();
  server.config('[behaviors.actions]\ncommandBehaviors = ["live:cmd"]\ncommandNamespaces = []\n');
  await server.start();
  owner = await join(OWNER.name);
  await owner.tp(0, 61, 0);
  await owner.body(uuid);
  await check('command works with allow-list and is logged', async () => {
    const from = logText().length;
    const result = await server.finish(await owner.run('act', 'live:cmd'));
    assert.equal(result.state, 'COMPLETED', diagnosticOf(result.text));
    const placed = await server.capture('execute if block 20 61 20 minecraft:gold_block', /passed|failed/i);
    assert(/passed/i.test(placed), placed.slice(0, 200));
    const audit = logText().slice(from).split('\n').find(line => line.includes('live:cmd') && line.includes('setblock'));
    assert(audit, 'no audit line for the command');
    assert(audit.includes(uuid), audit);
    return audit.replace(/^.*?\]: /, '').slice(0, 220);
  });
  await check('command as player', async () => {
    server.behavior('live:cmd', { routines: { main: action('animus:command', { command: 'gamemode survival', as: 'player' }) } });
    await server.reload();
    const result = await server.finish(await owner.run('act', 'live:cmd'));
    assert.equal(result.state, 'COMPLETED', diagnosticOf(result.text));
    await server.cmd('gamemode creative AnimusProofQA', /./);
  });
  await owner.close(); await delay(300);
  await server.stop();
  server.config('');
  await server.start();
  await server.cmd('animus delete act', /Deleted/);
}

async function mounts() {
  let owner = await join(OWNER.name);
  await owner.tp(0, 61, 0);
  const horse = await server.npc('mhorse', 'minecraft:horse', 0, 61, 4, 'Horse');
  const riderUuid = await server.npc('mrider', 'minecraft:player', 6, 61, 4, 'Rider', { modules: { 'animus:mount': { vehicle: 'mhorse' } } });
  const passengerOf = async (client, vehicleUuid, passengerUuid) => {
    const vehicle = await client.body(vehicleUuid), passenger = await client.body(passengerUuid);
    await client.wait(e => e.event === 'passengers' && e.vehicle === vehicle.id && e.ids.includes(passenger.id), 0, 8000);
    return { vehicle: vehicle.id, passenger: passenger.id };
  };
  await check('NPC on NPC passenger packet', async () => JSON.stringify(await passengerOf(owner, horse, riderUuid)));
  await check('inspect shows no problem', async () => {
    const text = await server.capture('animus inspect mrider', /Runtime mrider:[^\n]*/);
    assert(!text.includes('mount-problem='), text.slice(0, 300));
    return text.match(/Runtime mrider:[^\n]*/)[0].slice(0, 200);
  });
  await check('second viewer join gets passenger list', async () => {
    const peer = await join(PEER.name, null);
    await peer.tp(0, 61, 1);
    const seen = await passengerOf(peer, horse, riderUuid);
    await peer.close();
    return JSON.stringify(seen);
  });
  await check('viewer leaving range and returning gets passenger list', async () => {
    await owner.tp(0, 61, 400);
    await delay(800);
    assert.equal(owner.current(horse), null, 'horse still presented far away');
    const from = owner.events.length;
    await owner.tp(0, 61, 0);
    await delay(500);
    const vehicle = await owner.wait(e => e.event === 'spawn' && e.uuid === horse, from, 15000);
    const rider = await owner.wait(e => e.event === 'spawn' && e.uuid === riderUuid, from, 15000);
    await owner.wait(e => e.event === 'passengers' && e.vehicle === vehicle.id && e.ids.includes(rider.id), from, 8000);
  });
  await check('rejoining viewer gets passenger list', async () => {
    await owner.close(); await delay(500);
    owner = await join(OWNER.name);
    await owner.tp(0, 61, 0);
    return JSON.stringify(await passengerOf(owner, horse, riderUuid));
  });
  await check('cycle refused, self refused, missing vehicle reported', async () => {
    const cycle = await server.capture('animus edit mhorse {"modules":{"animus:mount":{"vehicle":"mrider"}}}', /Saved|cycle|Cannot|Conflict|rror|refus/i);
    assert(!/Saved; transaction/.test(cycle), cycle.slice(0, 300));
    const self = await server.capture('animus edit mrider {"modules":{"animus:mount":{"vehicle":"mrider"}}}', /Saved|itself|Cannot|Conflict|rror|refus/i);
    assert(!/Saved; transaction/.test(self), self.slice(0, 300));
    await server.npc('mdetached', 'minecraft:player', 8, 61, 4, 'Detached', { modules: { 'animus:mount': { vehicle: 'nobody' } } });
    await delay(500);
    const missing = await server.capture('animus inspect mdetached', /Runtime mdetached:[^\n]*/);
    assert(missing.includes('mount-problem='), missing.slice(0, 400));
    await server.cmd('animus delete mdetached', /Deleted/);
    return missing.match(/mount-problem=[^\n]*/)[0].slice(0, 160);
  });
  await owner.close(); await delay(300);
  await server.stop();
  await server.start();
  owner = await join(OWNER.name);
  await owner.tp(0, 61, 0);
  await check('passenger relation returns after restart', async () => JSON.stringify(await passengerOf(owner, horse, riderUuid)));
  await check('ending relation returns to definition position', async () => {
    await server.cmd('animus edit mrider {"modules":{}}', /Saved/);
    await delay(1200);
    const body = owner.current(riderUuid);
    assert(body, 'rider body gone');
    const last = owner.events.filter(e => e.id === body.id && (e.event === 'move' || e.event === 'spawn')).at(-1);
    assert(Math.abs(last.x - 6) < .01, JSON.stringify(last));
    return JSON.stringify({ x: last.x, y: last.y, z: last.z });
  });
  await owner.close();
  await server.cmd('animus delete mrider', /Deleted/);
  await server.cmd('animus delete mhorse', /Deleted/);
}

const rideStatus = () => server.capture('animus ride status', /./, 200);
const mounted = (client, vehicleId, from = 0) => client.wait(e => e.event === 'passengers' && e.vehicle === vehicleId && e.ids.includes(e.own), from, 6000);
// Removing the vehicle also dismounts a vanilla client; a presentation reset does that instead of a passenger list.
const dismounted = (client, vehicleId, from = 0) => client.wait(e => (e.event === 'passengers' && e.vehicle === vehicleId && !e.ids.includes(e.own)) || (e.event === 'remove' && e.ids.includes(vehicleId)), from, 8000);
async function mount(client, body) {
  await client.keys({});
  await client.look(body.x, body.y + 1, body.z);
  const from = client.events.length;
  client.send('use', { id: body.id });
  await mounted(client, body.id, from);
  await delay(100);
}
async function riding() {
  let owner = await join(OWNER.name, 'survival');
  await owner.tp(0, 61, 0);
  const horse = await server.npc('rhorse', 'minecraft:horse', 0, 61, 3, 'Steed', { modules: { 'animus:riding': { enabled: true, speed: 4, leash: 32, lifetimeSeconds: 600 } } });
  let body = await owner.body(horse);
  const clean = (label, offset) => { const bad = logText().slice(offset).split('\n').filter(line => FORBIDDEN.test(line)); assert.deepEqual(bad, [], label); };
  const lastOf = () => owner.events.filter(e => e.event === 'move' && e.id === body.id).at(-1) ?? body;
  await check('right-click mounts', async () => {
    await mount(owner, body);
    const status = await rideStatus();
    assert(/AnimusProofQA on rhorse/.test(status), status.slice(0, 200));
    return status.match(/AnimusProofQA on[^\n]*/)[0];
  });
  await check('riding idle 120 ticks with allow-flight=false', async () => {
    const offset = logText().length;
    assert(readFileSync(path.join(instance, 'server.properties'), 'utf8').includes('allow-flight=false'));
    await delay(6000);
    clean('idle ride', offset);
    assert(!owner.fatal, owner.fatal?.message);
    assert(/AnimusProofQA on rhorse/.test(await rideStatus()));
  });
  await check('steering input moves the NPC', async () => {
    const offset = logText().length, from = owner.events.length;
    await owner.look(body.x, body.y + 1, body.z + 30);
    await owner.keys({ forward: true });
    await delay(5000);
    await owner.keys({});
    await delay(300);
    const moves = owner.events.slice(from).filter(e => e.event === 'move' && e.id === body.id);
    assert(moves.length > 5, `only ${moves.length} move packets`);
    const last = moves.at(-1), distance = Math.hypot(last.x - body.x, last.z - body.z);
    assert(distance > 8 && distance < 25, `moved ${distance}`);
    clean('steering', offset);
    const status = await rideStatus();
    return `moved ${distance.toFixed(2)} blocks in 5s at speed 4; ${status.match(/steps=\d+/)?.[0]}`;
  });
  await check('steering follows look direction', async () => {
    const offset = logText().length, from = owner.events.length;
    const here = lastOf();
    await owner.look(here.x + 40, here.y + 1, here.z);
    await owner.keys({ forward: true });
    await delay(2000);
    await owner.keys({});
    await delay(200);
    const last = owner.events.slice(from).filter(e => e.event === 'move' && e.id === body.id).at(-1);
    assert(last.x - here.x > 3, `dx ${last.x - here.x}`);
    clean('steering turn', offset);
    return `dx=${(last.x - here.x).toFixed(2)} dz=${(last.z - here.z).toFixed(2)}`;
  });
  await check('blocked by wall stays riding, no warnings', async () => {
    const offset = logText().length;
    const here = lastOf();
    const wall = Math.floor(here.x) + 3;
    await server.cmd(`fill ${wall} 61 ${Math.floor(here.z) - 3} ${wall} 64 ${Math.floor(here.z) + 3} stone`, /filled/);
    await owner.look(here.x + 40, here.y + 1, here.z);
    await owner.keys({ forward: true });
    await delay(3000);
    await owner.keys({});
    const last = lastOf();
    assert(last.x < wall, `passed through wall: ${last.x} vs ${wall}`);
    assert(/AnimusProofQA on rhorse/.test(await rideStatus()));
    clean('wall', offset);
    await server.cmd(`fill ${wall} 61 ${Math.floor(here.z) - 3} ${wall} 64 ${Math.floor(here.z) + 3} air`, /filled/);
    return `stopped at x=${last.x.toFixed(2)} wall=${wall}`;
  });
  await check('sneak dismounts and places player beside NPC', async () => {
    const offset = logText().length, from = owner.events.length;
    await owner.keys({ shift: true });
    await dismounted(owner, body.id, from);
    const position = await owner.wait(e => e.event === 'position', from, 5000);
    await owner.keys({});
    await delay(2500);
    assert(!owner.fatal);
    clean('sneak', offset);
    assert(!/AnimusProofQA on/.test(await rideStatus()));
    const npc = lastOf();
    assert(Math.hypot(position.x - npc.x, position.z - npc.z) < 3, JSON.stringify(position));
    return `client placed at ${position.x.toFixed(2)},${position.y.toFixed(2)},${position.z.toFixed(2)} beside NPC ${npc.x.toFixed(2)},${npc.z.toFixed(2)}`;
  });
  const remount = async () => {
    const npc = lastOf();
    if (Math.hypot(owner.position.x - npc.x, owner.position.z - npc.z) > 3) { await owner.tp(npc.x + 1.5, 61, npc.z); body = await owner.body(horse); }
    await owner.keys({});
    await owner.look(npc.x, npc.y + 1, npc.z);
    const from = owner.events.length;
    owner.send('use', { id: body.id });
    await mounted(owner, body.id, from);
    await delay(100);
    return npc;
  };
  await check('logout while riding saves at NPC feet, no stuck state', async () => {
    const npc = await remount();
    const offset = logText().length;
    await owner.close();
    await delay(700);
    assert(!/AnimusProofQA on/.test(await rideStatus()));
    owner = await join(OWNER.name, null);
    await delay(300);
    assert(Math.hypot(owner.position.x - npc.x, owner.position.z - npc.z) < .1, JSON.stringify(owner.position));
    assert.equal(owner.position.y, 61);
    body = await owner.body(horse);
    await delay(2500);
    assert(!owner.fatal);
    clean('logout', offset);
    return `rejoined at ${owner.position.x.toFixed(2)},${owner.position.y},${owner.position.z.toFixed(2)}`;
  });
  await check('remount after logout works', async () => { await remount(); assert(/AnimusProofQA on rhorse/.test(await rideStatus())); });
  await check('teleport while riding ends the ride without moving further', async () => {
    const offset = logText().length, from = owner.events.length;
    await server.cmd('tp AnimusProofQA 20.0 61.0 20.0', /Teleported/);
    await dismounted(owner, body.id, from);
    await delay(2500);
    const status = await rideStatus();
    assert(!/AnimusProofQA on/.test(status), status.slice(0, 200));
    assert(Math.hypot(owner.position.x - 20, owner.position.z - 20) < .01, JSON.stringify(owner.position));
    assert(!owner.fatal);
    clean('teleport', offset);
    return owner.events.slice(from).filter(e => e.event === 'chat').map(e => e.text).join('|').slice(0, 120);
  });
  await check('death while riding ends the ride, respawn works', async () => {
    await remount();
    const offset = logText().length, from = owner.events.length;
    await server.cmd('kill AnimusProofQA', /Killed/);
    await dismounted(owner, body.id, from);
    await owner.wait(e => e.event === 'health' && e.health <= 0, from, 5000);
    owner.send('respawn');
    await owner.wait(e => e.event === 'respawn', from, 5000);
    await owner.wait(e => e.event === 'health' && e.health > 0, from, 5000);
    await delay(2500);
    assert(!/AnimusProofQA on/.test(await rideStatus()));
    assert(!owner.fatal);
    clean('death', offset);
    return `respawned at ${owner.position.x.toFixed(1)},${owner.position.y},${owner.position.z.toFixed(1)}`;
  });
  await delay(1000);
  body = await owner.body(horse, 8000).catch(() => body);
  await check('admin ride stop', async () => {
    await remount();
    const from = owner.events.length;
    await server.cmd('animus ride stop AnimusProofQA', /Dismounted AnimusProofQA/);
    await dismounted(owner, body.id, from);
    await owner.chat('Dismounted:', from);
  });
  await check('another viewer sees the rider on the NPC, cannot double-mount', async () => {
    await remount();
    const peer = await join(PEER.name, null);
    const npc = lastOf();
    await peer.tp(npc.x + 3, 61, npc.z);
    const seen = await peer.body(horse);
    const riders = await peer.wait(e => e.event === 'passengers' && e.vehicle === seen.id && e.ids.length === 1, 0, 6000);
    const denied = peer.events.length;
    await peer.look(npc.x, npc.y + 1, npc.z);
    peer.send('use', { id: seen.id });
    const reply = await peer.chat(/permission|riding this/, denied, 4000);
    await peer.close();
    return `ids=${riders.ids}; ${reply.text}`;
  });
  await check('definition edit during ride ends it', async () => {
    const offset = logText().length, from = owner.events.length;
    await server.cmd('animus edit rhorse {"name":"Steed Two"}', /Saved/);
    await owner.chat('Dismounted:', from, 8000);
    await delay(2000);
    assert(!owner.fatal);
    clean('definition edit', offset);
    body = await owner.body(horse);
    return owner.events.slice(from).filter(e => e.event === 'chat').map(e => e.text).at(-1);
  });
  await check('graceful stop while riding is clean', async () => {
    await remount();
    owner.send('expect_disconnect');
    owner.closing = true;
    const final = await server.stop();
    await owner.close();
    const bad = final.split('\n').filter(line => FORBIDDEN.test(line));
    assert.deepEqual(bad, []);
  });
  await server.start();
  owner = await join(OWNER.name, null);
  await check('player state after stop while riding', async () => {
    body = await owner.body(horse);
    await delay(2500);
    assert(!owner.fatal);
    assert.equal(owner.position.y, 61);
    assert(!/AnimusProofQA on/.test(await rideStatus()));
    return `rejoined at ${owner.position.x.toFixed(2)},${owner.position.y},${owner.position.z.toFixed(2)}`;
  });
  await check('player-shaped NPC can be ridden', async () => {
    await owner.tp(-10, 61, -10);
    const person = await server.npc('rman', 'minecraft:player', -10, 61, -7, 'Carrier', { modules: { 'animus:riding': { enabled: true, speed: 2 } } });
    const carried = await owner.body(person);
    await mount(owner, carried);
    await owner.look(carried.x, carried.y + 1, carried.z + 30);
    await owner.keys({ forward: true });
    await delay(1500);
    await owner.keys({});
    const from = owner.events.length;
    await owner.keys({ shift: true });
    await dismounted(owner, carried.id, from);
    await owner.keys({});
    await server.cmd('animus delete rman', /Deleted/);
  });
  await owner.close();
  await server.cmd('animus delete rhorse', /Deleted/);
}

async function dragon() {
  server.behavior('live:dragon', {
    routines: { main: sayTo('manual'), hit: sayTo('dragon-hit') },
    triggers: [{ id: 'hit', event: 'interaction', action: 'any', routine: 'hit', cooldownTicks: 0 }],
  });
  await server.reload();
  const owner = await join(OWNER.name);
  await owner.tp(0, 61, 0);
  const uuid = await server.npc('drake', 'minecraft:ender_dragon', 0, 61, 12, 'Drake', { behaviors: ['live:dragon'] });
  let body = await owner.body(uuid);
  const go = async (x, y, z) => { await owner.tp(x, y, z); body = await owner.body(uuid); };
  await check('dragon presented with spawn and sitting phase', async () => {
    assert.equal(body.type, 'minecraft:ender_dragon');
    const phase = await owner.wait(e => e.event === 'metadata' && e.id === body.id && e.fields.some(f => f.value === 6), 0, 6000);
    return JSON.stringify(phase.fields).slice(0, 120);
  });
  const strike = async (id, op = 'attack') => {
    const from = owner.events.length;
    owner.send(op, { id });
    await delay(500);
    return owner.has(e => e.event === 'chat' && e.text.includes('dragon-hit'), from);
  };
  // Stand in front of the head, clear of every part box, so only aim decides.
  await check('part hit accepted when aiming at the head', async () => {
    await go(0, 61, 21.5);
    await owner.look(0, 61.5, 18.5);
    await delay(300);
    assert.equal(await strike(body.id + 1), true, 'head part attack not accepted');
    await delay(300);
    assert.equal(await strike(body.id + 6), true, 'claimed tail tip, aimed at head: position decides');
    await delay(300);
    assert.equal(await strike(body.id + 1, 'use'), true, 'use on part not accepted');
  });
  await check('part hit refused aiming away', async () => {
    await owner.look(0, 62, 60);
    await delay(300);
    assert.equal(await strike(body.id + 1), false);
  });
  await check('root entity id hit refused when aiming away', async () => {
    assert.equal(await strike(body.id), false);
  });
  await check('part hit refused inside broad bounds but outside parts', async () => {
    await go(7, 61, 20);
    await owner.look(7, 62, 24);
    await delay(300);
    assert.equal(await strike(body.id + 3), false);
  });
  await check('part hit refused out of reach', async () => {
    await go(0, 61, 26);
    await owner.look(0, 61.5, 18.5);
    await delay(300);
    assert.equal(await strike(body.id + 1), false);
  });
  await check('forged part id out of range refused', async () => {
    await go(0, 61, 21.5);
    await owner.look(0, 61.5, 18.5);
    await delay(300);
    // body + 9 is now the name text display, which identifies the same NPC; forge an id no entity uses.
    assert.equal(await strike(2_000_000), false);
  });
  await check('moving dragon uses flight phase and returns to sitting', async () => {
    await go(0, 61, -20);
    const from = owner.events.length;
    const reply = await server.capture('animus navigate drake 0 61 16 walk 2', /Navigation[^\n]*|Animus navigation[^\n]*/, 2500);
    const status = await server.capture('animus navigation drake status', /Navigation[^\n]*|Animus navigation[^\n]*/);
    note(reply + status);
    const phases = () => owner.events.slice(from).filter(e => e.event === 'metadata' && e.id === body.id).map(e => JSON.stringify(e.fields)).join(' ');
    await owner.wait(e => e.event === 'metadata' && e.id === body.id && e.fields.some(f => f.value === 0), from, 8000).catch(error => { throw new Error(`${error.message}; phases=${phases()}; ${status.match(/Navigation[^\n]*/)?.[0]}`); });
    await owner.wait(e => e.event === 'metadata' && e.id === body.id && e.fields.some(f => f.value === 6), from, 15000);
    return phases();
  });
  await check('no boss bar ever sent', async () => { assert(!owner.has(e => e.event === 'boss_event')); });
  await check('despawn removes the dragon', async () => {
    const from = owner.events.length;
    await server.cmd('animus despawn drake', /./);
    await owner.wait(e => e.event === 'remove' && e.ids.includes(body.id), from, 8000);
    assert(!owner.has(e => e.event === 'boss_event'));
  });
  await owner.close();
  await server.cmd('animus delete drake', /Deleted/);
}

async function folia() {
  server.behavior('live:folia', {
    routines: { main: sayTo('manual'), up: say('f-spawn'), clicked: sayTo('f-click'), near: sayTo('f-near'), tick: action('animus:set-flag', { name: 'folia', scope: 'global' }) },
    triggers: [
      { id: 'up', event: 'spawn', routine: 'up', cooldownTicks: 0 }, { id: 'click', event: 'interaction', routine: 'clicked', cooldownTicks: 0 },
      { id: 'near', event: 'proximity', phase: 'enter', radius: 6, routine: 'near', cooldownTicks: 0 }, { id: 't', event: 'timer', intervalTicks: 40, routine: 'tick' },
    ],
  });
  await server.reload();
  let owner = await join(OWNER.name);
  // Folia skips the platform fills: everything stands on the flat world's own surface beside spawn.
  const ground = Math.round(owner.position.y), sx = Math.floor(owner.position.x), sz = Math.floor(owner.position.z);
  const at = (dx, dz) => [sx + dx + .5, ground, sz + dz + .5];
  const statusLine = async () => plain(await server.capture('animus status', /NPC runtime[^\n]*/)).match(/NPC runtime[^\n]*/)[0];
  let from = owner.events.length;
  const uuid = await server.npc('fnpc', 'minecraft:player', ...at(0, 4), 'Folia', { behaviors: ['live:folia'] });
  await check('NPC activates on its region and renders to the player', async () => {
    const body = await owner.body(uuid);
    const status = await statusLine();
    const runtime = await inspect('fnpc');
    assert(/active=1\b/.test(status), status);
    assert(/: active/.test(runtime.line), runtime.line.slice(0, 200));
    return `${status.slice(0, 160)}; body entity ${body.id}`;
  });
  await check('triggers: spawn, interaction, proximity and timer', async () => {
    await owner.chat('Folia » f-spawn', from);
    let clickAt = await click(owner, uuid);
    await owner.chat('Folia » f-click', clickAt);
    await owner.tp(...at(0, 24));
    from = owner.events.length;
    await owner.tp(...at(0, 1));
    await owner.chat('Folia » f-near', from);
    assert(await flagStored('global', 'live', 'folia'), 'timer never stored its flag');
    return 'spawn, interaction, proximity enter and timer all fired';
  });
  await check('actions: say, look, equipment and sound', async () => {
    from = owner.events.length;
    const body = owner.current(uuid);
    const result = await runDoc(owner, 'factions', seq(sayTo('f-said'), action('animus:look', { target: 'player' }),
      action('animus:set-equipment', { slot: 'main_hand', item: 'minecraft:iron_sword' }), action('animus:play-sound', { sound: 'minecraft:entity.villager.yes' })), {}, 'fnpc');
    assert.equal(result.state, 'COMPLETED', diagnosticOf(result.text));
    await owner.chat('Folia » f-said', from);
    await owner.wait(e => e.event === 'equipment' && e.id === body.id && e.slots.mainhand === 'minecraft:iron_sword', from, 5000);
    await owner.wait(e => e.event === 'sound' && e.sound === 'minecraft:entity.villager.yes', from, 5000);
    await owner.wait(e => e.event === 'head' && e.id === body.id, from, 5000);
    return result.state;
  });
  await check('navigation completes on the region', async () => {
    const result = await runDoc(owner, 'fwalk', action('animus:navigate', { x: sx + 6.5, y: ground, z: sz + 4.5, speed: 3, wait: true, timeoutTicks: 600 }), {}, 'fnpc');
    const end = await inspect('fnpc');
    assert.equal(result.state, 'COMPLETED', diagnosticOf(result.text));
    assert(Math.abs(end.x - (sx + 6.5)) < .7, end.line.slice(0, 200));
    return `ended at ${end.x.toFixed(2)},${end.y},${end.z.toFixed(2)}`;
  });
  await check('patrol crosses chunk owners and keeps going', async () => {
    // The owner stands midway so every chunk on the way stays loaded; each chunk crossed is an owner handoff.
    await owner.tp(...at(20, 4));
    const target = sx + 40.5;
    const chunks = Math.floor(target / 16) - Math.floor((sx + 6.5) / 16);
    // A route leg is at most 32 blocks, so the patrol goes out and back through a midpoint.
    const leg = x => action('animus:navigate', { x, y: ground, z: sz + 4.5, speed: 4, wait: true, timeoutTicks: 1200 });
    const result = await runDoc(owner, 'fpatrol', seq(leg(sx + 24.5), leg(target), leg(sx + 24.5), leg(sx + 6.5)), {}, 'fnpc');
    const end = await inspect('fnpc');
    assert.equal(result.state, 'COMPLETED', diagnosticOf(result.text));
    assert(chunks >= 2, `only ${chunks} chunk borders`);
    assert(Math.abs(end.x - (sx + 6.5)) < .7, end.line.slice(0, 200));
    return `out ${chunks} chunk borders to x=${target} and back; ${end.line.match(/owner=[^;]*/)?.[0]}`;
  });
  // Folia merges a player's loaded chunks with the NPC's, so a player in another region never has the NPC presented:
  // the hit is dropped before the region check and only the absence of any effect can be observed.
  await check('hit from a player in another region does nothing', async () => {
    const body = owner.current(uuid);
    const before = Number((await statusLine()).match(/accepted=(\d+)/)[1]);
    const peer = await join(PEER.name, null);
    await peer.tp(sx + 4000.5, ground, sz + 4000.5);
    await delay(1500);
    from = owner.events.length;
    peer.send('attack', { id: body.id });
    peer.send('use', { id: body.id });
    await delay(1500);
    const status = await statusLine();
    const after = Number(status.match(/accepted=(\d+)/)[1]);
    await peer.close();
    await owner.noChat('Folia: f-click', from, 300);
    assert.equal(after, before, status);
    return `accepted stays ${after}, ${status.match(/rejected=\d+/)[0]}; no interaction trigger`;
  });
  await check('riding, mounts and combat report unavailable', async () => {
    await server.npc('fride', 'minecraft:horse', ...at(-4, 4), 'Steed', { modules: { 'animus:riding': { enabled: true }, 'animus:mount': { vehicle: 'fnpc' } } });
    const runtime = await inspect('fride');
    const ride = plain(await server.capture('animus ride status', /./, 300));
    const combat = plain(await server.capture('animus combat status', /Combat \w+[^\n]*/)).match(/Combat \w+[^\n]*/)[0];
    await server.cmd('animus delete fride', /Deleted/);
    assert(/mount=mounted NPCs are unavailable on Folia/.test(runtime.line), runtime.line.slice(0, 300));
    assert(/ride=riding is unavailable on Folia/.test(runtime.line), runtime.line.slice(0, 300));
    assert(/unavailable/i.test(ride), ride.slice(0, 200));
    assert(/Combat unavailable/.test(combat), combat);
    return `${combat.slice(0, 90)}; ride status: ${ride.split('\n').find(line => /unavailable/i.test(line))?.replace(/^.*?\]: /, '').slice(0, 80)}`;
  });
  await owner.close();
  await server.cmd('animus delete fnpc', /Deleted/);
  await foliaBuildsRefused();
}

async function appearance() {
  let owner = await join(OWNER.name);
  await owner.tp(0, 61, 0);
  const uuid = await server.npc('dressed', 'minecraft:player', 2.5, 61, 4.5, 'Dressed', {
    name: 'Captain of the Northern Watch', pose: 'sitting',
    equipment: { main_hand: { id: 'minecraft:iron_sword' }, head: { id: 'minecraft:iron_helmet' } },
    hologram: { lines: ['&6Quest giver', 'Right-click to talk'] },
  });
  // Packet level only: the long name and lines ride the body in a text display, and sitting rides a hidden armor stand.
  const presented = async client => {
    const body = await client.body(uuid);
    const after = e => e.sequence >= body.sequence - 40;
    const gear = await client.wait(e => e.event === 'equipment' && e.id === body.id && e.slots.mainhand === 'minecraft:iron_sword' && e.slots.head === 'minecraft:iron_helmet', 0, 8000);
    const seat = await client.wait(e => e.event === 'passengers' && after(e) && e.ids.includes(body.id), 0, 8000);
    const label = await client.wait(e => e.event === 'passengers' && after(e) && e.vehicle === body.id && e.ids.length > 0, 0, 8000);
    const types = Object.fromEntries(client.events.filter(e => e.event === 'spawn' && after(e)).map(e => [e.id, e.type]));
    assert.equal(types[seat.vehicle], 'minecraft:armor_stand', JSON.stringify(types));
    assert.equal(types[label.ids[0]], 'minecraft:text_display', JSON.stringify(types));
    return `body ${body.id} on armor stand ${seat.vehicle}, text display ${label.ids[0]} riding it, equipment ${JSON.stringify(gear.slots)}`;
  };
  await check('definition equipment, text lines, long name and sitting pose', async () => {
    const runtime = await inspect('dressed');
    assert(/pose=sitting/.test(runtime.line), runtime.line.slice(0, 300));
    return `${await presented(owner)}; ${runtime.line.match(/pose=[^;]*/)?.[0]}`;
  });
  await owner.close(); await delay(300);
  await server.stop();
  await server.start();
  owner = await join(OWNER.name);
  await owner.tp(0, 61, 0);
  await check('definition presentation survives restart', async () => presented(owner));
  await owner.close();
  await server.cmd('animus delete dressed', /Deleted/);
}

const plain = text => text.replace(/\x1b\[[0-9;]*m/g, '');
async function inspect(id) {
  const pattern = new RegExp(`Runtime ${id}:[^\\n]*`);
  const line = plain(await server.capture(`animus inspect ${id}`, pattern)).match(pattern)[0];
  const at = line.match(/position=Vec3\[x=([-\dE.]+), y=([-\dE.]+), z=([-\dE.]+)\]/);
  return { line, health: Number(line.match(/health=([-\d.]+)/)?.[1]), x: Number(at?.[1]), y: Number(at?.[2]), z: Number(at?.[3]) };
}
async function combatOf(id) {
  const pattern = new RegExp(`Combat ${id}:[^\\n]*`);
  return plain(await server.capture(`animus combat ${id} status`, pattern)).match(pattern)[0];
}
async function combatCommand(id, operation) {
  const pattern = new RegExp(`Combat ${id}:[^\\n]*|Unknown NPC[^\\n]*|Usage[^\\n]*`);
  return plain(await server.capture(`animus combat ${id} ${operation}`, pattern)).match(pattern)[0];
}
async function entityNumber(uuid, nbt) {
  const text = plain(await server.capture(`data get entity ${uuid} ${nbt}`, /following entity data[^\n]*|No entity was found|Found no elements[^\n]*/));
  const value = text.match(/following entity data: (-?[\d.]+)/);
  return value ? Number(value[1]) : null;
}
async function entityCount(selector) {
  const text = plain(await server.capture(`execute if entity ${selector}`, /Test (passed|failed)[^\n]*/));
  return Number(text.match(/count: (\d+)/i)?.[1] ?? 0);
}
async function itemCount(player, item) {
  const text = plain(await server.capture(`execute if items entity ${player} container.* ${item}`, /Test (passed|failed)[^\n]*/));
  return Number(text.match(/count: (\d+)/i)?.[1] ?? 0);
}
async function until(test, timeout, label) {
  const deadline = Date.now() + timeout;
  let last;
  while (Date.now() < deadline) {
    last = await test();
    if (last?.ok) return last;
    await delay(250);
  }
  throw new Error(`${label}: ${JSON.stringify(last)?.slice(0, 400)}`);
}
// Fixed mob UUIDs keep commands exact: [I;n,0,0,1].
const mobUuid = n => `${n.toString(16).padStart(8, '0')}-0000-0000-0000-000000000001`;
async function summon(n, type, x, y, z, extra = '') {
  await server.cmd(`summon ${type} ${x} ${y} ${z} {UUID:[I;${n},0,0,1],PersistenceRequired:1b${extra ? ',' + extra : ''}}`, /Summoned/);
  return mobUuid(n);
}
const sturdy = (health, ai = false) => `${ai ? '' : 'NoAI:1b,'}attributes:[{id:"minecraft:max_health",base:${health}}],Health:${health}f`;
async function click(client, uuid, op = 'use') {
  const spawned = await client.body(uuid);
  // Aim where the body is now: a moving NPC has left its spawn position.
  const body = client.events.filter(e => e.event === 'move' && e.id === spawned.id && e.sequence > spawned.sequence).at(-1) ?? spawned;
  await client.look(body.x, body.y + 1.4, body.z);
  const from = client.events.length;
  client.send(op, { id: body.id });
  return from;
}
async function choose(client, from, option) {
  const prompt = await client.wait(e => e.event === 'chat' && e.clicks?.some(c => c.endsWith(` ${option}`)), from);
  const after = client.events.length;
  await client.command(prompt.clicks.find(c => c.endsWith(` ${option}`)).replace(/^\//, ''));
  return { prompt, after };
}
async function cells(client, points) {
  if (!fixture) return;
  for (const [x, y, z] of points) note(plain((await client.command(`animusqa motion cell ${x} ${y} ${z}`, 'QA cell')).text));
}
const combatNpc = (settings, behaviors = []) => ({ ...(behaviors.length ? { behaviors } : {}), modules: { 'animus:combat': { enabled: true, ...settings } } });

async function terrain() {
  const owner = await join(OWNER.name, 'survival');
  await check('walking route over stairs', async () => {
    await server.cmd('fill 4 61 -22 9 61 -18 stone', /filled/);
    await server.cmd('fill 6 62 -22 9 62 -18 stone', /filled/);
    await server.cmd('fill 3 61 -22 3 61 -18 minecraft:oak_stairs[facing=east]', /filled/);
    await server.cmd('fill 5 62 -22 5 62 -18 minecraft:oak_stairs[facing=east]', /filled/);
    await owner.tp(0, 61, -16);
    await cells(owner, [[2, 61, -20], [3, 61, -20], [4, 61, -20], [5, 62, -20], [8, 62, -20], [8, 63, -20], [0, 60, -20]]);
    const uuid = await server.npc('stairs', 'minecraft:player', 0.5, 61, -19.5, 'Climber');
    const body = await owner.body(uuid);
    const from = owner.events.length;
    const result = await runDoc(owner, 'stairs', action('animus:navigate', { x: 8.5, y: 63, z: -19.5, speed: 3, wait: true, timeoutTicks: 400 }), {}, 'stairs');
    const heights = [...new Set(owner.events.slice(from).filter(e => e.event === 'move' && e.id === body.id).map(e => e.y))];
    note(`stairs navigation: ${plain(await server.capture('animus navigation stairs status', /Navigation[^\n]*/))}`);
    const end = await inspect('stairs');
    await server.cmd('animus delete stairs', /Deleted/);
    assert.equal(result.state, 'COMPLETED', `${diagnosticOf(result.text)} heights=${heights}`);
    assert(Math.abs(end.y - 63) < .01 && Math.abs(end.x - 8.5) < .7, end.line.slice(0, 300));
    // Each stair is climbed as a half-block step, never a jump: the body rises steadily through 61..63.
    assert(heights.some(y => y > 61.1 && y < 61.9) && heights.some(y => y > 62.1 && y < 62.9), `no stepped climb: ${heights}`);
    return `heights=${heights.map(y => y.toFixed(2)).join(',')}`;
  });
  await check('ride across grass, flowers and slabs', async () => {
    await server.cmd('setblock 2 61 -30 minecraft:short_grass', /Changed/);
    await server.cmd('setblock 3 61 -30 minecraft:poppy', /Changed/);
    await server.cmd('fill 5 61 -31 6 61 -29 minecraft:stone_slab[type=bottom]', /filled/);
    await server.cmd('setblock 8 61 -30 minecraft:dandelion', /Changed/);
    await server.cmd('setblock 9 61 -29 minecraft:tall_grass[half=lower]', /Changed/);
    await owner.tp(-1.5, 61, -30);
    await cells(owner, [[2, 61, -30], [3, 61, -30], [4, 61, -30], [4, 61, -31], [4, 61, -29], [4, 60, -30], [5, 61, -30], [4, 62, -30]]);
    const uuid = await server.npc('slabhorse', 'minecraft:horse', 0.5, 61, -29.5, 'Trotter', { modules: { 'animus:riding': { enabled: true, speed: 4, leash: 32, lifetimeSeconds: 600 } } });
    const body = await owner.body(uuid);
    const offset = logText().length;
    await mount(owner, body);
    const from = owner.events.length;
    await owner.look(body.x + 40, body.y + 1, body.z);
    await owner.keys({ forward: true });
    await delay(5000);
    await owner.keys({});
    await delay(300);
    const moves = owner.events.slice(from).filter(e => e.event === 'move' && e.id === body.id);
    const status = await rideStatus();
    note(`slab ride moves: ${moves.map(e => `${e.x.toFixed(2)},${e.y}`).join(' ')}`);
    const shiftFrom = owner.events.length;
    await owner.keys({ shift: true });
    await dismounted(owner, body.id, shiftFrom).catch(() => {});
    await owner.keys({});
    await server.cmd('animus delete slabhorse', /Deleted/);
    const last = moves.at(-1);
    const bad = logText().slice(offset).split('\n').filter(line => FORBIDDEN.test(line));
    assert.deepEqual(bad, []);
    assert(last && last.x > 9.5, `stopped at ${JSON.stringify(last)}; ${status.slice(0, 200)}`);
    assert(moves.some(e => Math.abs(e.y - 61.5) < .01), `never on a slab: ${[...new Set(moves.map(e => e.y))]}`);
    return `reached x=${last.x.toFixed(2)}; heights=${[...new Set(moves.map(e => e.y.toFixed(2)))].join(',')}`;
  });
  await check('NPC in shallow water', async () => {
    await server.cmd('fill 10 59 -42 18 59 -34 stone', /filled/);
    await server.cmd('fill 11 60 -41 17 60 -35 water', /filled/);
    await owner.tp(14, 61, -32);
    const uuid = await server.npc('wader', 'minecraft:player', 11.5, 60, -37.5, 'Wader');
    const body = await owner.body(uuid);
    const start = await inspect('wader');
    const from = owner.events.length;
    const result = await runDoc(owner, 'wade', action('animus:navigate', { x: 16.5, y: 60, z: -37.5, mode: 'swim', speed: 2, wait: true, timeoutTicks: 400 }), {}, 'wader');
    const heights = [...new Set(owner.events.slice(from).filter(e => e.event === 'move' && e.id === body.id).map(e => e.y.toFixed(2)))];
    const end = await inspect('wader');
    await server.cmd('animus delete wader', /Deleted/);
    await server.cmd('fill 11 60 -41 17 60 -35 stone', /filled/);
    assert(/active/.test(start.line), start.line.slice(0, 300));
    assert.equal(result.state, 'COMPLETED', `${diagnosticOf(result.text)}; start=${start.line.slice(0, 200)}`);
    assert(Math.abs(end.x - 16.5) < .7, end.line.slice(0, 300));
    return `start y=${start.y}; end x=${end.x.toFixed(2)} y=${end.y}; heights=${heights.join(',')}`;
  });
  await owner.close();
}

async function combat() {
  await server.cmd('difficulty easy', /difficulty/i);
  server.behavior('live:fighter', {
    routines: { main: sayTo('manual'), up: say('fighter-up'), down: say('fighter-down') },
    triggers: [{ id: 'up', event: 'spawn', routine: 'up', cooldownTicks: 0 }, { id: 'down', event: 'death', routine: 'down', cooldownTicks: 0 }],
  });
  await server.reload();
  let owner = await join(OWNER.name, 'survival');
  await owner.tp(-20, 61, -20);
  await check('melee hits a player for its configured damage', async () => {
    await server.cmd(`effect give ${OWNER.name} minecraft:instant_health 1 5 true`, /Applied|effect/i);
    await delay(500);
    const from = owner.events.length;
    await server.npc('cmelee', 'minecraft:player', -20.5, 61, -16.5, 'Brawler', combatNpc({
      targets: { players: [OWNER.name] }, targeting: { scanRange: 16, scanIntervalTicks: 5, lineOfSight: 'ignored' },
      melee: { damage: 4, reach: 3, cooldownTicks: 20 }, pursuit: { speed: 3, giveUpRange: 24, leashRange: 24 },
    }));
    const hit = await owner.wait(e => e.event === 'health' && e.health < 20, from, 15000);
    await delay(2600);
    const healths = owner.events.slice(from).filter(e => e.event === 'health').map(e => e.health);
    const status = await combatOf('cmelee');
    assert.equal(hit.health, 16, `first hit left ${hit.health}`);
    const drops = healths.slice(1).map((h, i) => +(healths[i] - h).toFixed(3)).filter(d => d > 0);
    assert(drops.every(d => Math.abs(d - 4) < .01), `hits ${drops}`);
    assert(/target=PLAYER:/.test(status), status);
    return `healths=${healths.join(',')}; ${status}`;
  });
  await check('combat commands', async () => {
    const results = {};
    results.global = plain(await server.capture('animus combat status', /Combat \w+[^\n]*/)).match(/Combat \w+[^\n]*/)[0];
    assert(/Combat ready \| module npcs=1/.test(results.global), results.global);
    results.disengage = await combatCommand('cmelee', 'disengage');
    const before = owner.events.filter(e => e.event === 'health').at(-1).health;
    await delay(2500);
    const after = owner.events.filter(e => e.event === 'health').at(-1).health;
    assert(after >= before, `still hit after disengage: ${before} -> ${after}`);
    results.disengaged = await combatOf('cmelee');
    assert(/disengaged/.test(results.disengaged) && /target=none/.test(results.disengaged), results.disengaged);
    results.engage = await combatCommand('cmelee', 'engage');
    await until(async () => { const s = await combatOf('cmelee'); return { ok: /target=PLAYER:/.test(s), s }; }, 6000, 'engage did not retarget');
    results.release = await combatCommand('cmelee', 'release');
    results.target = await combatCommand('cmelee', `target ${OWNER.uuid}`);
    results.guard = await combatCommand('cmelee', `guard ${OWNER.uuid}`);
    results.guardStop = await combatCommand('cmelee', 'guard stop');
    results.unknown = plain(await server.capture('animus combat nobody status', /Unknown NPC[^\n]*|Combat[^\n]*/)).match(/Unknown NPC[^\n]*|Combat[^\n]*/)[0];
    for (const key of ['disengage', 'engage', 'release', 'target', 'guard', 'guardStop']) assert(/: APPLIED/.test(results[key]), `${key}: ${results[key]}`);
    assert(/Unknown NPC/.test(results.unknown), results.unknown);
    await combatCommand('cmelee', 'disengage');
    await server.cmd('animus delete cmelee', /Deleted/);
    note(JSON.stringify(results));
    return Object.entries(results).map(([k, v]) => `${k}=${v.replace(/^Combat [^:]*: /, '').slice(0, 60)}`).join('; ').slice(0, 300);
  });
  // Mod servers have no protection API: every hit is allowed and pursuit regions report unavailable.
  if (MOD) await check('protection none and pursuit regions unavailable on a mod server', async () => {
    const status = plain(await server.capture('animus combat status', /Combat \w+[^\n]*/)).match(/Combat \w+[^\n]*/)[0];
    assert(/protection=none \(mod servers have no standard protection API\)/.test(status), status);
    assert(/mob-control=unavailable on mod servers/.test(status) && /pursuit-regions=unavailable/.test(status), status);
    return status.slice(status.indexOf('protection='));
  });
  await check('melee kills a mob; victimDrops false drops nothing', async () => {
    const holding = 'equipment:{mainhand:{id:"minecraft:diamond",count:1}},drop_chances:{mainhand:2.0f}';
    const control = await summon(11, 'minecraft:zombie', -24, 61, -24, `NoAI:1b,${holding}`);
    note(`control equipment: ${plain(await server.capture(`data get entity ${control} equipment`, /following entity data[^\n]*|No entity[^\n]*|Found no[^\n]*/))}`);
    await server.cmd(`kill ${control}`, /Killed/);
    await delay(600);
    const controlDrops = await entityCount('@e[type=item,x=-24,y=61,z=-24,distance=..4]');
    note(`mob drops gamerule: ${plain(await server.capture('gamerule minecraft:mob_drops', /mob_drops[^\n]*|Incorrect[^\n]*|Unknown[^\n]*/))}`);
    await server.cmd('kill @e[type=item]', /Killed|No entity/);
    const zombie = await summon(12, 'minecraft:zombie', -24, 61, -27, `NoAI:1b,${holding},Health:8f`);
    await server.npc('cslayer', 'minecraft:player', -24.5, 61, -30.5, 'Slayer', combatNpc({
      targets: { entityTypes: ['minecraft:zombie'] }, targeting: { scanRange: 16, scanIntervalTicks: 5, lineOfSight: 'ignored' },
      melee: { damage: 5, reach: 3, cooldownTicks: 10 }, pursuit: { speed: 4 }, death: { victimDrops: false },
    }));
    await until(async () => ({ ok: await entityCount(`@e[type=zombie,nbt={UUID:[I;12,0,0,1]}]`) === 0 }), 20000, 'zombie survived');
    await delay(800);
    const drops = await entityCount('@e[type=item,x=-24,y=61,z=-27,distance=..6]');
    const orbs = await entityCount('@e[type=experience_orb,x=-24,y=61,z=-27,distance=..6]');
    await server.cmd('animus delete cslayer', /Deleted/);
    assert(controlDrops >= 1, `control zombie dropped ${controlDrops}`);
    assert.equal(drops, 0, `${drops} items near ${zombie}`);
    assert.equal(orbs, 0);
    return `control kill dropped ${controlDrops}; NPC kill dropped items=${drops} orbs=${orbs}`;
  });
  await check('arrows hit once each and are removed on disengage', async () => {
    const target = await summon(13, 'minecraft:husk', -30, 61, -1, `${sturdy(100)}`);
    await server.npc('carcher', 'minecraft:player', -30.5, 61, -22.5, 'Archer', combatNpc({
      targets: { entityTypes: ['minecraft:husk'] }, targeting: { scanRange: 24, scanIntervalTicks: 5, lineOfSight: 'ignored' },
      melee: { enabled: false }, pursuit: { closeChase: false, rangedChase: false },
      ranged: { enabled: true, weapon: 'arrow', damage: 3, range: 24, minimumRange: 2, cooldownTicks: 40, velocity: 1.6, inaccuracy: 0, safeShot: true },
    }));
    const samples = [100];
    await until(async () => { const h = await entityNumber(target, 'Health'); if (h !== samples.at(-1)) samples.push(h); return { ok: samples.length >= 4, samples }; }, 30000, 'fewer than three arrow hits');
    const inFlight = (await until(async () => ({ ok: await entityCount('@e[type=arrow]') > 0 }), 8000, 'never saw an arrow in flight')).ok ? await entityCount('@e[type=arrow]') : 0;
    const disengaged = await combatCommand('carcher', 'disengage');
    await delay(200);
    const left = await entityCount('@e[type=arrow]');
    const status = plain(await server.capture('animus combat status', /Combat \w+[^\n]*/)).match(/helpers=\d+/)?.[0];
    await server.cmd('animus delete carcher', /Deleted/);
    await server.cmd(`kill ${target}`, /Killed/);
    const hits = samples.slice(1).map((h, i) => +(samples[i] - h).toFixed(3));
    // A husk has 2 armor points: vanilla reduces one 3-damage arrow by max(2/5, 2 - 3/2) / 25 to 2.94.
    assert(hits.every(d => Math.abs(d - 2.94) < .01), `hits ${hits}`);
    assert.equal(left, 0, `${left} arrows after disengage`);
    assert.equal(status, 'helpers=0');
    return `health ${samples.join(' -> ')}; arrows in flight before disengage=${inFlight}, after=${left}; ${disengaged}`;
  });
  await check('fireballs hit once each with no explosion', async () => {
    const target = await summon(15, 'minecraft:husk', -36, 61, -6, sturdy(100));
    // A bystander inside any explosion radius; the NPC never targets cows.
    const bystander = await summon(16, 'minecraft:cow', -34.5, 61, -6, sturdy(100));
    await server.npc('cfire', 'minecraft:player', -36.5, 61, -22.5, 'Pyro', combatNpc({
      targets: { entityTypes: ['minecraft:husk'] }, targeting: { scanRange: 24, scanIntervalTicks: 5, lineOfSight: 'ignored' },
      melee: { enabled: false }, pursuit: { closeChase: false, rangedChase: false },
      ranged: { enabled: true, weapon: 'fireball', damage: 3, range: 24, minimumRange: 2, cooldownTicks: 40, velocity: 1.6, inaccuracy: 0, safeShot: true },
    }));
    const samples = [100];
    await until(async () => { const h = await entityNumber(target, 'Health'); if (h !== samples.at(-1)) samples.push(h); return { ok: samples.length >= 3, samples }; }, 30000, 'fewer than two fireball hits');
    const disengaged = await combatCommand('cfire', 'disengage');
    await delay(300);
    const left = await entityCount('@e[type=fireball]');
    const status = plain(await server.capture('animus combat status', /Combat \w+[^\n]*/)).match(/helpers=\d+/)?.[0];
    const spared = await entityNumber(bystander, 'Health');
    const crater = plain(await server.capture('fill -40 60 -10 -31 60 -2 stone replace air', /Successfully filled[^\n]*|No blocks were filled[^\n]*/));
    const fire = plain(await server.capture('fill -40 61 -10 -31 63 -2 air replace minecraft:fire', /Successfully filled[^\n]*|No blocks were filled[^\n]*/));
    await server.cmd('animus delete cfire', /Deleted/);
    await server.cmd(`kill ${target}`, /Killed/);
    await server.cmd(`kill ${bystander}`, /Killed/);
    const hits = samples.slice(1).map((h, i) => +(samples[i] - h).toFixed(3));
    assert(hits.every(d => Math.abs(d - hits[0]) < .01 && d > 0), `hits ${hits}`);
    assert.equal(spared, 100, `bystander health ${spared}`);
    assert(/No blocks were filled/.test(crater), `floor changed: ${crater}`);
    assert(/No blocks were filled/.test(fire), `fire placed: ${fire}`);
    assert.equal(left, 0, `${left} fireballs after disengage`);
    assert.equal(status, 'helpers=0');
    return `health ${samples.join(' -> ')}; bystander ${spared}; ${crater.slice(0, 40)}; ${disengaged}`;
  });
  await check('imported-style guard with ignores.owner never targets its owner', async () => {
    await server.cmd(`effect give ${OWNER.name} minecraft:instant_health 1 5 true`, /Applied|effect/i);
    await owner.tp(-20, 61, -12);
    const peer = await join(PEER.name, 'survival');
    await server.npc('cowned', 'minecraft:player', -20.5, 61, -14.5, 'Sentry', { owner: OWNER.uuid, ...combatNpc({
      targets: { categories: ['players'] }, ignores: { owner: true }, targeting: { scanRange: 16, scanIntervalTicks: 5, lineOfSight: 'ignored' },
      melee: { damage: 1, reach: 3, cooldownTicks: 20 }, pursuit: { speed: 3, giveUpRange: 24, leashRange: 24 },
    }) });
    const from = owner.events.length;
    const quiet = [];
    for (let i = 0; i < 8; i++) { quiet.push(await combatOf('cowned')); await delay(500); }
    const ownerHit = owner.events.slice(from).some(e => e.event === 'health' && e.health < 20);
    await peer.tp(-22, 61, -14);
    const peerTarget = await until(async () => { const s = await combatOf('cowned'); return { ok: s.includes(PEER.uuid), s }; }, 15000, 'never targeted the other player');
    await server.cmd('animus delete cowned', /Deleted/);
    await peer.close();
    assert(quiet.every(s => /target=none/.test(s) && !s.includes(OWNER.uuid)), quiet.find(s => !/target=none/.test(s)));
    assert(!ownerHit, 'owner took damage');
    assert(!peerTarget.s.includes(OWNER.uuid), peerTarget.s);
    return `owner alone: ${quiet.at(-1).slice(0, 80)}; with peer: ${peerTarget.s.slice(0, 100)}`;
  });
  await check('pursuit, leash and return to post', async () => {
    const post = { x: -10.5, z: -30.5 };
    const zombie = await summon(14, 'minecraft:zombie', -10, 61, -24, sturdy(200));
    await server.npc('cchase', 'minecraft:player', post.x, 61, post.z, 'Chaser', combatNpc({
      targets: { entityTypes: ['minecraft:zombie'] }, targeting: { scanRange: 16, scanIntervalTicks: 5, lineOfSight: 'ignored' },
      melee: { damage: 1, reach: 3, cooldownTicks: 20 }, pursuit: { speed: 4, giveUpRange: 24, leashRange: 10, returnToPost: true },
    }));
    const chased = await until(async () => { const n = await inspect('cchase'); return { ok: Math.hypot(n.x - post.x, n.z - post.z) > 3, n: n.line.slice(0, 160) }; }, 15000, 'never pursued');
    await server.cmd(`tp ${zombie} -10 61 -14`, /Teleported/);
    const dropped = await until(async () => { const s = await combatOf('cchase'); return { ok: /target=none/.test(s), s }; }, 15000, 'kept target past leash');
    const home = await until(async () => { const n = await inspect('cchase'); return { ok: Math.hypot(n.x - post.x, n.z - post.z) < 1, n: n.line.slice(0, 200) }; }, 20000, 'did not return to post');
    await server.cmd('animus delete cchase', /Deleted/);
    await server.cmd(`kill ${zombie}`, /Killed/);
    return `pursued (${chased.n.match(/position=[^;]*/)?.[0]}); ${dropped.s}; back ${home.n.match(/position=[^;]*/)?.[0]}`;
  });
  await check('death and respawn refire the spawn trigger', async () => {
    await gamemode('creative');
    await owner.tp(-20, 61, -6);
    const from = owner.events.length;
    const uuid = await server.npc('cfrail', 'minecraft:player', -20.5, 61, -4.5, 'Frail', combatNpc({ targeting: { fightBack: false }, defense: { maximumHealth: 2, healRateTicks: 0, invulnerableTicks: 0 }, respawn: { delayTicks: 40 } }, ['live:fighter']));
    await owner.chat('fighter-up', from);
    const first = await owner.body(uuid);
    for (let i = 0; i < 4 && !owner.has(e => e.event === 'chat' && e.text.includes('fighter-down'), from); i++) { await click(owner, uuid, 'attack'); await delay(700); }
    await owner.chat('fighter-down', from).catch(async error => { throw new Error(`${error.message}; trace: ${plain(await server.capture('animus behaviors trace cfrail', /Behavior trace[^\n]*/, 400)).split('\n').filter(l => /recent|trace/.test(l)).join(' / ').slice(0, 900)}`); });
    await owner.wait(e => e.event === 'remove' && e.ids.includes(first.id), from, 5000);
    const downAt = owner.events.length;
    await owner.chat('fighter-up', downAt, 8000);
    const again = await owner.wait(e => e.event === 'spawn' && e.uuid === uuid, downAt, 8000);
    const health = (await inspect('cfrail')).health;
    await server.cmd('animus delete cfrail', /Deleted/);
    assert.equal(health, 2);
    return `died, respawned as entity ${again.id} with health ${health}; spawn trigger fired twice`;
  });
  await owner.close();
  if (fixture) await check('qa/combat-client.mjs', async () => {
    const done = spawnSync('node', [path.join(root, 'qa/combat-client.mjs'), '--version', version, '--port', String(server.port), '--libraries', serverLibraries, '--results', results, '--event-fixture', '--id', 'combat-live'], { encoding: 'utf8', timeout: 300_000 });
    writeFileSync(path.join(results, 'combat-client.out'), (done.stdout ?? '') + (done.stderr ?? ''));
    const passed = (done.stdout ?? '').split('\n').filter(line => line.includes('"status":"passed"')).length;
    assert.equal(done.status, 0, `exit ${done.status}; ${(done.stdout + done.stderr).split('\n').filter(Boolean).slice(-3).join(' | ').slice(0, 500)}`);
    return `${passed} checks passed`;
  });
  await server.cmd('difficulty peaceful', /difficulty/i);
}

// Vanilla prints nothing when the player already has that mode.
const gamemode = mode => server.cmd(`gamemode ${mode} ${OWNER.name}`, /game mode/i, 2000).catch(() => {});
const PACKS = ['greeter', 'patrol', 'dialogue', 'quest', 'shop', 'town-guard', 'personal-guard'];
async function packs() {
  await server.cmd('difficulty easy', /difficulty/i);
  server.floor = 'fill -30 61 -20 30 63 20 stone';
  await server.cmd(server.floor, /filled/);
  for (const pack of PACKS) {
    const source = path.join(root, 'examples/packs', pack);
    for (const entry of readdirSync(path.join(source, 'npcs/examples'))) {
      mkdirSync(path.join(data, 'npcs/examples'), { recursive: true });
      copyFileSync(path.join(source, 'npcs/examples', entry), path.join(data, 'npcs/examples', entry));
    }
    copyTree(source, path.join(data, 'packs', pack), name => name !== 'npcs' && name !== 'shops' && name !== 'gui');
    for (const folder of ['shops', 'gui']) if (existsSync(path.join(source, folder))) copyTree(path.join(source, folder), path.join(data, folder), () => true);
  }
  await server.stop();
  await server.start();
  const startup = logText().split('\n').filter(line => /pack|Behaviors? (catalog|load)/i.test(line) && /Animus/.test(line)).slice(0, 4).map(plain).join(' / ');
  note(`pack startup: ${startup}`);
  const uuidOf = id => JSON.parse(readFileSync(path.join(data, 'npcs/examples', `${id}.json`), 'utf8')).uuid;
  let owner = await join(OWNER.name);
  await check('packs load and every pack NPC is active', async () => {
    // The list pages at 20 rows; earlier scenarios' documents come first.
    let list = '';
    for (const page of [1, 2]) list += plain(await server.capture(`animus behaviors list ${page}`, /Behaviors: \d+ documents[^\n]*/, 600));
    note(list);
    const rows = list.split('\n').filter(line => /Behavior \S+ \| path=packs\//.test(line));
    const problems = [];
    for (const id of ['greeter', 'patroller', 'innkeeper', 'quest-giver', 'harbour-master', 'shopkeeper', 'town-guard', 'bodyguard']) {
      const runtime = await inspect(id);
      if (!/: active/.test(runtime.line)) problems.push(runtime.line.slice(0, 160));
    }
    assert.deepEqual(problems, []);
    const bad = rows.filter(line => !/state=VALID \| available=true/.test(line));
    assert.deepEqual(bad, []);
    assert.equal(rows.length, 8, list.slice(0, 600));
    return `${rows.length} pack behaviors VALID and available; 8 pack NPCs active`;
  });
  const greeter = uuidOf('greeter');
  await check('greeter waves on approach, greets once, then remembers', async () => {
    await owner.tp(-7.5, 64, 12);
    await owner.body(greeter);
    const from = owner.events.length;
    await owner.tp(-7.5, 64, 3);
    const wave = await owner.wait(e => e.event === 'particle' && e.particle === 'minecraft:happy_villager', from, 6000);
    const clickAt = await click(owner, greeter);
    await owner.chat('Welcome, traveller! The market is to the north.', clickAt);
    await owner.wait(e => e.event === 'sound' && e.sound === 'minecraft:entity.villager.ambient', clickAt, 3000);
    await delay(2200);
    const again = await click(owner, greeter);
    await owner.chat('Good to see you again, traveller!', again);
    return `wave particles=${wave.count}`;
  });
  const innkeeper = uuidOf('innkeeper');
  await check('dialogue answers lead to more prompts and change the next one', async () => {
    await owner.tp(-7.5, 64, 11);
    let from = await click(owner, innkeeper);
    const first = await choose(owner, from, 'rumours');
    assert.equal(new Set(first.prompt.clicks).size, 3, JSON.stringify(first.prompt.clicks));
    await owner.chat('Lights in the old mine, three nights running.', first.after);
    const more = await choose(owner, first.after, 'yes');
    await owner.chat('The miners left in a hurry.', more.after);
    await delay(2200);
    from = await click(owner, innkeeper);
    const asked = await owner.chat('Back again?', from);
    const back = await owner.wait(e => e.event === 'chat' && e.sequence > asked.sequence && e.clicks?.length, from);
    assert.equal(new Set(back.clicks).size, 2, JSON.stringify(back.clicks));
    const room = await choose(owner, from, 'room');
    await owner.chat('Upstairs, second door.', room.after);
    return `first prompt options=${new Set(first.prompt.clicks).size}, after rumour=${new Set(back.clicks).size}`;
  });
  const giver = uuidOf('quest-giver'), master = uuidOf('harbour-master');
  await check('quest accepted, stage kept across restart', async () => {
    await owner.tp(-7.5, 64, -4.5);
    const from = await click(owner, giver);
    const accepted = await choose(owner, from, 'accept');
    await owner.chat('The harbour master is by the docks.', accepted.after);
    await owner.close(); await delay(300);
    await server.stop();
    await server.start();
    owner = await join(OWNER.name);
    await owner.tp(-7.5, 64, -4.5);
    const after = await click(owner, giver);
    await owner.chat('still waiting for that letter', after);
    const stored = readdirSync(path.join(data, 'state/behavior-state/player', OWNER.uuid), { recursive: true }).filter(name => String(name).endsWith('.json'));
    return `stored ${stored.join(',')}`;
  });
  await check('quest delivered and completed', async () => {
    await owner.tp(24.5, 64, -4.5);
    let from = await click(owner, master);
    await owner.chat('A letter for me?', from);
    await owner.tp(-7.5, 64, -4.5);
    await delay(2200);
    from = await click(owner, giver);
    await owner.chat('Delivered! Thank you.', from);
    await owner.wait(e => e.event === 'sound' && e.sound === 'minecraft:entity.player.levelup', from, 3000);
    await delay(2200);
    from = await click(owner, giver);
    await owner.chat('You have done enough for me, friend.', from);
  });
  await check('patrol walks its timed round while a pack reloads', async () => {
    // The pack starts its round from a 1,200-tick timer; a manual run beside it would replace its route.
    await owner.tp(4, 64, 4);
    const from = owner.events.length;
    await owner.chat('Starting my rounds.', from, 75_000);
    const started = Date.now();
    await delay(4000);
    const file = path.join(data, 'packs/greeter/behaviors/greeter/greeter.json');
    const document = JSON.parse(readFileSync(file, 'utf8'));
    document.revision += 1;
    writeFileSync(file, JSON.stringify(document, null, 2).replace('Good to see you again, &etraveller&f!', 'Back so soon?') + '\n');
    const reload = await server.reload();
    await owner.chat('All quiet.', from, 90_000);
    const seconds = ((Date.now() - started) / 1000).toFixed(0);
    // The closing say finishes on a later tick than its chat delivery, so the round may still read active for a moment.
    await until(async () => {
      const trace = plain(await server.capture('animus behaviors trace patroller', /Behavior trace[^\n]*/, 400));
      const line = trace.split('\n').find(text => /trigger=patrol:route#rounds/.test(text)) ?? trace.slice(0, 400);
      return { ok: /state=COMPLETED/.test(line), round: line };
    }, 10_000, 'round never completed');
    const end = await inspect('patroller');
    assert(Math.hypot(end.x - .5, end.z - .5) < .8, end.line.slice(0, 200));
    await owner.tp(-7.5, 64, 3);
    const clickAt = await click(owner, greeter);
    await owner.chat('Back so soon?', clickAt);
    return `${reload[0].slice(0, 70)}; round took ${seconds}s, COMPLETED, ended at ${end.x.toFixed(2)},${end.y},${end.z.toFixed(2)}`;
  });
  await check('malformed pack edit keeps last valid across restart', async () => {
    await owner.close(); await delay(300);
    await server.stop();
    const file = path.join(data, 'packs/greeter/behaviors/greeter/greeter.json');
    writeFileSync(file, readFileSync(file, 'utf8').replace('"routines"', '"routines" broken'));
    await server.start();
    const startup = logText().split('\n').filter(line => /greeter/.test(line) && /diagnostic|invalid/i.test(line)).slice(0, 2).map(plain).join(' / ');
    owner = await join(OWNER.name);
    await owner.tp(-7.5, 64, 3);
    const clickAt = await click(owner, greeter);
    await owner.chat('Back so soon?', clickAt);
    assert(startup, 'startup did not report the broken pack document');
    const document = readFileSync(file, 'utf8').replace('"routines" broken', '"routines"').replace(/"revision": (\d+)/, (all, n) => `"revision": ${Number(n) + 1}`);
    writeFileSync(file, document);
    assert.match((await server.reload())[0], /APPLIED/);
    return startup.replace(/\[[^\]]*\]: /g, '').slice(0, 220);
  });
  const guard = uuidOf('town-guard');
  // Every restart sets peaceful, which refuses hostile mobs.
  await server.cmd('difficulty easy', /difficulty/i);
  await check('town guard arms, challenges, fights a hostile mob and returns to post', async () => {
    await owner.tp(-15.5, 64, 9);
    await owner.body(guard);
    // The owner first crossed the 12-block radius beside the greeter, so the 200-tick cooldown may still hold.
    await owner.chat(/Evening, citizen\.|Stranger, state your business/, 0, 15000);
    const greeting = owner.events.find(e => e.event === 'chat' && /citizen|Stranger/.test(e.text)).text;
    let from = owner.events.length;
    const armed = owner.events.find(e => e.event === 'equipment' && e.id === owner.current(guard)?.id && e.slots.mainhand === 'minecraft:iron_sword');
    // The cooldown is per trigger on the NPC, not per player: let the owner's greeting expire first.
    await delay(10_500);
    const peer = await join(PEER.name, null);
    await peer.tp(-15.5, 64, 9);
    await peer.chat('Stranger, state your business', 0);
    await peer.close();
    const zombie = await summon(21, 'minecraft:zombie', -15.5, 64, -9.5, 'NoAI:1b');
    from = owner.events.length;
    const fought = await until(async () => { const s = await combatOf('town-guard'); return { ok: /target=MOB:/.test(s), s }; }, 10000, 'guard never targeted the zombie');
    await until(async () => ({ ok: await entityCount('@e[type=zombie,nbt={UUID:[I;21,0,0,1]}]') === 0 }), 30000, 'zombie survived the guard');
    const home = await until(async () => { const n = await inspect('town-guard'); return { ok: Math.hypot(n.x + 15.5, n.z - .5) < 1, n: n.line.slice(0, 200) }; }, 30000, 'guard did not return to post');
    assert.equal(greeting.includes('Evening, citizen.'), true, greeting);
    assert(armed, 'guard never showed an iron sword');
    return `${greeting.slice(0, 40)}; ${fought.s}; back at ${home.n.match(/position=[^;]*/)?.[0]}`;
  });
  const bodyguard = uuidOf('bodyguard');
  await check('bodyguard hired for emeralds, follows, defends, then dismissed', async () => {
    await gamemode('survival');
    await server.cmd(`clear ${OWNER.name} minecraft:emerald`, /Removed|No items/);
    await server.cmd(`give ${OWNER.name} minecraft:emerald 5`, /Gave/);
    await owner.tp(-15.5, 64, 11.5);
    let from = await click(owner, bodyguard);
    let window = await windowOpened(owner, from);
    assert.equal(window.items.find(i => i.slot === 20)?.item, 'minecraft:lime_concrete', JSON.stringify(window.items));
    from = await clickSlot(owner, window.container, 20);
    await owner.chat(/Hired Bodyguard for 30 minutes/, from, 8000);
    assert.equal(await itemCount(OWNER.name, 'minecraft:emerald'), 0, 'the hire took the five emeralds');
    // Walk off: the guard replans as the charge moves and teleports beside it past catchUpDistance.
    await owner.walk(-15.5, 4);
    await owner.tp(14, 64, 14);
    const followed = await until(async () => { const n = await inspect('bodyguard'); return { ok: Math.hypot(n.x - 14, n.z - 14) < 6, n: n.line.slice(0, 160) }; }, 30000, 'bodyguard did not follow');
    const attacker = await summon(22, 'minecraft:zombie', 18, 64, 14, sturdy(20, true));
    const defended = await until(async () => ({ ok: await entityCount('@e[type=zombie,nbt={UUID:[I;22,0,0,1]}]') === 0 }), 40000, 'bodyguard did not kill the attacker');
    await delay(2200);
    from = await click(owner, bodyguard);
    window = await windowOpened(owner, from);
    assert.equal(window.items.find(i => i.slot === 22)?.item, 'minecraft:red_concrete', JSON.stringify(window.items));
    from = await clickSlot(owner, window.container, 22);
    await owner.chat(/is dismissed/, from, 8000);
    const status = await combatOf('bodyguard');
    // Sampled after dismissal: a follow step that was already under way may land before it.
    const near = await inspect('bodyguard');
    await owner.tp(25, 64, -15);
    await delay(5000);
    const stayed = await inspect('bodyguard');
    await gamemode('creative');
    assert(/target=none/.test(status), status);
    assert(Math.hypot(stayed.x - near.x, stayed.z - near.z) < 2, `followed after dismissal: ${near.line.match(/position=[^;]*/)?.[0]} -> ${stayed.line.match(/position=[^;]*/)?.[0]}`);
    return `followed to ${followed.n.match(/position=[^;]*/)?.[0]}; attacker killed=${defended.ok}; after dismiss ${status}`;
  });
  await check('the bakery shop and its counter GUI load from the shop pack', async () => {
    const status = plain(await server.capture('animus shop status', /Shops [^\n]*/, 600));
    assert(/shops=1\b/.test(status) && /guis=1\b/.test(status) && /problems=0\b/.test(status), status);
    const listed = plain(await server.capture('animus shop list', /shop:bakery[^\n]*/, 600));
    assert(/shop:bakery/.test(listed), listed);
    return status;
  });
  await owner.close();
  for (const id of ['greeter', 'patroller', 'innkeeper', 'quest-giver', 'harbour-master', 'shopkeeper', 'town-guard', 'bodyguard']) await server.cmd(`animus delete ${id}`, /Deleted|Unknown/);
  await server.cmd('kill @e[type=!player]', /Killed|No entity/);
  server.floor = null;
  await server.cmd('fill -30 61 -20 30 63 20 air', /filled/);
  await server.cmd('difficulty peaceful', /difficulty/i);
}
function copyTree(from, to, keep = () => true) {
  mkdirSync(to, { recursive: true });
  for (const entry of readdirSync(from, { withFileTypes: true })) {
    if (!keep(entry.name)) continue;
    if (entry.isDirectory()) copyTree(path.join(from, entry.name), path.join(to, entry.name));
    else copyFileSync(path.join(from, entry.name), path.join(to, entry.name));
  }
}

// ---- shared helpers -------------------------------------------------------------------------

async function blockIs(x, y, z, id) {
  const found = await server.cmd(`execute if block ${x} ${y} ${z} ${id}`, /Test (passed|failed)/);
  return found[1] === 'passed';
}
async function buildStatus(id) {
  const pattern = new RegExp(`Build ${id}: [^\\n]*|No build for ${id}[^\\n]*|Animus build: [^\\n]*|Builds unavailable: [^\\n]*`);
  return plain(await server.capture(`animus build ${id} status`, pattern, 150)).match(pattern)[0];
}
// The next chest window after `from`, with the contents the server sent for it.
async function windowOpened(client, from, timeout = 10_000) {
  const opened = await client.wait(e => e.event === 'open_screen', from, timeout);
  const content = await client.wait(e => e.event === 'container_content' && e.container === opened.container && e.sequence > opened.sequence, from, timeout);
  return { container: opened.container, menu: opened.menu, title: opened.title, size: content.size, items: content.items };
}
// One left click (vanilla PICKUP, button 0; Bukkit ClickType.LEFT) on a slot of an open window.
async function clickSlot(client, container, slot) {
  const from = client.events.length;
  client.send('click_container', { container, slot, button: 0, mode: 'pickup' });
  await client.wait(e => e.event === 'container_click_sent' && e.container === container && e.slot === slot, from, 5000);
  return from;
}
async function closeWindow(client, container) {
  const from = client.events.length;
  client.send('close_container', { container });
  await client.wait(e => e.event === 'container_close_sent' && e.container === container, from, 5000);
}

// ---- scenario: building ---------------------------------------------------------------------

// x 24..26, y 61..62, z 24..26: clear of every fill, setblock, tp and NPC position the other scenarios use.
const BUILD = { npc: 'mason', file: 'live-tiny.nbt', origin: [24, 61, 24] };
async function building() {
  const owner = await join(OWNER.name, 'survival');
  await check('npc builds a tiny .nbt with creative materials', async () => {
    const [ox, oy, oz] = BUILD.origin;
    const box = `${ox} ${oy} ${oz} ${ox + 2} ${oy + 1} ${oz + 2}`;
    const file = path.join(data, 'schematics', BUILD.file);
    let created = false;
    try {
      mkdirSync(path.dirname(file), { recursive: true });
      const made = spawnSync('python3', ['-I', path.join(root, 'qa/multiplexor/make-nbt.py'), file], { encoding: 'utf8' });
      assert.equal(made.status, 0, made.stderr);
      const blocks = JSON.parse(made.stdout).blocks.map(b => ({ x: ox + b.pos[0], y: oy + b.pos[1], z: oz + b.pos[2], id: b.name }));
      assert.equal(blocks.length, 18);
      await server.cmd(`fill ${box} air`, /Successfully filled|No blocks were filled/);
      await server.cmd(`clear ${OWNER.name}`, /Removed|No items/);
      const kinds = ['minecraft:stone', 'minecraft:oak_planks', 'minecraft:glass'];
      for (const id of kinds) await server.cmd(`give ${OWNER.name} ${id} 5`, /Gave/);
      await owner.tp(20.5, 61, 20.5);
      await server.npc(BUILD.npc, 'minecraft:player', 22.5, 61, 25.5, 'Mason');
      created = true;
      await until(async () => { const n = await inspect(BUILD.npc); return { ok: /: active/.test(n.line), line: n.line.slice(0, 160) }; }, 15_000, 'mason never became active');
      const listed = plain(await server.capture('animus build schematics', /Schematics \(\d+\):[^\n]*|No schematics[^\n]*/));
      assert(listed.includes(BUILD.file), listed);
      const from = logText().length;
      const startPattern = new RegExp(`Started build ${BUILD.npc}[^\\n]*|Cannot start build ${BUILD.npc}[^\\n]*|Animus build: [^\\n]*|Builds unavailable: [^\\n]*`);
      const started = plain((await server.cmd(`animus build start ${BUILD.npc} ${BUILD.file} at=${ox},${oy},${oz} anchor=corner materials=creative interval=1 timeout=5`, startPattern))[0]);
      assert.match(started, new RegExp(`^Started build ${BUILD.npc}: ${BUILD.file.replace('.', '\\.')} 18 steps from ${ox} ${oy} ${oz} to ${ox + 2} ${oy + 1} ${oz + 2}$`));
      // BuildService logs "Build <id> finished" when the last block is placed (the console starter gets no chat line).
      await server.waitLog(new RegExp(`Build ${BUILD.npc} finished`), from, 90_000);
      const status = await buildStatus(BUILD.npc);
      assert.match(status, /: finished 18\/18 \(100%\)/);
      assert.match(status, /placed=18 skipped=0 denied=0 no-item=0 refused=0/);
      assert.match(status, /materials=creative \| undo=18\b/);
      const survey = plain(await server.capture(`animus build ${BUILD.npc} survey`, new RegExp(`Build ${BUILD.npc} [^\\n]*`)));
      assert(survey.includes(`Build ${BUILD.npc} uses creative materials; nothing is needed`), survey);
      const wrong = [];
      for (const b of blocks) if (!await blockIs(b.x, b.y, b.z, b.id)) wrong.push(`${b.x},${b.y},${b.z}!=${b.id}`);
      assert.deepEqual(wrong, []);
      // Nothing outside the box changed: one cell on each open side.
      for (const [x, y, z] of [[ox - 1, oy, oz], [ox + 3, oy, oz + 2], [ox + 1, oy + 2, oz + 1], [ox + 1, oy, oz - 1], [ox + 1, oy, oz + 3]])
        assert(await blockIs(x, y, z, 'minecraft:air'), `cell ${x},${y},${z} changed`);
      // Creative materials take nothing from anyone: the owner still holds exactly what was given.
      const held = [];
      for (const id of kinds) held.push(await itemCount(OWNER.name, id));
      assert.deepEqual(held, [5, 5, 5]);
      // Undo puts the air back; it does not walk and is limited by undoPerTick (32), so 18 cells take one tick.
      const undo = plain((await server.cmd(`animus build ${BUILD.npc} undo`, new RegExp(`Undoing \\d+ changes of build ${BUILD.npc}[^\\n]*|Animus build: [^\\n]*`)))[0]);
      assert.equal(undo, `Undoing 18 changes of build ${BUILD.npc}`);
      const undone = await until(async () => { const s = await buildStatus(BUILD.npc); return { ok: /: undone /.test(s), s }; }, 20_000, 'undo did not finish');
      assert.match(undone.s, /restored=18 kept=0/);
      const left = [];
      for (const b of blocks) if (!await blockIs(b.x, b.y, b.z, 'minecraft:air')) left.push(`${b.x},${b.y},${b.z}`);
      assert.deepEqual(left, []);
      return `${started.slice(0, 90)}; ${status.match(/placed=[^|]*/)[0].trim()}; undo ${undone.s.match(/restored=\d+ kept=\d+/)[0]}`;
    } finally {
      await server.cmd(`fill ${box} air`, /Successfully filled|No blocks were filled/);
      if (created) await server.cmd(`animus delete ${BUILD.npc}`, /Deleted|Unknown/);
      if (existsSync(file)) unlinkSync(file);
      await server.cmd(`clear ${OWNER.name}`, /Removed|No items/);
    }
  });
  await gamemode('creative');
  await owner.close();
}

// ---- scenario: shops ------------------------------------------------------------------------

// Slot 11 of a 3-row page; the bottom row (18..26) holds the controls, and only close (slot 22) shows
// because the shop has one page and one mode.
const SHOP = { id: 'live:swap', npc: 'swapper', slot: 11, at: [-26.5, 61, 26.5] };
const swapShop = () => ({
  schemaVersion: 1, revision: 1, id: SHOP.id, title: '&bSwap', modes: ['trade'],
  pages: [{ name: 'Swap', rows: 3, trade: { [String(SHOP.slot)]: {
    id: 'diamond-for-emeralds', type: 'trade', item: { id: 'minecraft:diamond' },
    needs: [{ item: { id: 'minecraft:emerald' }, amount: 2 }],
    gives: [{ item: { id: 'minecraft:diamond' }, amount: 1 }],
  } } }],
});
async function shops() {
  const owner = await join(OWNER.name, 'survival');
  await check('shop item-for-item trade', async () => {
    const file = path.join(data, 'shops', 'live', 'swap.json');
    let created = false, open = null;
    const counts = async () => ({ emerald: await itemCount(OWNER.name, 'minecraft:emerald'), diamond: await itemCount(OWNER.name, 'minecraft:diamond') });
    try {
      mkdirSync(path.dirname(file), { recursive: true });
      writeFileSync(file, JSON.stringify(swapShop(), null, 1) + '\n');
      const reloaded = plain((await server.cmd('animus shop reload', /Reloaded \d+ shops and \d+ GUIs; \d+ problems/))[0]);
      const listed = plain(await server.capture('animus shop list', /Shops: \d+/, 400));
      assert(new RegExp(`${SHOP.id} \\| [^|]*Swap \\| pages=1`).test(listed), listed);
      const problems = plain(await server.capture('animus shop problems', /Shop and GUI problems: \d+/, 400));
      assert(!/live[:/]swap/.test(problems), problems);
      const uuid = await server.npc(SHOP.npc, 'minecraft:player', ...SHOP.at, 'Swapper', { modules: { 'animus:shop': { shop: SHOP.id, openOn: 'right' } } });
      created = true;
      await server.cmd(`clear ${OWNER.name}`, /Removed|No items/);
      await server.cmd(`give ${OWNER.name} minecraft:emerald 2`, /Gave/);
      await owner.tp(SHOP.at[0], 61, SHOP.at[2] - 3);
      assert.deepEqual(await counts(), { emerald: 2, diamond: 0 });

      // 1. Open by right-clicking the NPC (animus:shop, openOn right) and trade with one left click.
      let from = await click(owner, uuid);
      open = await windowOpened(owner, from);
      assert.equal(open.menu, 'minecraft:generic_9x3', JSON.stringify(open));
      assert.equal(open.size, 27 + 36);
      assert.equal(open.items.find(i => i.slot === SHOP.slot)?.item, 'minecraft:diamond', JSON.stringify(open.items));
      assert.equal(open.items.find(i => i.slot === 22)?.item, 'minecraft:barrier', JSON.stringify(open.items));
      from = await clickSlot(owner, open.container, SHOP.slot);
      const traded = await owner.chat('Traded for', from, 8000);
      const paid = await until(async () => { const c = await counts(); return { ok: c.emerald === 0 && c.diamond === 1, ...c }; }, 5000, 'trade did not change the inventory');
      // Every click is cancelled: nothing was left on the cursor, and the window stays open (no closeAfterPurchase).
      assert(!owner.has(e => e.event === 'container_slot' && e.container === -1 && e.item !== 'empty', from), 'an item reached the cursor');
      assert(!owner.has(e => e.event === 'container_closed' && e.container === open.container, from), 'window closed after the trade');
      await closeWindow(owner, open.container);
      open = null;

      // 2. Open again with the command and try once more without the price: nothing changes.
      from = owner.events.length;
      await server.cmd(`animus shop open ${SHOP.id} ${OWNER.name}`);
      open = await windowOpened(owner, from);
      from = await clickSlot(owner, open.container, SHOP.slot);
      const refused = await owner.chat('You do not have the items for that.', from, 8000);
      await delay(500);
      assert.deepEqual(await counts(), { emerald: 0, diamond: 1 });
      await closeWindow(owner, open.container);
      open = null;
      const status = plain(await server.capture('animus shop status', /Shops [^\n]*/, 400)).match(/Shops [^\n]*/)[0];
      assert(/\| open=0 \|/.test(status), status);
      return `${reloaded}; "${traded.text}" -> emerald=${paid.emerald} diamond=${paid.diamond}; again: "${refused.text}"`;
    } finally {
      if (open) await closeWindow(owner, open.container).catch(() => {});
      if (created) await server.cmd(`animus delete ${SHOP.npc}`, /Deleted|Unknown/);
      if (existsSync(file)) unlinkSync(file);
      await server.cmd('animus shop reload', /Reloaded \d+ shops/);
      await server.cmd(`clear ${OWNER.name}`, /Removed|No items/);
    }
  });
  // Mod servers have no economy API: item trades work, money is reported unavailable.
  if (MOD) await check('money reported unavailable on a mod server', async () => {
    const status = plain(await server.capture('animus shop status', /Shops [^\n]*/, 400)).match(/Shops [^\n]*/)[0];
    assert(/economy=unavailable \(mod servers have no standard economy API\)/.test(status), status);
    return status.slice(status.indexOf('economy='));
  });
  await gamemode('creative');
  await owner.close();
}

// ---- folia(): add inside the existing folia scenario ----------------------------------------

// AnimusPlugin.startBuilds sets this reason when there is no main-thread runtime; BuildCommands answers
// every /animus build form with "Builds unavailable: " + reason before reading any argument.
const FOLIA_BUILDS = 'Builds unavailable: unavailable on Folia: a build site, its NPC team and its material container have no shared region owner, and no handoff for a build exists yet';
async function foliaBuildsRefused() {
  await check('builds refuse on Folia with the reason', async () => {
    const pattern = /Builds unavailable: [^\n]*/;
    const status = plain((await server.cmd('animus build', pattern))[0]);
    const start = plain((await server.cmd(`animus build start nobody ${BUILD.file} at=0,61,0`, pattern))[0]);
    assert.equal(status, FOLIA_BUILDS);
    assert.equal(start, FOLIA_BUILDS);
    return status;
  });
}

// ---- scenario: smoke (start, spawn, interact, navigate, ride, restart) ----------------------

async function smoke() {
  server.behavior('live:smoke', {
    routines: { main: sayTo('manual'), clicked: sayTo('smoke-click'), arrived: say('smoke-arrived') },
    triggers: [{ id: 'click', event: 'interaction', routine: 'clicked', cooldownTicks: 0 }, { id: 'walked', event: 'waypoint', routine: 'arrived', cooldownTicks: 0 }],
  });
  await server.reload();
  let owner = await join(OWNER.name, 'survival');
  await owner.tp(0, 61, 0);
  const uuid = await server.npc('smoke', 'minecraft:player', 0, 61, 6, 'Smoke', { behaviors: ['live:smoke'] });
  let body = null;
  await check('smoke NPC spawns and renders', async () => { body = await owner.body(uuid); return `id=${body.id} at ${body.x},${body.y},${body.z}`; });
  await check('smoke interaction fires its trigger', async () => {
    await owner.walk(0, 3);
    await owner.look(body.x, body.y + .9, body.z);
    const from = owner.events.length; owner.send('use', { id: body.id });
    return (await owner.chat('Smoke » smoke-click', from)).text;
  });
  await check('smoke navigation arrives', async () => {
    const from = owner.events.length;
    await server.cmd('animus navigate smoke 4 61 6 walk 3', /./);
    await owner.chat('Smoke » smoke-arrived', from, 15_000);
    const moved = owner.events.slice(from).filter(e => e.event === 'move' && e.id === body.id).at(-1);
    assert(moved && Math.abs(moved.x - 4.5) < 1, JSON.stringify(moved));
    return `walked to ${moved.x.toFixed(2)},${moved.z.toFixed(2)}`;
  });
  const horse = await server.npc('shorse', 'minecraft:horse', -4, 61, 3, 'Steed', { modules: { 'animus:riding': { enabled: true, speed: 4, leash: 32, lifetimeSeconds: 600 } } });
  await check('smoke ride: mount, steer, sneak dismount', async () => {
    const offset = logText().length, steed = await owner.body(horse);
    await mount(owner, steed);
    const from = owner.events.length;
    await owner.look(steed.x, steed.y + 1, steed.z + 30);
    await owner.keys({ forward: true });
    await delay(3000);
    await owner.keys({});
    const last = owner.events.slice(from).filter(e => e.event === 'move' && e.id === steed.id).at(-1);
    const distance = last ? Math.hypot(last.x - steed.x, last.z - steed.z) : 0;
    assert(distance > 4, `moved ${distance}`);
    const off = owner.events.length;
    await owner.keys({ shift: true });
    await dismounted(owner, steed.id, off);
    await owner.keys({});
    await delay(500);
    assert(!/AnimusProofQA on/.test(await rideStatus()));
    const bad = logText().slice(offset).split('\n').filter(line => FORBIDDEN.test(line));
    assert.deepEqual(bad, []);
    return `rode ${distance.toFixed(2)} blocks`;
  });
  await owner.close(); await delay(300);
  await server.stop();
  await server.start();
  owner = await join(OWNER.name, 'survival');
  await owner.tp(0, 61, 0);
  await check('smoke restart keeps the NPC and its UUID', async () => {
    const again = await owner.body(uuid);
    const stored = JSON.parse(readFileSync(path.join(data, 'npcs/live', 'smoke.json'), 'utf8'));
    assert.equal(stored.uuid, uuid);
    assert.notEqual(again, null);
    const line = (await inspect('smoke')).line;
    assert(/: active/.test(line), line);
    return line.slice(0, 160);
  });
  await owner.close();
  await server.cmd('animus delete smoke', /Deleted/);
  await server.cmd('animus delete shorse', /Deleted/);
}

// ---- scenario: worlds (visibility rebuild on teleport, dimension change, reconnect) --------

async function worlds() {
  let owner = await join(OWNER.name, 'creative');
  await owner.tp(0, 61, 0);
  const uuid = await server.npc('wnpc', 'minecraft:player', 0, 61, 8, 'Wanderer');
  let body = await owner.body(uuid);
  await check('far teleport removes the NPC and return rebuilds it with a fresh id', async () => {
    const old = body, from = owner.events.length;
    await owner.tp(400, 61, 0);
    await owner.wait(e => e.event === 'remove' && e.ids.includes(old.id), from);
    assert(!owner.current(uuid));
    const back = owner.events.length;
    await owner.tp(0, 61, 0);
    body = await owner.body(uuid);
    assert(owner.events.slice(back).some(e => e.event === 'spawn' && e.uuid === uuid));
    assert.notEqual(body.id, old.id);
    const stale = owner.events.length;
    owner.send('use', { id: old.id });
    await delay(300);
    assert(!owner.fatal, owner.fatal?.message);
    return `old id ${old.id} -> ${body.id}; stale id click ignored (${owner.events.length - stale} events)`;
  });
  await check('short teleport keeps or rebuilds the NPC, never loses it', async () => {
    const old = body, from = owner.events.length;
    await owner.tp(2, 61, 2);
    await delay(800);
    const now = owner.current(uuid);
    assert(now, 'NPC no longer presented after a short teleport');
    body = now;
    const rebuilt = owner.events.slice(from).some(e => e.event === 'spawn' && e.uuid === uuid);
    return rebuilt ? `rebuilt: id ${old.id} -> ${now.id}` : `kept id ${now.id}`;
  });
  await check('dimension change clears presentation and return resubscribes', async () => {
    const old = body, from = owner.events.length;
    await server.cmd(`execute in minecraft:the_nether run tp ${OWNER.name} 0 100 0`, /Teleported/);
    await owner.wait(e => e.event === 'respawn' && String(e.dimension).includes('the_nether'), from);
    await delay(600);
    assert(!owner.current(uuid), 'overworld NPC still presented in the nether');
    assert(!owner.events.slice(from).some(e => e.event === 'spawn' && e.uuid === uuid));
    const back = owner.events.length;
    await server.cmd(`execute in minecraft:overworld run tp ${OWNER.name} 0 61 0`, /Teleported/);
    await owner.wait(e => e.event === 'respawn' && String(e.dimension).includes('overworld'), back);
    body = await owner.body(uuid);
    assert.notEqual(body.id, old.id);
    return `nether and back: ${old.id} -> ${body.id}`;
  });
  await check('an NPC in another dimension renders only there', async () => {
    await server.cmd('execute in minecraft:the_nether run forceload add -16 -16 16 16', /Marked|already|No chunks/);
    await delay(1000);
    await server.cmd('execute in minecraft:the_nether run fill -3 99 -3 3 99 3 minecraft:netherrack', /Successfully filled|No blocks were filled/);
    await server.cmd('execute in minecraft:the_nether run fill -3 100 -3 3 103 3 minecraft:air', /Successfully filled|No blocks were filled/);
    await server.cmd('animus create wnether minecraft:player minecraft:the_nether 2 100 2 live Netherling', /Created dormant wnether/);
    await server.cmd('animus spawn wnether', /Spawn requested for wnether/);
    const nether = JSON.parse(readFileSync(path.join(data, 'npcs/live', 'wnether.json'), 'utf8')).uuid;
    await delay(800);
    assert(!owner.current(nether), 'nether NPC presented in the overworld');
    const from = owner.events.length;
    await server.cmd(`execute in minecraft:the_nether run tp ${OWNER.name} 0 100 0`, /Teleported/);
    await owner.wait(e => e.event === 'respawn' && String(e.dimension).includes('the_nether'), from);
    const seen = await owner.body(nether);
    const line = (await inspect('wnether')).line;
    assert(/: active/.test(line), line);
    const back = owner.events.length;
    await server.cmd(`execute in minecraft:overworld run tp ${OWNER.name} 0 61 0`, /Teleported/);
    await owner.wait(e => e.event === 'respawn' && String(e.dimension).includes('overworld'), back);
    body = await owner.body(uuid);
    await server.cmd('animus delete wnether', /Deleted/);
    return `seen in the nether as id ${seen.id}; ${line.slice(0, 120)}`;
  });
  await check('reconnect gets a fresh presentation', async () => {
    const old = body;
    await owner.close(); await delay(400);
    owner = await join(OWNER.name, 'creative');
    body = await owner.body(uuid);
    return `relogin: ${old.id} -> ${body.id}`;
  });
  await owner.close();
  await server.cmd('animus delete wnpc', /Deleted/);
}

// A hired bodyguard through a long session: wandering, a dirt-path road, standing, far teleports, deaths, the nether,
// fights and a renewal. The guard must never stay more than 8 blocks behind for long. --soak-minutes sets the length.
async function guardsoak() {
  const minutes = Number(args.get('soak-minutes') ?? 16);
  const source = path.join(root, 'examples/packs/personal-guard');
  mkdirSync(path.join(data, 'npcs/examples'), { recursive: true });
  copyFileSync(path.join(source, 'npcs/examples/bodyguard.json'), path.join(data, 'npcs/examples/bodyguard.json'));
  copyTree(source, path.join(data, 'packs/personal-guard'), entry => entry !== 'npcs');
  server.floor = 'fill -40 61 -40 40 63 40 stone';
  await server.stop();
  await server.start();
  // The road the demo's players walk: dirt path, whose top is 1/16 below a full block.
  await server.cmd('fill -40 63 -1 40 63 1 minecraft:dirt_path', /filled/);
  await server.cmd('setworldspawn 0 64 6', /world spawn/i);
  await server.cmd('difficulty easy', /difficulty/i);
  await server.cmd('execute in minecraft:the_nether run forceload add -16 -16 16 16', /Marked|already|No chunks/);
  await delay(1000);
  await server.cmd('execute in minecraft:the_nether run fill -12 99 -12 12 99 12 minecraft:netherrack', /Successfully filled|No blocks were filled/);
  await server.cmd('execute in minecraft:the_nether run fill -12 100 -12 12 104 12 minecraft:air', /Successfully filled|No blocks were filled/);
  const bodyguard = JSON.parse(readFileSync(path.join(data, 'npcs/examples/bodyguard.json'), 'utf8')).uuid;
  const owner = await join(OWNER.name, null);
  await gamemode('survival');
  const hire = async pattern => {
    await server.cmd(`give ${OWNER.name} minecraft:emerald 5`, /Gave/);
    const window = await windowOpened(owner, await click(owner, bodyguard));
    const from = await clickSlot(owner, window.container, 20);
    await owner.chat(pattern, from, 8000);
    await closeWindow(owner, window.container).catch(() => {});
  };
  await check('bodyguard hired for the soak', async () => {
    await owner.tp(-15.5, 64, 11.5);
    await hire(/Hired Bodyguard for 30 minutes/);
    return combatOf('bodyguard');
  });
  const position = () => owner.position;
  let where = 'overworld', zombies = 30, worst = 0, worstAt = '', samples = 0, laggingSince = null, renewed = false;
  const timeline = [];
  const respawnIfDead = async () => {
    const health = owner.events.filter(e => e.event === 'health').at(-1);
    if (!health || health.health > 0) return false;
    const from = owner.events.length;
    owner.send('respawn');
    await owner.wait(e => e.event === 'position', from, 10_000);
    await owner.wait(e => e.event === 'health' && e.health > 0, from, 10_000).catch(() => {});
    where = 'overworld';
    return true;
  };
  const sample = async phase => {
    if (await respawnIfDead()) { laggingSince = null; return; }
    const n = await inspect('bodyguard');
    const fight = await combatOf('bodyguard');
    const me = position();
    samples++;
    const guardWorld = n.y > 90 ? 'nether' : 'overworld';
    const distance = guardWorld === where ? Math.hypot(n.x - me.x, n.z - me.z) : Infinity;
    const fighting = /target=(?!none)/.test(fight);
    if (distance > 8 && !fighting) laggingSince ??= Date.now();
    else laggingSince = null;
    const lag = laggingSince ? Date.now() - laggingSince : 0;
    if (lag > worst) { worst = lag; worstAt = `${phase}: guard ${n.x.toFixed(1)},${n.y},${n.z.toFixed(1)} me ${where} ${me.x.toFixed(1)},${me.y},${me.z.toFixed(1)} ${fight}`; }
    if (samples % 20 === 0) timeline.push(`${phase} d=${Number.isFinite(distance) ? distance.toFixed(1) : 'other-world'} ${fight.replace(/^Combat bodyguard: APPLIED /, '')}`);
  };
  const random = (low, high) => low + Math.random() * (high - low);
  const walkTo = async (x, z, phase) => {
    // Walk in short hops and sample between them, so a guard that stops is seen while it is stopped.
    for (let hop = 0; hop < 40; hop++) {
      const me = position(), dx = x - me.x, dz = z - me.z, length = Math.hypot(dx, dz);
      if (length < .5) return;
      const step = Math.min(4, length);
      try { await owner.walk(me.x + dx / length * step, me.z + dz / length * step); }
      catch (stuck) { note(`soak walk resync at ${me.x.toFixed(1)},${me.y},${me.z.toFixed(1)}: ${stuck.message}`); return; }
      await sample(phase);
    }
  };
  await check(`hired bodyguard keeps following for ${minutes} minutes`, async () => {
    const deadline = Date.now() + minutes * 60_000;
    const started = Date.now();
    const phases = new Map();
    while (Date.now() < deadline) {
      const pick = Math.floor(Math.random() * 9);
      const phase = ['wander', 'wander', 'road', 'road-stand', 'stand', 'teleport', 'die', 'nether', 'fight'][pick];
      phases.set(phase, (phases.get(phase) ?? 0) + 1);
      if (where !== 'overworld') {
        const from = owner.events.length;
        await server.cmd(`execute in minecraft:overworld run tp ${OWNER.name} 4 64 6`, /Teleported/);
        await owner.wait(e => e.event === 'position', from, 10_000).catch(() => {});
        where = 'overworld';
      }
      if (phase === 'wander') {
        if (position().y < 63.99) await owner.tp(position().x, 64, 4);
        for (let leg = 0; leg < 3; leg++) await walkTo(random(-34, 34), random(-34, 34), phase);
      } else if (phase === 'road' || phase === 'road-stand') {
        // Stand on the path itself, as a player walking the road does; tp() rounds to a tenth.
        const from = owner.events.length, roadX = Math.round(position().x) + .5;
        await server.cmd(`tp ${OWNER.name} ${roadX} 63.9375 0.5`, /Teleported/);
        await owner.wait(e => e.event === 'position' && Math.abs(e.y - 63.9375) < .001, from, 10_000);
        await walkTo(random(-36, 36), 0.5, phase);
        if (phase === 'road-stand') for (let i = 0; i < 6; i++) { await delay(1000); await sample(phase); }
      } else if (phase === 'stand') {
        for (let i = 0; i < 6; i++) { await delay(1000); await sample(phase); }
      } else if (phase === 'teleport') {
        const me = position();
        await owner.tp(Math.max(-36, Math.min(36, -me.x + random(-4, 4))), 64, Math.max(-36, Math.min(36, -me.z + random(-4, 4))) || 6);
        for (let i = 0; i < 4; i++) { await delay(1000); await sample(phase); }
      } else if (phase === 'die') {
        const from = owner.events.length;
        await server.cmd(`kill ${OWNER.name}`, /Killed/);
        await owner.wait(e => e.event === 'health' && e.health <= 0, from, 10_000).catch(() => {});
        await delay(1500);
        await respawnIfDead();
        for (let i = 0; i < 5; i++) { await delay(1000); await sample(phase); }
      } else if (phase === 'nether') {
        const from = owner.events.length;
        await server.cmd(`execute in minecraft:the_nether run tp ${OWNER.name} ${random(-8, 8).toFixed(1)} 100 ${random(-8, 8).toFixed(1)}`, /Teleported/);
        await owner.wait(e => e.event === 'respawn' && String(e.dimension).includes('the_nether'), from, 10_000);
        await owner.wait(e => e.event === 'position', from, 10_000).catch(() => {});
        where = 'nether';
        for (let i = 0; i < 5; i++) { await delay(1000); await sample(phase); }
        await walkTo(random(-8, 8), random(-8, 8), phase);
      } else {
        const me = position();
        const zombie = await summon(++zombies, 'minecraft:husk', (me.x + 3).toFixed(1), 64, me.z.toFixed(1), 'Silent:1b');
        await until(async () => { await sample(phase); return { ok: await entityCount(`@e[type=husk,nbt={UUID:[I;${zombies},0,0,1]}]`) === 0 }; }, 45_000, `the guard did not kill zombie ${zombie}`);
      }
      if (!renewed && Date.now() - started > minutes * 30_000) {
        renewed = true;
        if (where !== 'overworld') continue;
        await respawnIfDead();
        const n = await inspect('bodyguard');
        await owner.tp(n.x + 1.5, 64, n.z);
        await hire(/Renewed Bodyguard/);
      }
    }
    const stalls = logText().split('\n').filter(line => /guard made no progress/.test(line)).map(plain);
    note(`soak timeline: ${timeline.join(' | ')}`);
    note(`soak stalls: ${stalls.join(' | ') || 'none'}`);
    assert(worst <= 8000, `left more than 8 blocks behind for ${worst} ms at ${worstAt}`);
    return `${samples} samples over ${minutes} min; phases ${JSON.stringify(Object.fromEntries(phases))}; longest lag ${worst} ms; renewed=${renewed}; watchdog stalls=${stalls.length}${stalls.length ? ` first: ${stalls[0].slice(-220)}` : ''}`;
  });
  await gamemode('creative');
  await owner.close();
  await server.cmd('animus delete bodyguard', /Deleted|Unknown/);
  await server.cmd('kill @e[type=!player]', /Killed|No entity/);
  server.floor = null;
}

const SCENARIOS = { triggers, state, actions, mounts, riding, dragon, terrain, combat, packs, appearance, building, shops, folia, smoke, worlds, guardsoak };
function finalLogReview(text, label) {
  const lines = text.split('\n');
  const bad = lines.filter(line => /\b(ERROR|SEVERE)\b|Exception|Nag author|Could not pass event/.test(line) || (MOD && /[Mm]ixin[^\n]*(?:fail|error|could not|not applied)/i.test(line)));
  const warnings = lines.filter(line => /WARN/.test(line) && /Animus/.test(line));
  writeFileSync(path.join(results, `${label}-review.txt`), [...bad, '---', ...warnings].join('\n') + '\n');
  return { bad: bad.length, warnings: warnings.length, sample: bad.slice(0, 3).map(line => line.slice(0, 200)), threadsAfterDisable: server.lastThreads };
}

let exitCode = 0;
try {
  server.provision();
  await server.properties();
  const skin = args.get('skin-file');
  if (skin) { mkdirSync(path.join(data, 'skins'), { recursive: true }); copyFileSync(skin, path.join(data, 'skins', path.basename(skin))); }
  const harnessArgs = new Map([['version', version], ['port', String(server.port)], ['libraries', serverLibraries], ['results', results]]);
  await server.start();
  harness = prepareNative(harnessArgs, import.meta.url, 'live');
  for (const scenario of scenarios) {
    console.log(`SCENARIO ${scenario}`);
    await check(`scenario ${scenario} completed`, () => SCENARIOS[scenario]());
    for (const client of harness.clients) await client.close().catch(() => {});
    harness.clients.length = 0;
  }
  const final = await server.stop();
  console.log(`FINAL LOG ${JSON.stringify(finalLogReview(final, name))}`);
  console.log(`PORT CLOSED ${!(await portOpen(server.port))}`);
} catch (error) {
  console.log(`DRIVER FAILED ${error.stack?.slice(0, 800)}`);
  exitCode = 2;
  try { mux('runtime', 'stop', name, '--graceful'); } catch (stop) { note(`stop: ${stop.message}`); }
}
console.log(`RESULT passed=${passedCount} failed=${failedCount}${failures.length ? ` | ${failures.join('; ')}` : ''}`);
process.exit(failedCount || exitCode ? 1 : 0);
