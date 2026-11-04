#!/usr/bin/env node
// Local exact-codec audit only; does not connect to or launch a server. Build the plugin first.
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { prepareNative, parseArgs } from './client.mjs';
const harness = prepareNative(parseArgs(), import.meta.url, 'presentation-audit');
const pluginClasses = path.join(harness.root, 'bukkit', 'build', 'classes', 'java', 'main');
const coreLibs = path.join(harness.root, 'core', 'build', 'libs');
assert(existsSync(pluginClasses) && existsSync(coreLibs), 'Build the plugin before this audit');
const coreJar = readdirSync(coreLibs).filter(name => name.endsWith('.jar')).map(name => path.join(coreLibs, name));
const classpath = [harness.classpath, pluginClasses, ...coreJar].join(path.delimiter);
const source = path.join(harness.root, 'qa/protocol-client/PresentationProtocolAudit.java');
const compile = spawnSync(harness.javac, ['-cp', classpath, '-d', harness.classes, source], { encoding: 'utf8' });
assert.equal(compile.status, 0, compile.stdout + compile.stderr);
const result = spawnSync(harness.java, ['-cp', [harness.classes, classpath].join(path.delimiter), 'art.arcane.animus.bukkit.packet.PresentationProtocolAudit', harness.version], { encoding: 'utf8' });
writeFileSync(path.join(harness.resultDir, 'audit.stderr.log'), result.stderr);
assert.equal(result.status, 0, result.stdout + result.stderr);
const parsed = JSON.parse(result.stdout.trim().split('\n').at(-1));
assert.equal(parsed.version, harness.version);
const report = { status: 'passed', scope: 'local exact native codec audit of poses, armor stand parts, text displays, seats and hidden name teams; no rendering claim', ...parsed, nativeCodec: harness.nativeCodec, auditSourceSha256: createHash('sha256').update(readFileSync(source)).digest('hex'), evidence: harness.resultDir };
writeFileSync(path.join(harness.resultDir, 'result.json'), JSON.stringify(report, null, 2) + '\n');
harness.wire.end();
console.log(JSON.stringify(report));
