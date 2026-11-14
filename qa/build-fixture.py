#!/usr/bin/env python3
"""Compile the QA-only integration listener into .qa; never package it as an Animus distribution."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java-home', type=Path, required=True)
parser.add_argument('--spigot-api', type=Path, required=True)
parser.add_argument('--plugin', type=Path, help='Exact recorded Animus artifact; defaults to the local packedJar output')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
output = root / '.qa/event-fixture'
classes = output / 'classes'
previous = output / 'AnimusProofFixture.jar'
if previous.exists():
    previous_hash = hashlib.sha256(previous.read_bytes()).hexdigest()
    history = output / 'history' / previous_hash
    history.mkdir(parents=True, exist_ok=True)
    shutil.copy2(previous, history / previous.name)
    if (output / 'build.json').exists(): shutil.copy2(output / 'build.json', history / 'build.json')
shutil.rmtree(classes, ignore_errors=True)
classes.mkdir(parents=True, exist_ok=True)
plugin = (args.plugin or root / 'bukkit/build/libs/Animus v0.1.0 [CraftBukkit] 26.1.2-26.3.jar').resolve()
sources = sorted((root / 'qa/fixture/src').rglob('*.java'))
# The packed plugin jar keeps its classes in a compressed runtime, so javac reads the unpacked jar of the same build.
unpacked = root / 'bukkit/build/tmp/packaging/distribution' / plugin.name
subprocess.run([str(args.java_home / 'bin/javac'), '--release', '25', '-cp', os.pathsep.join([str(unpacked if unpacked.exists() else plugin), str(args.spigot_api)]), '-d', str(classes), *map(str, sources)], check=True)
(classes / 'plugin.yml').write_text((root / 'qa/fixture/plugin.yml').read_text())
jar = output / 'AnimusProofFixture.jar'
subprocess.run([str(args.java_home / 'bin/jar'), '--create', '--file', str(jar), '-C', str(classes), '.'], check=True)
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
(output / 'build.json').write_text(json.dumps({'plugin': str(plugin), 'pluginSha256': digest(plugin), 'spigotApi': str(args.spigot_api.resolve()), 'spigotApiSha256': digest(args.spigot_api), 'sources': {str(source.relative_to(root)): digest(source) for source in [*sources, root / 'qa/fixture/plugin.yml']}, 'fixtureSha256': digest(jar)}, indent=2) + '\n')
print(jar)
