// Shared process control for QA scenarios. Wire codecs remain in the exact-version
// NativeProtocolClient; this module does not implement Minecraft packet formats.
import { spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, readFileSync, createWriteStream } from 'node:fs';
import { homedir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createInterface } from 'node:readline';
import assert from 'node:assert/strict';

export const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

export function parseArgs(argv = process.argv.slice(2)) {
  const args = new Map();
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith('--')) throw new Error(`Expected named option: ${arg}`);
    args.set(arg.slice(2), argv[i + 1]?.startsWith('--') || i + 1 === argv.length ? true : argv[++i]);
  }
  return args;
}

export function prepareNative(args, scenarioUrl, prefix = 'packet') {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
  const version = args.get('version') ?? '26.3';
  const port = Number(args.get('port') ?? 65490);
  assert(['26.1.2', '26.2', '26.3'].includes(version), 'Validate another exact version before adding its QA adapter');
  assert(Number.isInteger(port) && port > 0 && port < 65536, 'Invalid loopback port');
  const resultDir = path.join(args.get('results') ? path.resolve(args.get('results')) : path.join(root, '.qa', 'protocol-cache', 'results'), `${new Date().toISOString().replaceAll(':', '-')}-${prefix}-${process.pid}-${version}`);
  // Per-run classes prevent concurrent platform scenarios from racing javac.
  const classes = path.join(resultDir, 'classes');
  mkdirSync(classes, { recursive: true });
  const jar = path.resolve(args.get('jar') ?? path.join(homedir(), '.gradle', 'caches', 'fabric-loom', version, 'minecraft-extracted_server.jar'));
  const libraries = path.resolve(args.get('libraries') ?? path.join(root, '.qa', `leaf-${version}`, 'libraries'));
  assert(existsSync(jar), `Exact ${version} vanilla server jar required: ${jar}`);
  assert(existsSync(libraries), `Server dependency directory required: ${libraries}`);
  const javaHome = args.get('java-home') ?? (existsSync('/opt/homebrew/opt/openjdk@25/bin/java') ? '/opt/homebrew/opt/openjdk@25' : process.env.JAVA_HOME);
  const java = javaHome ? path.join(javaHome, 'bin', 'java') : 'java';
  const javac = javaHome ? path.join(javaHome, 'bin', 'javac') : 'javac';
  function jars(directory) {
    return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
      const name = path.join(directory, entry.name);
      return entry.isDirectory() ? jars(name) : entry.name.endsWith('.jar') ? [name] : [];
    });
  }
  const classpath = [classes, jar, ...jars(libraries).sort()].join(path.delimiter);
  const revision = version === '26.3' ? 'v26_3' : 'v26_1_26_2';
  const nativeSource = path.join(root, 'qa', 'protocol-client', 'NativeProtocolClient.java');
  const revisionSource = path.join(root, 'qa', 'protocol-client', revision, 'ProtocolRevision.java');
  const compile = spawnSync(javac, ['-cp', classpath, '-d', classes, nativeSource, revisionSource], { encoding: 'utf8' });
  if (compile.status !== 0) throw new Error(`Exact-version helper compilation failed; no schema fallback is permitted.\n${compile.stdout}\n${compile.stderr}`);
  const hashFile = file => createHash('sha256').update(readFileSync(file)).digest('hex');
  const nativeCodec = { adapter: revision, jarSha256: hashFile(jar), clientSourceSha256: hashFile(nativeSource), adapterSourceSha256: hashFile(revisionSource), scenarioSourceSha256: hashFile(fileURLToPath(scenarioUrl)), harnessSourceSha256: hashFile(fileURLToPath(import.meta.url)) };
  return { root, version, port, resultDir, java, javac, classes, classpath, nativeCodec, clients: [], wire: createWriteStream(path.join(resultDir, 'wire.ndjson')) };
}

