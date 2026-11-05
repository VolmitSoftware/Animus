#!/usr/bin/env python3
"""Provision exact Spigot releases through Multiplexor's official BuildTools provider.

Builds run in the shared Multiplexor workspace with its default build cache; each release
keeps only its evidence and Maven repository under ignored .qa output. This script does not start servers. The generated source-evidence.json feeds managed-runtime.py.
"""
import argparse
import concurrent.futures
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.request
import zipfile

import multiplexor_workspace as mw


VERSIONS = ('26.1.2', '26.2', '26.3')
JENKINS = 'https://hub.spigotmc.org/jenkins/job/BuildTools/'


def digest(path):
    hasher = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            hasher.update(block)
    return hasher.hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.json.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


def fetch_json(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def inspect_jar(path, expected):
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        manifest = archive.read('META-INF/MANIFEST.MF').decode('utf8')
        if 'Main-Class:' not in manifest:
            raise AssertionError('BuildTools output has no executable main class')
        evidence = {'mainClass': re.search(r'(?m)^Main-Class: (.+)', manifest).group(1).strip()}
        if 'version.json' in names:
            game = json.loads(archive.read('version.json'))
            assert game.get('id') == expected or game.get('name') == expected, 'Embedded Minecraft version differs'
            evidence['minecraft'] = game
        pom = 'META-INF/maven/org.spigotmc/spigot/pom.properties'
        if pom in names:
            properties = dict(line.split('=', 1) for line in archive.read(pom).decode().splitlines() if '=' in line and not line.startswith('#'))
            assert properties.get('version', '').startswith(expected + '-'), 'Spigot Maven version differs'
            evidence['spigotMaven'] = properties
        evidence['entryCount'] = len(names)
        if 'META-INF/versions.list' in names:
            versions = archive.read('META-INF/versions.list').decode().strip().splitlines()
            assert len(versions) == 1, 'Expected one bootstrapped Spigot server'
            expected_hash, filename = versions[0].split(' *', 1)
            assert filename.startswith('spigot-' + expected + '-'), 'Bootstrap embeds another Spigot release'
            embedded = archive.read('META-INF/versions/' + filename)
            assert hashlib.sha256(embedded).hexdigest() == expected_hash, 'Embedded server hash mismatch'
            with zipfile.ZipFile(io.BytesIO(embedded)) as inner:
                game = json.loads(inner.read('version.json'))
                assert game.get('id') == expected or game.get('name') == expected, 'Embedded game version differs'
                evidence['embeddedServer'] = {'file': filename, 'sha256': expected_hash, 'minecraft': game}
        # Startup and native-client assertions independently verify game version.
        return evidence


def provision(args, version, tools):
    version_root = args.output / version
    workspace = args.workspace
    version_root.mkdir(parents=True, exist_ok=True)
    attempt = version_root / 'attempts' / str(time.time_ns())
    attempt.mkdir(parents=True)
    report = {'project': 'spigot', 'minecraftVersion': version, 'status': 'running',
              'channel': 'OFFICIAL_BUILDTOOLS', 'runner': 'ServerMultiplexor managed BuildTools provider',
              'workspace': str(workspace), 'evidence': str(attempt / 'evidence.json'),
              'buildTools': tools, 'commands': [], 'startedAtEpochSeconds': time.time()}
    write_json(attempt / 'evidence.json', report)
    environment = os.environ.copy()
    environment['JAVA_HOME'] = str(args.java_home)
    environment['PATH'] = str(args.java_home / 'bin') + os.pathsep + environment.get('PATH', '')
    environment['JDK_JAVA_OPTIONS'] = '-Xmx4G -XX:ActiveProcessorCount=4'
    environment['MAVEN_OPTS'] = '-Dmaven.repo.local=' + str(version_root / 'maven-cache')
    environment['SPIGOT_BUILDTOOLS_URL'] = tools['artifactUrl']
    environment['GIT_TERMINAL_PROMPT'] = '0'

    def command(*tokens, timeout=120):
        number = len(report['commands']) + 1
        log = attempt / ('command-%02d.log' % number)
        invocation = [str(args.binary), '--root', str(workspace), '--consumer', 'plugin', *tokens]
        entry = {'arguments': list(tokens), 'log': str(log), 'status': 'running'}
        report['commands'].append(entry)
        write_json(attempt / 'evidence.json', report)
        with log.open('w') as stream:
            completed = subprocess.run(invocation, env=environment, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
        entry.update(status='passed' if completed.returncode == 0 else 'failed', exitCode=completed.returncode)
        write_json(attempt / 'evidence.json', report)
        if completed.returncode:
            raise RuntimeError('Multiplexor %s exited %d; see %s' % (' '.join(tokens), completed.returncode, log))
        return log.read_text(errors='replace')

    print(json.dumps({'version': version, 'status': 'starting', 'evidence': report['evidence']}), flush=True)
    try:
        manifest_url = 'https://hub.spigotmc.org/versions/' + version + '.json'
        upstream = fetch_json(manifest_url)
        assert re.fullmatch(r'\d+', upstream['name']), 'Official manifest lacks a build number'
        assert 69 in upstream['javaVersions'], 'Official manifest does not permit Java 25'
        report.update(build=int(upstream['name']), upstreamManifestUrl=manifest_url, upstreamManifest=upstream)
        write_json(attempt / 'upstream-before.json', upstream)
        build_output = command('build', 'spigot', '--mc', version, timeout=args.timeout)
        report['providerCacheHit'] = 'already the newest' in build_output
        cached = command('build', 'cache-info', 'spigot', '--mc', version)
        candidates = []
        for line in cached.splitlines():
            fields = line.split('\t')
            if len(fields) >= 2 and fields[0] == 'spigot':
                jar = Path(fields[1])
                if not jar.is_absolute():
                    jar = workspace / 'consumers/plugin-consumers/builds/spigot' / jar
                jar = jar.resolve()
                assert workspace in jar.parents, 'Provider returned an artifact outside the shared workspace'
                if jar.is_file(): candidates.append(jar)
        assert len(candidates) == 1, 'Expected exactly one exact-version cached Spigot artifact'
        built = candidates[0]
        server = attempt / 'server.jar'
        shutil.copy2(built, server)
        after = fetch_json(manifest_url)
        write_json(attempt / 'upstream-after.json', after)
        assert upstream == after, 'Upstream changed during build; inspect retained logs before accepting provenance'
        report.update(status='built', serverJar=str(server), cachedJar=str(built), serverSha256=digest(server),
                      jarMetadata=inspect_jar(server, version), upstreamUnchanged=True)
        report['upstreamSpigotHashMatches'] = report['serverSha256'] == upstream.get('hashes', {}).get('Spigot')
        assert report['upstreamSpigotHashMatches'], 'Built executable differs from the official Spigot manifest hash'
        tool_jars = list((workspace / 'consumers/plugin-consumers/builds').rglob('BuildTools.jar'))
        report['buildToolsCopies'] = [{'path': str(path), 'sha256': digest(path)} for path in tool_jars]
        report['buildLogs'] = [str(path) for path in (workspace / 'consumers/plugin-consumers/state/build-logs').rglob('spigot-*.log')]
        assert report['buildToolsCopies'], 'Managed BuildTools binary was not retained for provenance'
        assert report['buildLogs'], 'Managed BuildTools log was not retained'
    except Exception as error:
        report.update(status='failed', error=repr(error))
    finally:
        report['finishedAtEpochSeconds'] = time.time()
        write_json(attempt / 'evidence.json', report)
        write_json(version_root / 'latest.json', report)
    print(json.dumps({'version': version, 'status': report['status'], 'evidence': report['evidence'], 'error': report.get('error')}), flush=True)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, default=mw.default_binary())
    parser.add_argument('--workspace', type=Path, default=mw.default_workspace())
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('.qa/spigot-builds'))
    parser.add_argument('--versions', nargs='+', choices=VERSIONS, default=VERSIONS)
    parser.add_argument('--jobs', type=int, choices=[1, 2, 3], default=1, help='The shared workspace serializes project operations')
    parser.add_argument('--timeout', type=int, default=3600)
    args = parser.parse_args()
    args.binary = args.binary.resolve(); args.workspace = args.workspace.resolve(); args.java_home = args.java_home.resolve(); args.output = args.output.resolve()
    assert '.qa' in args.output.parts, 'Use dedicated ignored QA output'
    assert args.binary.is_file() and (args.java_home / 'bin/java').is_file()
    java = subprocess.run([str(args.java_home / 'bin/java'), '-version'], capture_output=True, text=True, check=True)
    assert re.search(r'version "25(?:[.\"+-])', java.stderr + java.stdout), 'Use Java 25'
    tools = fetch_json(JENKINS + 'lastSuccessfulBuild/api/json?tree=number,url,timestamp,artifacts%5BrelativePath%5D')
    assert tools['url'] == JENKINS + str(tools['number']) + '/'
    assert any(item['relativePath'] == 'target/BuildTools.jar' for item in tools['artifacts'])
    tools['artifactUrl'] = tools['url'] + 'artifact/target/BuildTools.jar'
    write_json(args.output / 'buildtools-release.json', tools)
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as executor:
        results = list(executor.map(lambda version: provision(args, version, tools), args.versions))
    write_json(args.output / 'source-evidence.json', {'java': java.stderr + java.stdout, 'results': results})
    return 0 if all(result['status'] == 'built' for result in results) else 1


if __name__ == '__main__':
    raise SystemExit(main())
