#!/usr/bin/env node
// Local exact-codec audit only; does not connect to or launch a server.
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { prepareNative, parseArgs } from './client.mjs';
const harness = prepareNative(parseArgs(), import.meta.url, 'input-audit');
const source = path.join(harness.root, 'qa/protocol-client/InputProtocolAudit.java');
const compile = spawnSync(harness.javac, ['-cp', harness.classpath, '-d', harness.classes, source], { encoding: 'utf8' });
assert.equal(compile.status, 0, compile.stdout + compile.stderr);
const result = spawnSync(harness.java, ['-cp', harness.classpath, 'InputProtocolAudit', harness.version], { encoding: 'utf8' });
writeFileSync(path.join(harness.resultDir, 'audit.stderr.log'), result.stderr);
assert.equal(result.status, 0, result.stdout + result.stderr);
const parsed = JSON.parse(result.stdout);
assert.equal(parsed.version, harness.version);
assert.equal(parsed.nativeInputs, 128);
const report = { status: 'passed', scope: 'local exact native constructor/codec audit; no live input or movement claim', ...parsed, nativeCodec: harness.nativeCodec, auditSourceSha256: createHash('sha256').update(readFileSync(source)).digest('hex'), evidence: harness.resultDir };
writeFileSync(path.join(harness.resultDir, 'result.json'), JSON.stringify(report, null, 2) + '\n');
harness.wire.end();
console.log(JSON.stringify(report));
