#!/usr/bin/env python3
"""Exercise console administration and restart on an isolated, preconfigured Bukkit-family server.
Never run against a production server. Uses NPC IDs qa-smoke and qa-smoke-copy.
Requires an accepted EULA, server.jar, and the built Animus plugin in the supplied directory.
"""
import argparse
import json
import queue
import re
import subprocess
import threading
import time
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--server-dir', type=Path, required=True)
parser.add_argument('--java', required=True)
args = parser.parse_args()
root = args.server_dir.resolve()
if not (root / 'server.jar').is_file() or not (root / 'plugins/Animus.jar').is_file():
    parser.error('server.jar and plugins/Animus.jar must exist in the isolated server directory')
log = (root / 'animus-smoke.log').open('w')

class Server:
    def __init__(self):
        self.lines = queue.Queue()
        self.process = subprocess.Popen([args.java, '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xms256M', '-Xmx1G', '-jar', 'server.jar', 'nogui'], cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        def read():
            for line in self.process.stdout:
                log.write(line); log.flush(); self.lines.put(line)
        self.reader = threading.Thread(target=read, daemon=True); self.reader.start()
        self.wait(r'Done \([\d.]+s\)!', 90)
        print('PASS startup', flush=True)
    def wait(self, pattern, timeout=20):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try: line = self.lines.get(timeout=0.2)
            except queue.Empty:
                if self.process.poll() is not None: raise AssertionError('Server exited before ' + pattern)
                continue
            if re.search(pattern, line): return line
        raise AssertionError('Timeout waiting for ' + pattern)
    def command(self, command, expected):
        self.process.stdin.write(command + '\n'); self.process.stdin.flush()
        line = self.wait(expected)
        print('PASS ' + command.split(' ', 2)[0] + ' ' + command.split(' ', 2)[1], flush=True)
        return line
    def close(self):
        if self.process.poll() is None:
            self.process.stdin.write('stop\n'); self.process.stdin.flush()
            try: self.process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                self.process.kill(); self.process.wait(); raise AssertionError('Server failed to stop cleanly')
        self.reader.join(timeout=5)
        assert self.process.returncode == 0, self.process.returncode
        print('PASS clean shutdown', flush=True)

server = None
try:
    server = Server()
    server.command('animus status', r'Animus 0\.1\.0 \| BUKKIT')
    for npc in ('qa-smoke-copy', 'qa-smoke'):
        server.command('animus delete ' + npc, r'Deleted [^;]*; tx |Unknown NPC:')
    server.command('animus create qa-smoke type=minecraft:player world=world x=0 y=64 z=0 folder=qa name="Smoke Guard"', r'Created dormant qa-smoke;')
    server.command('animus edit qa-smoke {"name":"Persisted Guard"}', r'Saved[^;]*; tx ')
    server.command('animus inspect qa-smoke', r'"name":"Persisted Guard"')
    server.command('animus clone qa-smoke qa-smoke-copy', r'Cloned [^;]*; tx ')
    deletion = server.command('animus delete qa-smoke-copy', r'Deleted [^;]*; tx ')
    transaction = re.search(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', deletion).group()
    server.command('animus rollback ' + transaction, r'Rolled back [^;]*; tx ')
    server.command('animus validate', r'All definitions valid;')
    path = root / 'plugins/Animus/npcs/qa/qa-smoke.json'
    original = path.read_text()
    malformed = json.loads(original); malformed['position']['y'] = 'invalid'
    path.write_text(json.dumps(malformed))
    server.command('animus reload', r'\$\.position\.y:')
    server.command('animus inspect qa-smoke', r'"name":"Persisted Guard"')
    path.write_text(original)
    server.command('animus reload', r'Reloaded; ')
    server.close(); server = None
    assert (root / 'plugins/Animus/config.toml').is_file()
    server = Server()
    server.command('animus inspect qa-smoke', r'"name":"Persisted Guard"')
    server.command('animus inspect qa-smoke-copy', r'"name":"Persisted Guard"')
    for npc in ('qa-smoke-copy', 'qa-smoke'):
        server.command('animus delete ' + npc, r'Deleted [^;]*; tx ')
    server.close(); server = None
    print('PASS all console/persistence smoke scenarios; logs: ' + str(root / 'animus-smoke.log'), flush=True)
finally:
    if server is not None: server.close()
    log.close()
