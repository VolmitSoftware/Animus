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
const workspace = path.resolve(args.get('workspace') ?? path.join(root, '.qa', 'multiplexor-workspace'));
const binary = path.resolve(args.get('binary'));
const buildHome = args.get('build-home') ? path.resolve(args.get('build-home')) : null;
const plugin = path.resolve(args.get('plugin'));
const javaHome = args.get('java-home') ?? '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home';
const scenarios = (args.get('scenarios') ?? (project === 'folia' ? 'folia' : 'triggers,state,actions,mounts,riding,dragon')).split(',');
const name = `animus-live-${project}-${version.replaceAll('.', '-')}`;
const instances = path.join(workspace, 'consumers/plugin-consumers/instances');
const instance = path.join(instances, name), data = path.join(instance, 'plugins/Animus'), logFile = path.join(instance, 'logs/latest.log');
const session = `mc-plugin-${name}`;
const OWNER = { uuid: 'b2b7d4ea-c517-3a19-8a16-ee03e26cbe9f', name: 'AnimusProofQA' };
const PEER = { uuid: '262b16de-d06b-325b-b115-4ad978f962f2', name: 'AnimusProofPeer' };
const FORBIDDEN = /moved too quickly|moved wrongly|Flying is not enabled|was kicked/i;
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
function mux(...command) {
  const done = spawnSync(binary, ['--root', workspace, '--consumer', 'plugin', ...command], { encoding: 'utf8', env: muxEnvironment() });
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
  provision() {
    const template = path.join(instances, `animus-${project}-${version.replaceAll('.', '-')}`);
    if (!existsSync(instance)) {
      mux('server', 'create', name, '--jar', realpathSync(path.join(template, 'server.jar')), '--type', project, '--mc', version, '--isolated');
      mux('runtime', 'settings', 'set-java', path.join(javaHome, 'bin/java'), '--instance', name);
      mux('runtime', 'settings', 'set-heap', '1G', '--instance', name);
      mux('runtime', 'settings', 'set-preset', 'vanilla', '--instance', name);
      mux('gameplay', 'prepare', name);
    }
    assert.equal(mux('instance', 'isolated', name).trim(), 'true', 'Refusing a shared instance');
    copyFileSync(path.join(template, 'eula.txt'), path.join(instance, 'eula.txt'));
    const plugins = path.join(instance, 'plugins');
    // Every run starts from empty plugin data so no earlier revision, checkpoint or state answers for this run.
    assert(data.startsWith(instances) && path.basename(instance).startsWith('animus-live-'));
    rmSync(data, { recursive: true, force: true });
    mkdirSync(data, { recursive: true });
    for (const entry of readdirSync(plugins)) if (/^Animus.*\.jar$/.test(entry)) unlinkSync(path.join(plugins, entry));
    copyFileSync(plugin, path.join(plugins, path.basename(plugin)));
    const cached = path.join(process.env.HOME, '.gradle/caches/fabric-loom', version, 'minecraft-server.jar');
    mkdirSync(path.join(instance, 'cache'), { recursive: true });
    if (existsSync(cached)) copyFileSync(cached, path.join(instance, 'cache', `mojang_${version}.jar`));
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
        if (logText().slice(from).includes('Disabling Animus')) {
          clearInterval(poll);
          setTimeout(() => {
            const dump = spawnSync(path.join(javaHome, 'bin/jcmd'), [pid, 'Thread.print'], { encoding: 'utf8' }).stdout ?? '';
            const names = dump.split('\n').filter(line => line.startsWith('"')).map(line => line.split('"')[1]);
            resolve({ jvmThreads: names.length, animus: names.filter(threadName => /animus/i.test(threadName)) });
          }, 400);
        }
      }, 25);
      setTimeout(() => { clearInterval(poll); resolve(null); }, 90_000);
    });
    const child = spawn(binary, ['--root', workspace, '--consumer', 'plugin', 'runtime', 'stop', name, '--graceful'], { env: muxEnvironment(), stdio: 'ignore' });
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
  await check('trigger spawn', async () => (await owner.chat('Trigger: spawn-fired', start)).text);
  await check('trigger timer', async () => { await owner.chat('Trigger: timer-fired', start, 10_000); const from = owner.events.length; return (await owner.chat('Trigger: timer-fired', from, 10_000)).text; });
  await check('trigger proximity enter', async () => { const from = owner.events.length; await owner.walk(0, 6); return (await owner.chat('Trigger: near-fired', from)).text; });
  await check('trigger proximity leave', async () => { const from = owner.events.length; await owner.walk(0, 2); return (await owner.chat('Trigger: away-fired', from)).text; });
  await owner.walk(0, 7);
  await check('trigger interaction', async () => {
    await owner.look(body.x, body.y + 0.9, body.z);
    const from = owner.events.length; owner.send('use', { id: body.id });
    const fired = await owner.chat('Trigger: click-fired', from);
    await owner.noChat('Trigger: hit-fired', from, 400);
    return fired.text;
  });
  await check('trigger attack action', async () => {
    const from = owner.events.length; owner.send('attack', { id: body.id });
    return (await owner.chat('Trigger: hit-fired', from)).text;
  });
  await check('trigger waypoint arrival', async () => {
    const from = owner.events.length;
    await server.cmd('animus navigate trig 4 61 10 walk 3', /./);
    return (await owner.chat('Trigger: waypoint-fired', from, 15_000)).text;
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

async function runDoc(owner, id, routine, extra = {}) {
  server.behavior(`live:${id}`, { routines: { main: routine }, ...extra });
  await server.reload();
  const execution = await owner.run('act', `live:${id}`);
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
    await owner.chat('Actor: hello', ownerFrom);
    await peer.noChat('Actor: hello', peerFrom);
    const nearbyFrom = peer.events.length;
    await runDoc(owner, 'say', action('animus:say', { text: 'all', audience: 'nearby', radius: 16 }));
    await peer.chat('Actor: all', nearbyFrom);
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
    await owner.chat('Actor: arrived', from);
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
    await owner.chat('Actor: quest-three', from); await owner.chat('Actor: flag-set', from);
    const shared = await runDoc(owner, 'other/quest', { type: 'condition', capability: 'animus:quest-state', arguments: { name: 'main', atLeast: 3 }, then: sayTo('shared-in-namespace'), else: sayTo('not-shared') });
    assert.equal(shared.state, 'COMPLETED');
    await owner.chat('Actor: shared-in-namespace', from);
    server.behavior('harbor:quest', { routines: { main: { type: 'condition', capability: 'animus:quest-state', arguments: { name: 'main', atLeast: 1 }, then: sayTo('leaked-namespace'), else: sayTo('own-namespace') } } });
    await server.reload();
    await owner.run('act', 'harbor:quest');
    await owner.chat('Actor: own-namespace', from);
    await delay(5500);
    const file = path.join(data, 'state/behavior-state/player', OWNER.uuid, 'animus/quests/live.json');
    assert(existsSync(file), `${file} missing`);
    return readFileSync(file, 'utf8').replace(/\s+/g, ' ').slice(0, 120);
  });
  await check('combat-control', async () => {
    await server.cmd('difficulty easy', /difficulty/i);
    const zombie = '00000001-0000-0002-0000-000300000004';
    await server.cmd('summon minecraft:zombie 3 61 8 {UUID:[I;1,2,3,4],PersistenceRequired:1b}', /Summoned/);
    const enabled = await runDoc(owner, 'combat', action('animus:combat-control', { operation: 'enable' }));
    assert.equal(enabled.state, 'COMPLETED', diagnosticOf(enabled.text));
    const status = await server.capture('animus combat status', /Combat \w+[^\n]*/);
    assert(/registered=1/.test(status), status.slice(0, 200));
    const targeted = await runDoc(owner, 'combat', action('animus:combat-control', { operation: 'target', target: zombie }));
    assert.equal(targeted.state, 'COMPLETED', diagnosticOf(targeted.text));
    const leased = await server.capture('animus combat status', /Combat \w+[^\n]*/);
    assert(/controlled-mobs=1/.test(leased), leased.slice(0, 200));
    const released = await runDoc(owner, 'combat', action('animus:combat-control', { operation: 'release' }));
    assert.equal(released.state, 'COMPLETED', diagnosticOf(released.text));
    const free = await server.capture('animus combat status', /Combat \w+[^\n]*/);
    assert(/controlled-mobs=0/.test(free), free.slice(0, 200));
    const off = await runDoc(owner, 'combat', action('animus:combat-control', { operation: 'disable' }));
    assert.equal(off.state, 'COMPLETED', diagnosticOf(off.text));
    await server.cmd(`kill ${zombie}`, /Killed/);
    await server.cmd('difficulty peaceful', /difficulty/i);
    return leased.match(/Combat[^\n]*/)[0].slice(0, 120);
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
const dismounted = (client, vehicleId, from = 0) => client.wait(e => e.event === 'passengers' && e.vehicle === vehicleId && !e.ids.includes(e.own), from, 8000);
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
    assert.equal(await strike(body.id + 9), false);
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
  server.behavior('live:folia', { routines: { main: seq(action('animus:set-flag', { name: 'folia', scope: 'global' }), { type: 'condition', capability: 'animus:flag-state', arguments: { name: 'folia', scope: 'global' }, then: action('animus:advance-quest-state', { name: 'folia', scope: 'global' }) }) }, triggers: [{ id: 't', event: 'timer', intervalTicks: 40 }] });
  server.behavior('live:foliamixed', { routines: { main: action('animus:set-flag', { name: 'mixed', scope: 'global' }), speak: sayTo('no') } });
  server.behavior('live:foliaworld', { routines: { main: action('animus:say', { text: 'hello', audience: 'nearby' }) } });
  server.behavior('live:folianav', { routines: { main: action('animus:navigate', { x: 1, y: 64, z: 1, wait: true }) } });
  await server.reload();
  await server.cmd('animus create fn minecraft:player world 0 64 0 live Folia', /Created dormant fn/);
  await server.cmd('animus edit fn {"behaviors":["live:folia"],"modules":{"animus:riding":{"enabled":true},"animus:mount":{"vehicle":"nobody"}}}', /Saved/);
  await check('status reports NPC runtime unavailable', async () => (await server.capture('animus status', /NPC runtime unavailable on Folia[^\n]*/)).match(/NPC runtime unavailable[^\n]*/)[0].slice(0, 200));
  await check('triggers report unavailable with a reason', async () => { const text = await server.capture('animus behaviors triggers', /Behavior triggers: UNAVAILABLE[^\n]*/); return text.match(/Behavior triggers:[^\n]*/)[0].slice(0, 220); });
  await check('manual state-only run still works', async () => {
    const result = await server.finish(await server.runConsole('fn', 'live:folia'));
    assert.equal(result.state, 'COMPLETED', result.text.slice(0, 300));
    return result.state;
  });
  await check('behavior mixing state and world actions is unavailable as a whole', async () => {
    const run = await server.capture('animus behaviors run fn live:foliamixed', /Behavior run: \w+[^\n]*/, 400);
    return run.match(/Behavior run:[^\n]*/)[0] + ' ' + (run.match(/Behavior diagnostic:[^\n]*/)?.[0] ?? '').slice(0, 160);
  });
  await check('world action reports unavailable', async () => {
    const text = await server.capture('animus behaviors validate', /Behaviors validate: \w+[^\n]*/);
    note(text);
    assert(/behavior-capability-unavailable/.test(text), text.slice(0, 400));
    const run = await server.capture('animus behaviors run fn live:foliaworld', /Behavior run: \w+[^\n]*/, 400);
    note(run);
    const execution = run.match(/execution=([0-9a-f-]{36})/)?.[1];
    if (execution) { const result = await server.finish(execution); assert.notEqual(result.state, 'COMPLETED', result.text.slice(0, 300)); return `${result.state} ${diagnosticOf(result.text).slice(0, 200)}`; }
    assert(/UNAVAILABLE|unavailable/.test(run), run.slice(0, 300));
    return run.match(/Behavior run:[^\n]*/)[0].slice(0, 200);
  });
  await check('navigation action reports unavailable', async () => {
    const run = await server.capture('animus behaviors run fn live:folianav', /Behavior run: \w+[^\n]*/, 400);
    const execution = run.match(/execution=([0-9a-f-]{36})/)?.[1];
    if (execution) { const result = await server.finish(execution); assert.notEqual(result.state, 'COMPLETED'); return `${result.state} ${diagnosticOf(result.text).slice(0, 200)}`; }
    return run.match(/Behavior run:[^\n]*/)[0].slice(0, 200);
  });
  await check('ride and mount report unavailable', async () => {
    const ride = await server.capture('animus ride status', /./, 300);
    assert(/unavailable/i.test(ride), ride.slice(0, 300));
    const spawn = await server.capture('animus spawn fn', /Cannot activate[^\n]*/, 200);
    return `${ride.split('\n').find(line => /unavailable/i.test(line)).replace(/^.*?\]: /, '').slice(0, 160)} ; ${spawn.match(/Cannot activate[^\n]*/)[0].slice(0, 120)}`;
  });
  await check('nothing fired while waiting', async () => {
    await delay(3000);
    const text = await server.capture('animus behaviors trace fn', /Behavior trace:[^\n]*/);
    assert(!text.includes('trigger=t'), text.slice(0, 300));
  });
  await check('stored flag present', async () => { assert(await flagStored('global', 'live', 'folia')); });
  await server.cmd('animus delete fn', /Deleted/);
}

const SCENARIOS = { triggers, state, actions, mounts, riding, dragon, folia };
function finalLogReview(text, label) {
  const lines = text.split('\n');
  const bad = lines.filter(line => /\b(ERROR|SEVERE)\b|Exception|Nag author|Could not pass event/.test(line));
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
  const libraries = path.join(instance, project === 'spigot' ? 'bundler/libraries' : 'libraries');
  const harnessArgs = new Map([['version', version], ['port', String(server.port)], ['libraries', libraries], ['results', results]]);
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
