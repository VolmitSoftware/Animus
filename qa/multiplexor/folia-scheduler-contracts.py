#!/usr/bin/env python3
"""Capture exact cached Folia scheduler/event bytecode for independent review.
This is primary-source inspection evidence only. It does not start a server,
execute plugin code, or certify live ownership, retirement, or cleanup behavior.
The output directory must be new; captured evidence is made read-only.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import multiplexor_workspace as mw

TARGETS = [('26.1.2', 8, 'STABLE'), ('26.2', 7, 'BETA'), ('26.3', 6, 'ALPHA')]
CLASSES = {
    'public-entity-scheduler': 'io.papermc.paper.threadedregions.scheduler.FoliaEntityScheduler',
    'scheduled-task': 'io.papermc.paper.threadedregions.scheduler.FoliaEntityScheduler$EntityScheduledTask',
    'entity-scheduler': 'io.papermc.paper.threadedregions.EntityScheduler',
    'event-manager': 'io.papermc.paper.plugin.manager.PaperEventManager',
    'server-ownership': 'org.bukkit.craftbukkit.CraftServer',
}


def sha(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', type=Path, default=mw.default_workspace())
    parser.add_argument('--javap', type=Path, default=Path('/opt/homebrew/opt/openjdk@25/bin/javap'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if os.path.lexists(args.output): parser.error('Output exists; preserve it and choose a new directory')
    args.output.mkdir(parents=True, exist_ok=False)
    report = {'schemaVersion': 1, 'status': 'failed', 'scope': 'Exact cached platform bytecode capture; no live behavior certification',
              'capturedAt': datetime.now(timezone.utc).isoformat(), 'targets': [], 'errors': [],
              'tool': {'path': str(Path(__file__).resolve()), 'sha256': sha(Path(__file__))},
              'javap': {'path': str(args.javap.resolve()), 'version': subprocess.run([str(args.javap), '-version'], capture_output=True, text=True, check=True).stdout.strip()},
              'reviewedConstraints': [
                  'FoliaEntityScheduler.execute wraps execution and retirement with plugin.isEnabled; explicit service shutdown must not rely on either callback running after disable.',
                  'EntityScheduler.retire marks retirement under stateLock and releases that monitor before invoking retirement callbacks.',
                  'ScheduledTask.cancel is atomic; a running repeating callback can finish, so generation checks are still required.',
                  'CraftServer.isPrimaryThread recognizes TickThread; this permits synchronous events on an owning region thread without granting ownership of other regions.',
                  'Entity and rectangle ownership checks use TickThread.isTickThreadFor; location scheduling is not a substitute for already-owned, loaded terrain.',
              ],
              'liveGatesStillRequired': ['entity and whole-terrain ownership before reads', 'separate-region viewer isolation',
                                        'event listener cancellation and reentrant lifecycle', 'retirement/publication races',
                                        'slow channel cleanup bounds', 'shutdown and queued skin work']}
    try:
        for version, build, channel in TARGETS:
            name = mw.baseline_name('folia', version)
            instance = args.workspace.resolve() / 'consumers/plugin-consumers/instances' / name
            lifecycle = mw.evidence_root(args.workspace.resolve()) / name / 'runtime.json'
            prior = json.loads(lifecycle.read_text())
            assert prior['project'] == 'folia' and prior['minecraftVersion'] == version and prior['build'] == build and prior['channel'] == channel, 'Exact platform source mismatch'
            launcher = instance / 'server.jar'
            assert sha(launcher) == prior['serverSha256'], 'Cached launcher differs from pinned runtime provenance'
            server = instance / 'versions' / version / ('folia-' + version + '.jar')
            apis = list((instance / 'libraries/dev/folia/folia-api').glob('*/*.jar'))
            assert len(apis) == 1, 'Expected exactly one cached exact Folia API'
            api = apis[0]
            target = {'minecraftVersion': version, 'build': build, 'channel': channel,
                      'launcher': {'path': str(launcher), 'resolved': str(launcher.resolve()), 'sha256': sha(launcher)},
                      'implementation': {'path': str(server), 'sha256': sha(server)},
                      'api': {'path': str(api), 'sha256': sha(api)},
                      'priorLifecycleRecord': {'path': str(lifecycle), 'sha256': sha(lifecycle)}, 'classes': []}
            folder = args.output / version; folder.mkdir()
            for label, klass in CLASSES.items():
                result = subprocess.run([str(args.javap), '-p', '-c', '-classpath', str(server), klass], text=True, capture_output=True, timeout=30)
                assert result.returncode == 0 and result.stdout.startswith('Compiled from'), f'Could not inspect {version} {klass}: {result.stderr}'
                output = folder / (label + '.txt'); output.write_text(result.stdout)
                target['classes'].append({'name': klass, 'capture': str(output.resolve()), 'sha256': sha(output)})
            report['targets'].append(target)
        report['status'] = 'captured'
    except (AssertionError, OSError, ValueError, subprocess.SubprocessError) as error:
        report['errors'].append(str(error))
    path = args.output / 'contracts.json'
    path.write_text(json.dumps(report, indent=2) + '\n')
    for entry in args.output.rglob('*'):
        os.chmod(entry, 0o555 if entry.is_dir() else 0o444)
    os.chmod(args.output, 0o555)
    print(json.dumps({'status': report['status'], 'scope': report['scope'], 'evidence': str(path.resolve()), 'sha256': sha(path), 'errors': report['errors']}))
    return 0 if report['status'] == 'captured' else 1


if __name__ == '__main__':
    raise SystemExit(main())
