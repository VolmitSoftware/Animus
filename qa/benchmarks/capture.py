#!/usr/bin/env python3
"""Capture explicitly selected benchmark inputs and host facts; no process/env/credential discovery."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--minecraft', choices=('26.1.2', '26.2', '26.3'), required=True)
    parser.add_argument('--server-build', required=True)
    parser.add_argument('--server-channel', choices=('stable', 'beta', 'alpha'), required=True)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--jvm-arg', action='append', default=[])
    parser.add_argument('--artifact', action='append', default=[], metavar='LABEL=FILE')
    parser.add_argument('--config', action='append', default=[], metavar='LABEL=FILE')
    parser.add_argument('--note', action='append', default=[])
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Output already exists; capture into a new run directory')
    workload = json.loads(args.manifest.read_text())
    for relative, expected in workload['files'].items():
        file = args.manifest.parent / relative
        if file.resolve().is_relative_to(args.manifest.parent.resolve()) is False or digest(file) != expected:
            parser.error(f'Workload file changed or escaped root: {relative}')
    artifacts = {}
    for category, pairs in (('artifact', args.artifact), ('config', args.config)):
        for pair in pairs:
            label, separator, name = pair.partition('=')
            if not separator or not label or label in artifacts:
                parser.error('Expected unique LABEL=FILE for every input')
            file = Path(name).resolve()
            if any(word in file.name.lower() for word in ('service-account', 'credential', 'secret', 'token')):
                parser.error('Credential-like inputs are not benchmark artifacts')
            artifacts[label] = {'kind': category, 'fileName': file.name, 'bytes': file.stat().st_size, 'sha256': digest(file)}
    version = subprocess.run([str(args.java), '-version'], text=True, capture_output=True, timeout=15, check=True)
    try:
        ram = int(os.sysconf('SC_PHYS_PAGES')) * int(os.sysconf('SC_PAGE_SIZE'))
    except (ValueError, OSError):
        ram = None
    result = {'schemaVersion': 1, 'status': 'environment-captured-not-measured', 'capturedUtc': datetime.now(timezone.utc).isoformat(),
              'workloadCase': workload['case'], 'manifestSha256': digest(args.manifest),
              'host': {'system': platform.system(), 'release': platform.release(), 'machine': platform.machine(),
                       'logicalCpus': os.cpu_count(), 'memoryBytes': ram},
              'runtime': {'minecraftDeclared': args.minecraft, 'serverBuildDeclared': args.server_build,
                          'serverChannelDeclared': args.server_channel, 'javaVersion': (version.stdout + version.stderr).strip(),
                          'javaExecutableSha256': digest(args.java.resolve()), 'jvmArgumentsDeclared': args.jvm_arg},
              'inputs': artifacts, 'notes': args.note,
              'stillRequired': ['Verify declared server version/build in actual boot logs', 'Capture actual startup arguments and profiler configuration',
                                'Record CPU model, power/thermal policy, other workload and graphical client hardware manually',
                                'Pass workload equivalence gates before recording measurements'], 'measurements': None}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x') as target:
        json.dump(result, target, indent=2, sort_keys=True)
        target.write('\n')
    print(json.dumps({'status': result['status'], 'output': str(args.output)}))


if __name__ == '__main__':
    main()
