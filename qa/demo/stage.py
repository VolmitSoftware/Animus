"""The burner servers the clips are filmed on: one isolated Multiplexor instance per platform, two world snapshots each."""
from __future__ import annotations

import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path
from typing import Any

DEMO: Path = Path(__file__).resolve().parent
REPO: Path = DEMO.parents[1]
sys.path.insert(0, str(REPO / 'qa' / 'multiplexor'))
import multiplexor_workspace  # noqa: E402
from sheet import fill  # noqa: E402

NAME: str = os.environ.get('ANIMUS_DEMO_STAGE', 'animus-clips-paper-26-3')
MINECRAFT: str = '26.3'
VIA_VERSION: str = '5.12.2'
# Per platform: the instance, the Multiplexor consumer and server type, where jars go, and where Animus keeps its data.
# Sheet files are written as plugins/Animus/...; on Fabric the same paths land under config/animus/.
PLATFORMS: dict[str, dict[str, str]] = {
    'paper': {'name': NAME, 'consumer': 'plugin', 'type': 'paper', 'jars': 'plugins', 'data': 'plugins/Animus'},
    'fabric': {'name': NAME.replace('paper', 'fabric'), 'consumer': 'fabric', 'type': 'fabric', 'jars': 'mods', 'data': 'config/animus'},
}
# Modrinth project and version per platform. ViaFabric needs the full Fabric API; ViaVersion is pinned so ViaBackwards matches it.
SUPPORT: dict[str, tuple[tuple[str, str], ...]] = {
    'paper': (('viaversion', VIA_VERSION), ('viabackwards', VIA_VERSION)),
    'fabric': (('fabric-api', '0.166.0+26.3'), ('viafabric', '0.4.22+188-26.x'), ('viaversion', VIA_VERSION), ('viabackwards', VIA_VERSION)),
}
TOWN: Path = DEMO / 'stage' / 'town'
LOG_POLL: float = 0.1
START_TIMEOUT: float = 300.0
CONSOLE_WAIT: float = 3.0
TOWN_COMMAND_GAP: float = 0.03

# Flat worlds: the town stands on y=64, the flat stage on y=61 (the round three clips were shot there).
WORLDS: dict[str, dict[str, Any]] = {
    'town': {
        'layers': [('minecraft:bedrock', 1), ('minecraft:stone', 124), ('minecraft:dirt', 2), ('minecraft:grass_block', 1)],
        'time': 6000, 'spawn': '0 64 7', 'forceload': '-64 -64 63 63',
    },
    'flat': {
        'layers': [('minecraft:bedrock', 1), ('minecraft:stone', 120), ('minecraft:dirt', 3), ('minecraft:grass_block', 1)],
        'time': 1000, 'spawn': '0 61 0', 'forceload': '-48 -48 48 48',
    },
}
GAMERULES: tuple[str, ...] = (
    'gamerule minecraft:advance_time false',
    'gamerule minecraft:advance_weather false',
    'gamerule minecraft:spawn_mobs false',
    'gamerule minecraft:send_command_feedback false',
    'weather clear',
    'difficulty peaceful',
)


def free_port(preferred: int) -> int:
    for port in range(preferred, preferred + 200):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            try:
                probe.bind(('127.0.0.1', port))
            except OSError:
                continue
            return port
    raise RuntimeError(f'no free port from {preferred}')


def java_home() -> str:
    """A Java 25 or newer home: ANIMUS_DEMO_JAVA_HOME, JAVA_HOME, Homebrew openjdk@25, then java_home -v 25."""
    found: subprocess.CompletedProcess[str] = subprocess.run(['/usr/libexec/java_home', '-v', '25'], capture_output=True, text=True)
    candidates: list[str] = [os.environ.get('ANIMUS_DEMO_JAVA_HOME', ''), os.environ.get('JAVA_HOME', ''),
                             '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home', found.stdout.strip()]
    for home in candidates:
        java: Path = Path(home) / 'bin' / 'java'
        if home and java.is_file():
            version: str = subprocess.run([str(java), '-version'], capture_output=True, text=True).stderr
            major: re.Match[str] | None = re.search(r'version "(\d+)', version)
            if major and int(major.group(1)) >= 25:
                return home
    raise RuntimeError('Java 25 or newer is needed for the 26.3 stage; set ANIMUS_DEMO_JAVA_HOME')


