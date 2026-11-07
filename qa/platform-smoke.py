#!/usr/bin/env python3
"""Download exact PaperMC builds and smoke-test Animus in owned isolated QA directories.
Checks startup/native binding/console lifecycle only. No NPC gameplay claim is made.
The EULA must already be accepted in an existing file supplied with --accepted-eula.
Stable releases are the default; prerelease channels require an explicit --channel selection.
"""
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
import argparse
import hashlib
import json
import os
import queue
import re
import shutil
import socket
import subprocess
import threading
import time
import urllib.request

USER_AGENT = 'Animus/0.1.0 (https://github.com/VolmitSoftware/Animus)'


def fetch(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': USER_AGENT}), timeout=60)


def sha256(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


class Server:
    def __init__(self, root, java, log):
        self.root, self.log = root, log
        self.lines = queue.Queue()
        self.process = subprocess.Popen([java, '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xms256M', '-Xmx1G', '-jar', 'server.jar', 'nogui'], cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        def read():
            for line in self.process.stdout:
                log.write(line)
                log.flush()
                self.lines.put(line)
        self.reader = threading.Thread(target=read, daemon=True)
        self.reader.start()

    def wait(self, pattern, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                line = self.lines.get(timeout=0.2)
            except queue.Empty:
                if self.process.poll() is not None:
                    raise AssertionError(f'Server exited {self.process.returncode} before {pattern}')
                continue
            if re.search(pattern, line):
                return line.strip()
        raise AssertionError(f'Timeout waiting for {pattern}; inspect {self.root}/platform-smoke.log')

    def command(self, command, pattern):
        self.process.stdin.write(command + '\n')
        self.process.stdin.flush()
        return self.wait(pattern)

    def close(self):
        if self.process.poll() is None:
            self.process.stdin.write('stop\n')
            self.process.stdin.flush()
            try:
                self.process.wait(timeout=40)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
                raise AssertionError('Server did not stop cleanly')
        self.reader.join(timeout=5)
        if self.process.returncode != 0:
            raise AssertionError(f'Server exit code {self.process.returncode}')


def run_target(args, version):
    endpoint = f'https://fill.papermc.io/v3/projects/{args.project}/versions/{version}/builds'
    with fetch(endpoint) as response:
        builds = json.load(response)
    candidates = [build for build in builds if build.get('channel') == args.channel and 'server:default' in build.get('downloads', {})
                  and (args.build is None or build['id'] == args.build)]
    if not candidates:
        raise RuntimeError(f'No official {args.channel} {args.project} build {args.build or "latest"} for exact Minecraft {version}; no version or channel fallback performed')
    build = max(candidates, key=lambda item: item['id'])
    download = build['downloads']['server:default']
    root = args.root.resolve() / f'{args.project}-{version}-{build["id"]}'
    root.mkdir(parents=True, exist_ok=True)
    lock = root / 'platform-smoke.lock'
    descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.close(descriptor)
    report = {'project': args.project, 'minecraftVersion': version, 'build': build['id'], 'channel': build['channel'], 'source': endpoint, 'download': download, 'pluginSha256': sha256(args.plugin), 'startedAt': datetime.now(timezone.utc).isoformat(), 'checks': [], 'status': 'running', 'scope': 'startup/native binding/console lifecycle only; no gameplay certification'}
    server = None
    log = None
    try:
        jar = root / 'server.jar'
        expected = download['checksums']['sha256']
        if not jar.exists() or sha256(jar) != expected:
            print(f'DOWNLOAD {args.project} {version} build {build["id"]}', flush=True)
            partial = root / 'server.jar.part'
            with fetch(download['url']) as response, partial.open('wb') as output:
                shutil.copyfileobj(response, output)
            if sha256(partial) != expected:
                raise AssertionError('Official server jar checksum mismatch')
            partial.replace(jar)
        report['serverSha256'] = sha256(jar)
        plugins = root / 'plugins'
        plugins.mkdir(exist_ok=True)
        foreign = [path for path in plugins.glob('*.jar') if path.name != 'Animus.jar']
        if foreign:
            raise RuntimeError('QA directory contains unexpected plugins; refusing to modify it')
        shutil.copy2(args.plugin, plugins / 'Animus.jar')
        shutil.copy2(args.accepted_eula, root / 'eula.txt')
        data = plugins / 'Animus'
        data.mkdir(exist_ok=True)
        (data / 'config.toml').write_text('statusMessage = "Platform smoke"\npacketProofEnabled = true\n')
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            port = listener.getsockname()[1]
        report['port'] = port
        flat = json.dumps({'layers': [{'block': 'minecraft:bedrock', 'height': 1}, {'block': 'minecraft:stone', 'height': 2}, {'block': 'minecraft:grass_block', 'height': 1}], 'biome': 'minecraft:plains'}, separators=(',', ':'))
        (root / 'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nlevel-type=minecraft:flat\ngenerator-settings={flat}\ngenerate-structures=false\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\nmax-players=4\nenable-status=false\nmax-tick-time=60000\npause-when-empty-seconds=-1\n')
        mojang = Path.home() / '.gradle/caches/fabric-loom' / version / 'minecraft-server.jar'
        if mojang.is_file():
            cache = root / 'cache'
            cache.mkdir(exist_ok=True)
            cached = cache / f'mojang_{version}.jar'
            if not cached.exists():
                shutil.copy2(mojang, cached)
        log = (root / 'platform-smoke.log').open('w')
        for cycle in range(2):
            print(f'START {args.project} {version} cycle {cycle + 1} port {port}', flush=True)
            server = Server(root, args.java, log)
            report['checks'].append(server.wait(r'Done \([\d.]+s\)!', timeout=180))
            report['checks'].append(server.command('animus status', rf'Animus 0\.1\.0 \| BUKKIT \| Minecraft {re.escape(version)} \| Platform smoke'))
            expected_binding = r'Packet proof unavailable on Folia' if args.project == 'folia' else rf'Packet proof ready \| Minecraft {re.escape(version)}'
            report['checks'].append(server.command('animus proof status', expected_binding))
            if cycle == 0:
                report['checks'].append(server.command('animus create platform-smoke minecraft:player world 0 64 0 qa "Platform Smoke"', r'Created dormant platform-smoke;'))
                report['checks'].append(server.command('animus inspect platform-smoke', r'"name":"Platform Smoke"'))
                report['checks'].append(server.command('animus validate', r'All definitions valid;'))
            else:
                report['checks'].append(server.command('animus inspect platform-smoke', r'"name":"Platform Smoke"'))
                report['checks'].append(server.command('animus delete platform-smoke', r'Deleted [^;]*; tx '))
            server.close()
            server = None
            report['checks'].append(f'clean shutdown cycle {cycle + 1}')
            print(f'PASS {args.project} {version} cycle {cycle + 1}', flush=True)
        report['status'] = 'pass'
        return report
    except Exception as error:
        report['status'] = 'failed'
        report['error'] = f'{type(error).__name__}: {error}'
        raise
    finally:
        if server is not None:
            try:
                server.close()
            except Exception as error:
                report['cleanupError'] = str(error)
                report['status'] = 'failed'
        if log is not None:
            log.close()
        report['finishedAt'] = datetime.now(timezone.utc).isoformat()
        (root / 'evidence.json').write_text(json.dumps(report, indent=2) + '\n')
        lock.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--versions', nargs='+', default=['26.1.2', '26.2'])
    parser.add_argument('--project', choices=['paper', 'folia'], default='paper')
    parser.add_argument('--channel', choices=['STABLE', 'BETA', 'ALPHA'], default='STABLE')
    parser.add_argument('--build', type=int, help='Pin one exact build; requires a single version')
    parser.add_argument('--root', type=Path, default=Path('.qa/platform-tests'))
    parser.add_argument('--plugin', type=Path, required=True)
    parser.add_argument('--accepted-eula', type=Path, required=True)
    parser.add_argument('--java', required=True)
    args = parser.parse_args()
    if any(version not in ('26.1.2', '26.2', '26.3') for version in args.versions):
        parser.error('Only exact approved 26.x target versions are allowed')
    if len(set(args.versions)) != len(args.versions):
        parser.error('Each exact version can appear only once')
    if args.build is not None and (args.build < 1 or len(args.versions) != 1):
        parser.error('--build requires a positive build number and one exact version')
    if not args.plugin.is_file():
        parser.error('Built Animus plugin is missing')
    if not args.accepted_eula.is_file() or not re.search(r'(?m)^\s*eula\s*=\s*true\s*$', args.accepted_eula.read_text()):
        parser.error('Supply an existing accepted EULA file')
    reports = []
    failures = []
    with ThreadPoolExecutor(max_workers=2) as workers:
        tasks = {workers.submit(run_target, args, version): version for version in args.versions}
        for future in as_completed(tasks):
            version = tasks[future]
            try:
                reports.append(future.result())
            except Exception as error:
                failures.append({'minecraftVersion': version, 'error': f'{type(error).__name__}: {error}'})
                print(f'FAIL {args.project} {version}: {error}', flush=True)
    args.root.mkdir(parents=True, exist_ok=True)
    (args.root / f'{args.project}-summary.json').write_text(json.dumps({'completed': reports, 'failures': failures}, indent=2) + '\n')
    if failures:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
