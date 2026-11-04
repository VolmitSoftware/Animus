#!/usr/bin/env python3
"""Compile the QA-only integration listener into .qa; never package it as an Animus distribution."""
import argparse
import os
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java-home', type=Path, required=True)
parser.add_argument('--spigot-api', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
output = root / '.qa/event-fixture'
classes = output / 'classes'
classes.mkdir(parents=True, exist_ok=True)
plugin = root / 'bukkit/build/libs/Animus v0.1.0 [CraftBukkit] 26.1.2-26.3.jar'
subprocess.run([str(args.java_home / 'bin/javac'), '--release', '25', '-cp', os.pathsep.join([str(plugin), str(args.spigot_api)]), '-d', str(classes), *map(str, (root / 'qa/fixture/src').rglob('*.java'))], check=True)
(classes / 'plugin.yml').write_text((root / 'qa/fixture/plugin.yml').read_text())
jar = output / 'AnimusProofFixture.jar'
subprocess.run([str(args.java_home / 'bin/jar'), '--create', '--file', str(jar), '-C', str(classes), '.'], check=True)
print(jar)
