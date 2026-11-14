#!/usr/bin/env python3
"""Records the Animus in-game tutorial clips described by the sheets in qa/demo/sheets."""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

DEMO: Path = Path(__file__).resolve().parent
REPO: Path = DEMO.parents[1]
sys.path.insert(0, str(DEMO))
import clip_check  # noqa: E402
import sheet  # noqa: E402
from client import PRISM_HOME, Client  # noqa: E402
from stage import PLATFORMS, Stage, fetch_support, find_plugin, free_port  # noqa: E402

SHEETS: Path = DEMO / 'sheets'
BUILD: Path = REPO / 'build' / 'demo'
CUE: re.Pattern[str] = re.compile(r'cue_([A-Za-z0-9_-]+)')
CLIP_TIMEOUT: float = 900.0


def new_cues(log: str, known: set[str], fired: set[str]) -> list[str]:
    """Cue names seen in the server log that are declared and have not fired yet, in log order."""
    found: list[str] = []
    for match in CUE.finditer(log):
        name: str = match.group(1)
        if name in known and name not in fired:
            fired.add(name)
            found.append(name)
    return found


def active_player() -> str:
    """The profile name of the active Prism account (only the name is read)."""
    accounts: dict[str, Any] = json.loads((PRISM_HOME / 'accounts.json').read_text())
    for account in accounts.get('accounts', []):
        if account.get('active') and account.get('profile', {}).get('name'):
            return str(account['profile']['name'])
    raise RuntimeError('no active Prism account; pass --player')


def build_mod() -> Path:
    mod: Path = DEMO / 'mod'
    subprocess.run([str(mod / 'gradlew'), '-p', str(mod), 'build', '--console=plain', '-q'], check=True)
    return max((mod / 'build' / 'libs').glob('animus-camera-*.jar'), key=lambda p: p.stat().st_mtime)


def build_animus(platform: str) -> Path:
    """The Paper plugin from :bukkit:packedJar, or the Fabric mod from the adapters/fabric build."""
    if platform == 'fabric':
        subprocess.run([str(REPO / 'gradlew'), '-p', str(REPO / 'adapters' / 'fabric'), 'shadowJar', '--console=plain', '-q'], check=True)
        return max((REPO / 'adapters' / 'fabric' / 'build').glob('*/libs/Animus*Fabric*.jar'), key=lambda p: p.stat().st_mtime)
    subprocess.run([str(REPO / 'gradlew'), '-p', str(REPO), ':bukkit:packedJar', '--console=plain', '-q'], check=True)
    return max((REPO / 'bukkit' / 'build' / 'libs').glob('Animus*CraftBukkit*.jar'), key=lambda p: p.stat().st_mtime)


def report_sheets(sheets: dict[str, sheet.Sheet]) -> None:
    for clip in sheets.values():
        print(f'{clip.id:28} {clip.world:5} target {clip.target:4.0f} s  estimate {sheet.estimate_seconds(clip):5.1f} s  {clip.title}')


def record(args: argparse.Namespace, sheets: dict[str, sheet.Sheet]) -> int:
    chosen: list[sheet.Sheet] = [c for c in sheets.values() if c.tutorial] if args.all else [sheets[i] for i in args.ids]
    output: Path = args.output.resolve()
    (output / 'logs').mkdir(parents=True, exist_ok=True)
    plugin_folder: Path = args.plugins.resolve()
    mod_jar: Path = build_mod() if not args.mod_jar else args.mod_jar.resolve()
    given: dict[str, Path | None] = {'paper': args.animus_jar, 'fabric': args.fabric_jar}
    player: str = args.player or active_player()
    editor_port: int = free_port(9790)
    client: Client = Client(BUILD)
    stages: dict[str, Stage] = {}
    base: dict[str, list[Path]] = {}
    failed: list[str] = []
    try:
        client.prepare(mod_jar)
        for clip in chosen:
            if clip.platform not in stages:
                jar: Path = given[clip.platform].resolve() if given[clip.platform] else build_animus(clip.platform)
                base[clip.platform] = [jar, *fetch_support(plugin_folder, clip.platform)]
                stages[clip.platform] = open_stage(clip.platform, player, free_port(args.port), editor_port, client)
            stage: Stage = stages[clip.platform]
            values: dict[str, str] = {'player': player, 'address': f'127.0.0.1:{stage.port}'}
            if not record_one(clip, sheets, stage, client, base[clip.platform], plugin_folder, editor_port, values, output):
                failed.append(clip.id)
    finally:
        if not args.keep_stage:
            for stage in stages.values():
                stage.delete()
                print(f'deleted {stage.name}', flush=True)
            shutil.rmtree(BUILD / 'stage-cache', ignore_errors=True)
        if not args.keep_client:
            client.restore()
    print(f'failed: {" ".join(failed) or "none"}')
    return 1 if failed else 0


def open_stage(platform: str, player: str, port: int, editor_port: int, client: Client) -> Stage:
    stage: Stage = Stage(BUILD / 'stage-cache', player, port, platform)
    if stage.exists():
        stage.stop()
    print(f'stage {stage.name} on port {port}, editor port {editor_port}, client {client.instance}, player {player}', flush=True)
    stage.create()
    return stage


