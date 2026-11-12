"""The hidden recording client: a Prism Launcher instance with the Animus Camera mod, driven by queued step scripts."""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import threading
import time
from pathlib import Path
from typing import Any, Callable

INSTANCE: str = os.environ.get('ANIMUS_DEMO_INSTANCE', '[AUTOMATER]')
PRISM_HOME: Path = Path.home() / 'Library' / 'Application Support' / 'PrismLauncher'
PRISM: Path = Path('/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher')
# Mods that fight a hidden, never focused window: frame throttling, GLFW thread moves, crash windows.
DISABLED_MODS: tuple[str, ...] = ('dynamic-fps', 'ixeris', 'crashassistant')
CONSOLE_KEYS: dict[str, str] = {'OverrideConsole': 'true', 'ShowConsole': 'false', 'ShowConsoleOnError': 'false', 'AutoCloseConsole': 'true'}
OPTIONS: dict[str, str] = {
    'pauseOnLostFocus': 'false', 'guiScale': '4', 'maxFps': '60', 'enableVsync': 'false', 'inactivityFpsLimit': '"minimized"',
    'fullscreen': 'false', 'overrideWidth': '1920', 'overrideHeight': '1080', 'tutorialStep': 'none', 'skipMultiplayerWarning': 'true',
    'onboardAccessibility': 'false', 'joinedFirstServer': 'true', 'renderDistance': '8', 'soundCategory_master': '0.0',
    'realmsNotifications': 'false', 'chatWidth': '0.35', 'advancedItemTooltips': 'false',
}
SESSION_CLASH: re.Pattern[str] = re.compile(r'invalid session|Failed to verify username')
LAUNCH_ATTEMPTS: int = 3


def find_ffmpeg() -> Path:
    for candidate in (os.environ.get('ANIMUS_DEMO_FFMPEG'), str(Path.home() / '.local/bin/ffmpeg'), shutil.which('ffmpeg')):
        if candidate and Path(candidate).is_file():
            return Path(candidate)
    raise FileNotFoundError('ffmpeg with libvpx-vp9 is needed (set ANIMUS_DEMO_FFMPEG)')