def server_properties(world: str, port: int) -> dict[str, str]:
    layers: list[dict[str, Any]] = [{'block': block, 'height': height} for block, height in WORLDS[world]['layers']]
    generator: str = json.dumps({'biome': 'minecraft:plains', 'layers': layers, 'structure_overrides': []}, separators=(',', ':'))
    return {
        'server-port': str(port), 'online-mode': 'true', 'enforce-secure-profile': 'true', 'motd': 'Animus tutorial stage',
        'level-type': 'minecraft\\:flat', 'generator-settings': generator.replace(':', '\\:'), 'generate-structures': 'false',
        'gamemode': 'creative', 'force-gamemode': 'true', 'difficulty': 'peaceful', 'spawn-protection': '0', 'allow-flight': 'true',
        'view-distance': '10', 'simulation-distance': '8', 'max-players': '5', 'pause-when-empty-seconds': '-1',
        'enable-command-block': 'true', 'white-list': 'false',
    }


def support_folder(folder: Path, platform: str) -> Path:
    """Paper jars sit in the plugin folder itself; Fabric ones in a subfolder, since ViaVersion's file names match."""
    return folder if platform == 'paper' else folder / platform


def fetch_support(folder: Path, platform: str) -> list[Path]:
    """The jars that let the 26.2 recording client join the 26.3 stage (Via, and on Fabric its API). Downloaded once from Modrinth."""
    folder = support_folder(folder, platform)
    folder.mkdir(parents=True, exist_ok=True)
    jars: list[Path] = []
    for project, version in SUPPORT[platform]:
        existing: list[Path] = sorted(p for p in folder.glob('*.jar') if p.name.lower().startswith(f'{project}-{version}'))
        if existing:
            jars.append(existing[0])
            continue
        url: str = f'https://api.modrinth.com/v2/project/{project}/version'
        request: urllib.request.Request = urllib.request.Request(url, headers={'User-Agent': 'animus-demo-stage'})
        with urllib.request.urlopen(request, timeout=60) as response:
            versions: list[dict[str, Any]] = json.loads(response.read())
        match: dict[str, Any] = next(v for v in versions if v['version_number'] == version and platform in v['loaders'])
        file: dict[str, Any] = next(f for f in match['files'] if f.get('primary')) if any(f.get('primary') for f in match['files']) else match['files'][0]
        target: Path = folder / file['filename']
        with urllib.request.urlopen(urllib.request.Request(file['url'], headers={'User-Agent': 'animus-demo-stage'}), timeout=120) as response:
            target.write_bytes(response.read())
        jars.append(target)
    return jars


def content_path(platform: str, relative: str) -> str:
    """Where a sheet file (a path under plugins/) goes in the instance: Animus/ moves to the platform's data folder."""
    if relative == 'Animus' or relative.startswith('Animus/'):
        return PLATFORMS[platform]['data'] + relative[len('Animus'):]
    return f'{PLATFORMS[platform]["jars"]}/{relative}'


def find_plugin(folder: Path, name: str) -> Path:
    """A third-party plugin jar a sheet names, found by name prefix in the plugin folder."""
    wanted: str = re.sub(r'[^a-z0-9]', '', name.lower())
    for jar in sorted(folder.glob('*.jar')):
        if re.sub(r'[^a-z0-9]', '', jar.stem.lower()).startswith(wanted):
            return jar
    raise FileNotFoundError(f'plugin {name} is needed; put its jar in {folder}')


