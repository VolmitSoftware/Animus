#!/usr/bin/env python3
"""Generate public synthetic idle workloads. This never starts a server or reads migration data."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import uuid

VERSIONS = ('26.1.2', '26.2', '26.3')
VARIANTS = ('no-plugin', 'animus')
CASES = ('reference-495', 'dormant-5000', 'dense-32', 'dense-64', 'dense-128')
NAMESPACE = uuid.UUID('962e3b79-f48d-5a84-825d-2026613e9822')
MIX = {'player': 483, 'armor_stand': 3, 'ender_dragon': 3, 'horse': 2, 'turtle': 1, 'frog': 1, 'llama': 1, 'armadillo': 1}


def encoded(value):
    return (json.dumps(value, indent=2, sort_keys=True, ensure_ascii=True) + '\n').encode()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(encoded(value))


def records(case, seed, world):
    if case == 'reference-495':
        types = [kind for kind, count in MIX.items() for _ in range(count)]
        active = 495
    elif case == 'dormant-5000':
        types, active = ['player'] * 5000, 64
    else:
        active = int(case.removeprefix('dense-'))
        types = ['player'] * active
    result = []
    sector_size = 128 if case == 'dense-128' else 64
    columns = 16 if sector_size == 128 else 8
    for i, kind in enumerate(types):
        sector, slot = divmod(i, sector_size)
        x = sector * 256 + (slot % columns - (columns - 1) / 2) * 1.5
        z = (slot // columns - 3.5) * 1.5
        result.append({'schemaVersion': 1, 'revision': 1, 'id': f'bench-{i:05}', 'group': 'benchmark',
                       'uuid': str(uuid.uuid5(NAMESPACE, f'{seed}/{case}/{i}')), 'name': f'Bench{i:05}',
                       'type': f'minecraft:{kind}', 'spawned': i < active,
                       'position': {'world': world, 'x': x, 'y': 61.0, 'z': z, 'yaw': 180.0, 'pitch': 0.0},
                       'behaviors': [], 'modules': {}})
    return result, sector_size


def expected_viewers(definitions, sector_size):
    active = [value for value in definitions if value['spawned']]
    viewers = []
    for sector in range(math.ceil(len(active) / sector_size)):
        point = {'x': sector * 256.0, 'y': 61.0, 'z': -16.0, 'yaw': 0.0, 'pitch': 0.0}
        visible = [npc for npc in active if sum((npc['position'][axis] - point[axis]) ** 2 for axis in ('x', 'y', 'z')) <= 48 ** 2]
        ids = sum(9 if npc['type'] == 'minecraft:ender_dragon' else 1 for npc in visible)
        if len(visible) > 128 or ids > 256:
            raise ValueError('Workload would silently truncate Animus presentation')
        viewers.append({'id': f'observer-{sector}', 'position': point, 'expectedNpcUuids': [npc['uuid'] for npc in visible],
                        'expectedNpcCount': len(visible), 'animusReservedEntityIds': ids})
    return viewers


def generate(output, case, seed='animus-benchmark-v1', world='world'):
    if case not in CASES or not world or len(world) > 128:
        raise ValueError('Invalid case/world')
    output.mkdir(parents=True, exist_ok=False)
    definitions, sector_size = records(case, seed, world)
    viewers = expected_viewers(definitions, sector_size)
    active = sum(npc['spawned'] for npc in definitions)
    write_json(output / 'workload.json', {'schemaVersion': 1, 'seed': seed, 'case': case, 'npcs': definitions})
    for npc in definitions:
        write_json(output / 'animus/npcs/benchmark' / f"{npc['id']}.json", npc)
    (output / 'animus/config.toml').write_text('''# Benchmark plugin settings; generated NPC data is JSON.
packetProofEnabled = false
[npcs]
enabled = true
viewDistance = 48
maximumVisible = 128
maximumViewers = 256
updatesPerTick = 16
''')
    write_json(output / 'no-plugin/expectations.json', {'npcPluginPresent': False, 'expectedNpcCount': 0, 'viewers': len(viewers)})
    manifest = {'schemaVersion': 1, 'status': 'generated-not-measured', 'case': case, 'seed': seed, 'world': world,
                'exactMinecraftTargets': VERSIONS, 'definitionCount': len(definitions),
                'activeCount': active, 'dormantCount': len(definitions) - active, 'viewers': viewers,
                'presentation': {'distanceBlocks': 48, 'animusMaximumVisible': 128, 'silentTruncationAllowed': False,
                                 'skins': 'no texture payload supplied; record/control any client skin fetching', 'behavior': 'stationary idle only'},
                'worldRecipe': {'seed': 710495, 'dimension': 'minecraft:overworld', 'flatFloorY': 60,
                                'floorBlocks': 'minecraft:stone', 'sectorSpacingBlocks': 256, 'floorRadiusBlocks': 32,
                                'clearAirFromY': 61, 'clearAirThroughY': 85,
                                'loadedChunkRadius': 3, 'viewDistanceChunks': 6, 'simulationDistanceChunks': 6,
                                'difficulty': 'normal', 'randomTickSpeed': 0, 'spawnMobs': False, 'weather': 'clear', 'time': 'noon'},
                'measurement': {'warmupSeconds': 120, 'sampleSeconds': 300, 'repetitions': 5,
                                'variantOrder': [list(VARIANTS[i % len(VARIANTS):] + VARIANTS[:i % len(VARIANTS)]) for i in range(5)],
                                'metrics': ['serverThreadMsPerTick', 'processCpuSeconds', 'residentBytes', 'heapBytes',
                                            'allocationBytesPerSecond', 'packetCount', 'networkBytes', 'clientFrameTimeMs'],
                                'results': None},
                'gates': ['Pin exact server, plugin and profiler jars/configs; no development fixture plugin in measured runs',
                          'Verify every expected UUID/type/position per viewer after stable chunk delivery in the Animus run',
                          'Reject any missing/extra body, cap truncation, drift, nameplate/skin/animation mismatch before measuring',
                          'Use identical world copy, clients, observer paths, loaded chunks and JVM flags for each variant',
                          'Baseline has the same world/viewers/measurement observer but no NPC plugin or NPC bodies'],
                'unavailableComparisons': ['Navigation, combat, scripted behavior, signed skins, mounts and join/teleport bursts need separate equivalent scenarios',
                                           'Folia renderer/combat unavailable; do not record zero-cost substitutes',
                                           'Client frame times require a graphical client and independent capture'],
                'files': {}}
    for file in sorted(output.rglob('*')):
        if file.is_file():
            manifest['files'][file.relative_to(output).as_posix()] = hashlib.sha256(file.read_bytes()).hexdigest()
    write_json(output / 'manifest.json', manifest)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--case', choices=CASES + ('all',), default='all')
    parser.add_argument('--seed', default='animus-benchmark-v1')
    parser.add_argument('--world', default='world')
    args = parser.parse_args()
    if args.case == 'all':
        args.output.mkdir(parents=True, exist_ok=False)
        for case in CASES:
            result = generate(args.output / case, case, args.seed, args.world)
            print(json.dumps({'case': case, 'definitions': result['definitionCount'], 'active': result['activeCount']}))
    else:
        result = generate(args.output, args.case, args.seed, args.world)
        print(json.dumps({'case': args.case, 'definitions': result['definitionCount'], 'active': result['activeCount']}))


if __name__ == '__main__':
    main()