class Client:
    def __init__(self, state: Path, instance: str = INSTANCE) -> None:
        self.instance: str = instance
        self.root: Path = PRISM_HOME / 'instances' / instance
        self.game: Path = self.root / 'minecraft'
        self.camera: Path = self.game / 'config' / 'animuscamera'
        self.changes: Path = state / 'client-changes.json'
        self.pattern: str = 'java.*instances/' + re.escape(instance) + '/'

    # Instance setup, recorded so restore() can undo it

    def _load_changes(self) -> dict[str, Any]:
        return json.loads(self.changes.read_text()) if self.changes.exists() else {'disabled': [], 'mod': '', 'backups': {}, 'packs': []}

    def prepare(self, mod_jar: Path) -> None:
        if not self.game.is_dir():
            raise FileNotFoundError(f'Prism instance not found: {self.root}')
        changes: dict[str, Any] = self._load_changes()
        state: Path = self.changes.parent
        state.mkdir(parents=True, exist_ok=True)
        for name in ('instance.cfg', 'minecraft/options.txt'):
            source: Path = self.root / name
            if name not in changes['backups']:
                backup: Path = state / 'client-backup' / name
                backup.parent.mkdir(parents=True, exist_ok=True)
                if source.exists():
                    shutil.copy2(source, backup)
                changes['backups'][name] = str(backup) if source.exists() else ''
        mods: Path = self.game / 'mods'
        for old in mods.glob('animus-camera-*.jar'):
            old.unlink()
        shutil.copy2(mod_jar, mods / mod_jar.name)
        changes['mod'] = mod_jar.name
        for jar in mods.glob('*.jar'):
            if jar.name.lower().startswith(DISABLED_MODS):
                jar.rename(jar.with_name(jar.name + '.disabled'))
                changes['disabled'].append(jar.name)
        self._set_keys(self.root / 'instance.cfg', CONSOLE_KEYS, '=')
        self._write_options()
        self.camera.mkdir(parents=True, exist_ok=True)
        (self.camera / 'settings.json').write_text(json.dumps({'ffmpeg': str(find_ffmpeg()), 'toasts': False}, indent=1))
        self.changes.write_text(json.dumps(changes, indent=1))

    def use_packs(self, packs: dict[str, Path]) -> dict[str, str]:
        """Puts exactly these packs in resourcepacks/ (removing ones an earlier clip added) and returns the resourcePacks
        option: the instance's own list plus these packs."""
        changes: dict[str, Any] = self._load_changes()
        folder: Path = self.game / 'resourcepacks'
        for name in changes.get('packs', []):
            if name not in packs:
                (folder / name).unlink(missing_ok=True)
        for name, source in packs.items():
            if not source.is_file():
                raise FileNotFoundError(f'resource pack {name} needs {source}')
            folder.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, folder / name)
        changes['packs'] = list(packs)
        self.changes.write_text(json.dumps(changes, indent=1))
        return {'resourcePacks': json.dumps(self._own_packs() + [f'file/{name}' for name in packs], separators=(',', ':'))}

    def _own_packs(self) -> list[str]:
        """The resourcePacks list the instance had before prepare(), or Minecraft's default."""
        backup: str = self._load_changes()['backups'].get('minecraft/options.txt', '')
        source: Path = Path(backup) if backup else self.game / 'config' / 'modpack_defaults' / 'options.txt'
        lines: list[str] = source.read_text().splitlines() if source.is_file() else []
        found: list[str] = [line.split(':', 1)[1] for line in lines if line.startswith('resourcePacks:')]
        return list(json.loads(found[0])) if found else ['vanilla']

    def _write_options(self, extra: dict[str, str] | None = None) -> None:
        options: Path = self.game / 'options.txt'
        if not options.exists():
            defaults: Path = self.game / 'config' / 'modpack_defaults' / 'options.txt'
            options.write_text(defaults.read_text() if defaults.exists() else '')
        self._set_keys(options, {**OPTIONS, **(extra or {})}, ':')

    @staticmethod
    def _set_keys(path: Path, values: dict[str, str], separator: str) -> None:
        lines: list[str] = path.read_text().splitlines() if path.exists() else []
        kept: list[str] = [line for line in lines if line.split(separator, 1)[0] not in values]
        path.write_text('\n'.join(kept + [f'{k}{separator}{v}' for k, v in values.items()]) + '\n')

    def restore(self) -> None:
        """Puts the instance back as it was before prepare(): mods, instance.cfg, options.txt, the camera folder."""
        if not self.changes.exists():
            return
        changes: dict[str, Any] = self._load_changes()
        mods: Path = self.game / 'mods'
        if changes['mod']:
            (mods / changes['mod']).unlink(missing_ok=True)
        for name in changes['disabled']:
            disabled: Path = mods / (name + '.disabled')
            if disabled.exists():
                disabled.rename(mods / name)
        for name in changes.get('packs', []):
            (self.game / 'resourcepacks' / name).unlink(missing_ok=True)
        for name, backup in changes['backups'].items():
            target: Path = self.root / name
            if backup:
                shutil.copy2(backup, target)
            else:
                target.unlink(missing_ok=True)
        shutil.rmtree(self.camera, ignore_errors=True)
        self.changes.unlink()
        shutil.rmtree(self.changes.parent / 'client-backup', ignore_errors=True)

    # Running scripts

    def running(self) -> bool:
        return subprocess.run(['pgrep', '-f', self.pattern], capture_output=True).returncode == 0

    def run(self, scripts: list[tuple[str, list[dict[str, Any]]]], port: int | None, timeout: float, options: dict[str, str] | None = None,
            server_log: Callable[[], str] | None = None, on_poll: Callable[[], None] | None = None) -> str:
        """Queues the scripts (run in name order by one client), launches the client and returns the last script's log."""
        for attempt in range(1, LAUNCH_ATTEMPTS + 1):
            self._wait_closed(120.0)
            self._write_options(options)
            queue: Path = self.camera / 'queue'
            done: Path = self.camera / 'done'
            queue.mkdir(parents=True, exist_ok=True)
            done.mkdir(parents=True, exist_ok=True)
            for stale in queue.glob('*.json'):
                stale.unlink()
            for name, steps in scripts:
                (done / f'{name}.log').unlink(missing_ok=True)
                (queue / f'{name}.json').write_text(json.dumps(steps, indent=1))
            log_start: int = len(server_log()) if server_log else 0
            args: list[str] = [str(PRISM), '--launch', self.instance]
            if port:
                args += ['--server', f'127.0.0.1:{port}']
            subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            watchdog: threading.Thread = threading.Thread(target=self._watchdog, args=(timeout,), daemon=True)
            watchdog.start()
            final: Path = done / f'{scripts[-1][0]}.log'
            deadline: float = time.time() + timeout
            clash: bool = False
            while not final.exists():
                if on_poll:
                    on_poll()
                if server_log and SESSION_CLASH.search(server_log()[log_start:]):
                    clash = True
                    break
                if time.time() > deadline:
                    self.kill()
                    raise TimeoutError(f'{scripts[-1][0]} did not finish within {timeout:.0f} s')
                time.sleep(0.25)
            if clash:
                self.kill()
                print(f'  session clash on attempt {attempt}, launching again', flush=True)
                time.sleep(10.0)
                continue
            self._wait_closed(60.0)
            return final.read_text()
        raise RuntimeError('the client could not join after three attempts (session clash)')

    def kill(self) -> None:
        subprocess.run(['pkill', '-f', self.pattern], capture_output=True)
        time.sleep(3.0)

    def _wait_closed(self, timeout: float) -> None:
        deadline: float = time.time() + timeout
        while self.running():
            if time.time() > deadline:
                self.kill()
                return
            time.sleep(1.0)

    def _watchdog(self, timeout: float) -> None:
        """Kills the client at once if it ever becomes a visible foreground app; the owner's screen stays his."""
        start: float = time.time()
        while time.time() < start + timeout:
            found: subprocess.CompletedProcess[str] = subprocess.run(['pgrep', '-f', self.pattern], capture_output=True, text=True)
            for pid in found.stdout.split():
                asn: str = subprocess.run(['lsappinfo', 'find', f'pid={pid}'], capture_output=True, text=True).stdout.strip()
                if asn and 'type="Foreground"' in subprocess.run(['lsappinfo', 'info', '-app', asn], capture_output=True, text=True).stdout:
                    subprocess.run(['kill', '-9', pid])
                    print(f'  client became visible (pid {pid}), killed', flush=True)
                    return
            if found.returncode != 0 and time.time() > start + 60.0:
                return
            time.sleep(0.3)