class Stage:
    def __init__(self, cache: Path, player: str, port: int = 0, platform: str = 'paper', workspace: Path | None = None) -> None:
        self.platform: str = platform
        self.settings: dict[str, str] = PLATFORMS[platform]
        self.cache: Path = cache / platform
        self.player: str = player
        self.port: int = port
        self.name: str = self.settings['name']
        self.workspace: Path = workspace or multiplexor_workspace.default_workspace()
        self.session: str = f'mc-{self.settings["consumer"]}-{self.name}'
        self.world: str = ''
        self._dir: Path | None = None
        self.installed: list[str] = []

    # Multiplexor and console

    def mux(self, *args: str) -> str:
        done: subprocess.CompletedProcess[str] = subprocess.run(['./start.sh', '--consumer', self.settings['consumer'], *args], cwd=self.workspace, capture_output=True, text=True, timeout=900)
        if done.returncode != 0:
            raise RuntimeError(f'multiplexor {" ".join(args)} failed: {(done.stdout + done.stderr)[-800:]}')
        return done.stdout

    def exists(self) -> bool:
        return any(line.split()[0] == self.name for line in self.mux('instance', 'list').splitlines() if line.strip())

    @property
    def dir(self) -> Path:
        if self._dir is None:
            self._dir = Path(self.mux('instance', 'path', self.name).strip().splitlines()[-1])
        return self._dir

    @property
    def data(self) -> Path:
        return self.dir / self.settings['data']

    @property
    def log(self) -> Path:
        return self.dir / 'logs' / 'latest.log'

    def text(self) -> str:
        try:
            return re.sub(r'\x1b\[[0-9;]*m', '', self.log.read_text(encoding='utf-8', errors='replace'))
        except FileNotFoundError:
            return ''

    def wait_log(self, pattern: str, start: int = 0, timeout: float = 30.0) -> re.Match[str]:
        deadline: float = time.time() + timeout
        compiled: re.Pattern[str] = re.compile(pattern)
        while True:
            found: re.Match[str] | None = compiled.search(self.text(), start)
            if found:
                return found
            if time.time() > deadline:
                raise TimeoutError(f'{self.name}: no "{pattern}" within {timeout:.0f} s; log ends: {self.text()[-600:]}')
            time.sleep(LOG_POLL)

    def send(self, command: str) -> None:
        subprocess.run(['tmux', 'send-keys', '-t', self.session, '-l', command], check=True)
        subprocess.run(['tmux', 'send-keys', '-t', self.session, 'Enter'], check=True)

    def console(self, command: str, wait: float = CONSOLE_WAIT) -> str:
        """Sends one console command and returns the log lines it produced (waits for the first new line)."""
        start: int = len(self.text())
        self.send(command)
        deadline: float = time.time() + wait
        while time.time() < deadline and len(self.text()) == start:
            time.sleep(LOG_POLL)
        time.sleep(0.3)
        return self.text()[start:]

    # Lifecycle

    def create(self) -> None:
        if not self.exists():
            self.mux('server', 'create', self.name, '--type', self.settings['type'], '--mc', MINECRAFT, '--isolated')
        self.mux('runtime', 'settings', 'set-java', str(Path(java_home()) / 'bin' / 'java'), '--instance', self.name)
        self.mux('runtime', 'settings', 'set-heap', '3G', '--instance', self.name)
        if self.mux('instance', 'isolated', self.name).strip().splitlines()[-1] != 'true':
            raise RuntimeError(f'{self.name} is not isolated')
        self.mux('instance', 'port', self.name, str(self.port))
        (self.dir / 'eula.txt').write_text('eula=true\n')

    def running(self) -> bool:
        return subprocess.run(['tmux', 'has-session', '-t', self.session], capture_output=True).returncode == 0

    def start(self) -> None:
        self.log.unlink(missing_ok=True)
        self.mux('runtime', 'start', self.name, '--no-console')
        self.wait_log(r'Done \([\d.]+s\)!', 0, START_TIMEOUT)
        time.sleep(1.0)

    def stop(self) -> None:
        """Stops through the console so plugins save, then waits for the session to end."""
        if not self.running():
            return
        self.send('stop')
        deadline: float = time.time() + 60.0
        while self.running() and time.time() < deadline:
            time.sleep(0.5)
        if self.running():
            self.mux('runtime', 'stop', self.name, '--force')
        time.sleep(1.0)

    def delete(self) -> None:
        if not self.exists():
            return
        self.stop()
        try:
            self.mux('runtime', 'stop', self.name, '--force')
        except RuntimeError:
            pass
        self.mux('instance', 'delete', self.name)

    # Content

    def install_plugins(self, jars: list[Path]) -> None:
        """Puts exactly these jars in plugins/ or mods/ (the server must be stopped)."""
        folder: Path = self.dir / self.settings['jars']
        folder.mkdir(parents=True, exist_ok=True)
        for old in folder.glob('*.jar'):
            old.unlink()
        for jar in jars:
            shutil.copy2(jar, folder / jar.name)
        self.installed = sorted(jar.name for jar in jars)

    def write_files(self, files: dict[str, Any], values: dict[str, str]) -> None:
        for relative, content in files.items():
            target: Path = self.dir / content_path(self.platform, relative)
            if content is None:
                if target.is_dir():
                    shutil.rmtree(target)
                else:
                    target.unlink(missing_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            if isinstance(content, dict) and set(content) == {'from'}:
                shutil.copy2(DEMO / str(content['from']), target)
            elif isinstance(content, str):
                target.write_text(fill(content, values), encoding='utf-8')
            else:
                target.write_text(fill(json.dumps(content, indent=1), values) + '\n', encoding='utf-8')

    def write_properties(self, world: str) -> None:
        path: Path = self.dir / 'server.properties'
        wanted: dict[str, str] = server_properties(world, self.port)
        kept: list[str] = []
        if path.exists():
            kept = [line for line in path.read_text().splitlines() if line and line.split('=', 1)[0] not in wanted]
        path.write_text('\n'.join(kept + [f'{k}={v}' for k, v in wanted.items()]) + '\n')

    def snapshot(self, world: str) -> Path:
        return self.cache / world

    def build(self, world: str, plugins: list[Path], editor_port: int) -> None:
        """Generates a fresh world of this kind, dresses it, and keeps a copy for resets."""
        self.stop()
        self.install_plugins(plugins)
        self.write_properties(world)
        shutil.rmtree(self.dir / 'world', ignore_errors=True)
        shutil.rmtree(self.data, ignore_errors=True)
        if world == 'town':
            shutil.copytree(TOWN / 'Animus', self.data)
        self.start()
        settings: dict[str, Any] = WORLDS[world]
        for command in GAMERULES + (f'time set {settings["time"]}', f'setworldspawn {settings["spawn"]}', f'forceload add {settings["forceload"]}', f'op {self.player}'):
            self.console(command)
        if world == 'town':
            time.sleep(5.0)
            for line in (TOWN / 'town.mcfunction').read_text().splitlines():
                if line.strip():
                    self.send(line.strip())
                    time.sleep(TOWN_COMMAND_GAP)
            time.sleep(3.0)
        self.console('save-all flush', 10.0)
        self.stop()
        self.configure_animus(editor_port)
        target: Path = self.snapshot(world)
        shutil.rmtree(target, ignore_errors=True)
        target.mkdir(parents=True)
        shutil.copytree(self.dir / 'world', target / 'world', ignore=shutil.ignore_patterns('session.lock'))
        shutil.copytree(self.data, target / 'Animus')
        self.world = ''

    def configure_animus(self, editor_port: int) -> None:
        """No bStats from a burner; the editor listener gets a free port so it never fights another server."""
        config: Path = self.data / 'config.toml'
        text: str = config.read_text()
        text = re.sub(r'(?m)^metrics = .*$', 'metrics = false', text)
        text = re.sub(r'(?m)^port = \d+$', f'port = {editor_port}', text)
        config.write_text(text)

    def reset(self, world: str, plugins: list[Path], editor_port: int) -> None:
        """Stops the server, puts the world and Animus data back from the snapshot, and starts it again."""
        if not (self.snapshot(world) / 'world').is_dir():
            self.build(world, plugins, editor_port)
        self.stop()
        self.install_plugins(plugins)
        self.write_properties(world)
        for name, source in (('world', self.snapshot(world) / 'world'), (self.settings['data'], self.snapshot(world) / 'Animus')):
            shutil.rmtree(self.dir / name, ignore_errors=True)
            shutil.copytree(source, self.dir / name)
        self.world = world

    def prepare(self, world: str, plugins: list[Path], editor_port: int, files: dict[str, Any], console: list[str], values: dict[str, str]) -> None:
        self.reset(world, plugins, editor_port)
        self.write_files(files, values)
        self.start()
        for command in console:
            self.console(fill(command, values))

    def keep(self, plugins: list[Path], files: dict[str, Any], console: list[str], values: dict[str, str]) -> None:
        """For a sheet that continues from the last clip: same world, its own files and commands."""
        if sorted(p.name for p in plugins) != self.installed or not self.running():
            self.stop()
            self.install_plugins(plugins)
            self.write_files(files, values)
            self.start()
        else:
            self.write_files(files, values)
        for command in console:
            self.console(fill(command, values))