export class NativeClient {
  constructor(harness, username) {
    this.harness = harness;
    const { resultDir, java, classpath, version, port, wire, clients } = harness;
    this.username = username;
    this.events = [];
    this.waiters = new Set();
    this.lastCommand = 0;
    this.closing = false;
    this.stderr = createWriteStream(path.join(resultDir, `${username}-${clients.length}.stderr.log`));
    this.process = spawn(java, ['-cp', classpath, 'NativeProtocolClient', String(port), username, version], { cwd: resultDir, stdio: ['pipe', 'pipe', 'pipe'] });
    this.process.stderr.pipe(this.stderr);
    createInterface({ input: this.process.stdout }).on('line', line => {
      let event;
      try { event = JSON.parse(line); } catch { this.fatal = new Error(`Unexpected native client output: ${line.slice(0, 200)}`); this.wake(); return; }
      event.sequence = this.events.length;
      this.events.push(event);
      if (event.event === 'position') this.position = event;
      if (event.event === 'health') this.health = event.health;
      wire.write(JSON.stringify(event) + '\n');
      if (event.event === 'error') this.fatal = new Error(`${username}: ${event.message}`);
      this.wake();
    });
    this.process.on('error', error => { this.fatal = error; this.wake(); });
    this.process.on('exit', (code, signal) => {
      this.exited = true;
      if (!this.closing) this.fatal ??= new Error(`${username} exited unexpectedly (${code}, ${signal})`);
      this.wake();
    });
    clients.push(this);
  }
  wake() { for (const waiter of [...this.waiters]) waiter(); }
  send(op, data = {}) {
    if (this.fatal) throw this.fatal;
    this.process.stdin.write(JSON.stringify({ op, ...data }) + '\n');
  }
  wait(test, from = 0, timeout = 15_000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.waiters.delete(check); reject(new Error(`${this.username}: timed out waiting for ${test}; recent chat: ${this.events.filter(e => e.event === 'chat').slice(-4).map(e => e.text).join(' | ')}`)); }, timeout);
      const check = () => {
        const found = this.events.slice(from).find(test);
        if (found || this.fatal) { clearTimeout(timer); this.waiters.delete(check); found ? resolve(found) : reject(this.fatal); }
      };
      this.waiters.add(check); check();
    });
  }
  async ready() {
    const toolchain = await this.wait(e => e.event === 'toolchain');
    assert.equal(toolchain.version, this.harness.version);
    const registries = await this.wait(e => e.event === 'registries');
    assert(registries.count > 0 && registries.entries > 0, 'Server registry contents must precede game packet decoding');
    if (this.harness.version === '26.3') assert(registries.keys.includes('minecraft:cow_sound_variant'));
    await this.wait(e => e.event === 'position');
    const health = await this.wait(e => e.event === 'health');
    if (health.health <= 0) {
      // Preserve the health receipt and use vanilla's explicit respawn action;
      // a dead saved QA player has no world/chunk-loader membership.
      const from = this.events.length;
      this.send('respawn');
      await this.wait(e => e.event === 'respawn', from);
      await this.wait(e => e.event === 'position', from);
      await this.wait(e => e.event === 'health' && e.health > 0, from);
    }
    return toolchain;
  }
  async command(text, match, timeout = 15_000) {
    await delay(Math.max(0, 650 - (Date.now() - this.lastCommand)));
    const start = this.events.length;
    this.lastCommand = Date.now();
    this.send('command', { text });
    return match ? this.wait(e => e.event === 'chat' && (typeof match === 'string' ? e.text.includes(match) : match.test(e.text)), start, timeout) : start;
  }
  async close() {
    if (this.closing) return;
    this.closing = true;
    if (!this.exited) {
      this.process.stdin.write('{"op":"close"}\n');
      this.process.stdin.end();
      for (let i = 0; i < 30 && !this.exited; i++) await delay(100);
      if (!this.exited) this.process.kill('SIGKILL');
    }
  }
}