def record_one(clip: sheet.Sheet, sheets: dict[str, sheet.Sheet], stage: Stage, client: Client, base: list[Path],
               plugin_folder: Path, editor_port: int, values: dict[str, str], output: Path) -> bool:
    chain: list[sheet.Sheet] = [sheets[i] for i in clip.after] + [clip]
    plugins: list[Path] = base + [find_plugin(plugin_folder, name) for c in chain for name in c.plugins]
    files: dict[str, Any] = {k: v for c in chain for k, v in c.files.items()}
    console: list[str] = [command for c in chain for command in c.console]
    started: float = time.time()
    print(f'{clip.id}: staging the {clip.world} world', flush=True)
    if clip.resets or stage.world != clip.world:
        stage.prepare(clip.world, plugins, editor_port, files, console, values)
    else:
        stage.keep(plugins, files, console, values)
    target: Path = output / clip.output_name
    target.unlink(missing_ok=True)
    scripts: list[tuple[str, list[dict[str, Any]]]] = [(f'a{n}-{dep.id}-prep', sheet.compile_prep(dep, values)) for n, dep in enumerate(chain[:-1])]
    scripts.append((f'z-{clip.id}', sheet.compile_script(clip, target, values)))
    fired: set[str] = set()
    log_start: int = len(stage.text())

    def answer_cues() -> None:
        for name in new_cues(stage.text()[log_start:], set(clip.cues), fired):
            for command in clip.cues[name]:
                stage.send(sheet.fill(command, values))

    packs: dict[str, str] = client.use_packs({name: stage.dir / 'plugins' / source for name, source in clip.client_packs.items()})
    print(f'{clip.id}: recording', flush=True)
    script_log: str = client.run(scripts, None if clip.title_screen else stage.port, CLIP_TIMEOUT, {**packs, **clip.client_options},
                                 stage.text, answer_cues)
    (output / 'logs' / f'{clip.id}.log').write_text(script_log)
    errors: list[str] = [line for line in script_log.splitlines() if ' ERROR ' in line]
    if not target.exists():
        print(f'{clip.id}: no clip written; see {output / "logs" / (clip.id + ".log")}', flush=True)
        return False
    facts: clip_check.Facts = clip_check.probe(target)
    found: list[str] = clip_check.problems(facts, clip.target) + errors
    print(f'{clip.id}: {facts.seconds:.1f} s, {facts.size / 1e6:.1f} MB, took {time.time() - started:.0f} s'
          + (''.join(f'\n  {p}' for p in found) if found else ''), flush=True)
    return not found


def main() -> int:
    parser: argparse.ArgumentParser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('check', help='validate every sheet')
    commands.add_parser('list', help='list sheets with target and estimated length')
    rec: argparse.ArgumentParser = commands.add_parser('record', help='record clips')
    rec.add_argument('ids', nargs='*', help='sheet ids, e.g. 04-palette')
    rec.add_argument('--all', action='store_true', help='record every tutorial sheet in id order (not the ux- review sheets)')
    rec.add_argument('--output', type=Path, default=BUILD / 'clips')
    rec.add_argument('--player', help='the recording account name; default the active Prism account')
    rec.add_argument('--port', type=int, default=25640, help='first port to try for the stage')
    rec.add_argument('--plugins', type=Path, default=BUILD / 'plugins', help='folder with ViaVersion, ViaBackwards and third-party plugin jars')
    rec.add_argument('--animus-jar', type=Path, help='use this plugin jar instead of building bukkit:packedJar')
    rec.add_argument('--fabric-jar', type=Path, help='use this Fabric mod jar instead of building adapters/fabric')
    rec.add_argument('--mod-jar', type=Path, help='use this recorder mod jar instead of building qa/demo/mod')
    rec.add_argument('--keep-stage', action='store_true', help='leave the stage instance for another run')
    rec.add_argument('--keep-client', action='store_true', help='leave the recorder installed in the Prism instance')
    commands.add_parser('cleanup', help='delete the stage instance and restore the Prism instance')
    args: argparse.Namespace = parser.parse_args()

    sheets, found = sheet.load_all(SHEETS)
    if args.command == 'cleanup':
        for platform in PLATFORMS:
            Stage(BUILD / 'stage-cache', '', 0, platform).delete()
        shutil.rmtree(BUILD / 'stage-cache', ignore_errors=True)
        Client(BUILD).restore()
        return 0
    if found:
        print('\n'.join(found))
        return 2
    if args.command == 'check':
        print(f'{len(sheets)} sheets valid')
        return 0
    if args.command == 'list':
        report_sheets(sheets)
        return 0
    unknown: list[str] = [i for i in args.ids if i not in sheets]
    if unknown or (not args.ids and not args.all):
        print(f'unknown sheet ids: {" ".join(unknown)}' if unknown else 'name sheet ids or pass --all')
        return 2
    return record(args, sheets)


if __name__ == '__main__':
    sys.exit(main())
