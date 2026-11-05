#!/usr/bin/env python3
"""Independently audit a staged Citizens JSON bundle without printing private record data."""
import argparse
import collections
import copy
import hashlib
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('stage', type=Path)
parser.add_argument('--source', type=Path, required=True)
parser.add_argument('--expected-records', type=int, required=True)
args = parser.parse_args()
stage = args.stage.resolve()
summary = json.loads((stage / 'report.json').read_text())
source = args.source.read_bytes()
assert source == (stage / 'source.yml').read_bytes(), 'Source archive differs'
assert hashlib.sha256(source).hexdigest() == summary['sourceSha256'], 'Source hash differs'
traits = collections.Counter()
fields = collections.Counter()
leaves = 0
version = summary.get('converterVersion', 1)
assert version in (1, 2, 3, 4, 5, 6, 7), 'Unsupported converter version'
remapped_locations = 0
resolved_references = 0
unresolved_references = 0

def pointer(value):
    return value.replace('~', '~0').replace('/', '~1')

def paths(value, path=None):
    if path is None:
        path = '' if version >= 2 else '$'
    if isinstance(value, dict) and value:
        return [leaf for key, child in value.items() for leaf in paths(child, path + ('/' + pointer(key) if version >= 2 else '.' + key))]
    if isinstance(value, list) and value:
        return [leaf for index, child in enumerate(value) for leaf in paths(child, path + (f'/{index}' if version >= 2 else f'[{index}]'))]
    return [path]

def remap_review(original):
    reviewed = copy.deepcopy(original)
    changes = []
    def remap(location, path):
        if not isinstance(location, dict) or not isinstance(location.get('world'), str):
            return
        before = location['world']
        after = summary['worldMap'].get(before, before)
        if before == after:
            return
        location['world'] = after
        change = {'source': path, 'from': before, 'to': after}
        if 'worldid' in location:
            change['removedWorldId'] = location.pop('worldid')
        changes.append(change)
    def waypoints(value, path):
        if isinstance(value, dict):
            for key, child in value.items():
                child_path = path + '/' + pointer(key)
                if key == 'location': remap(child, child_path)
                else: waypoints(child, child_path)
        elif isinstance(value, list):
            for index, child in enumerate(value): waypoints(child, path + '/' + str(index))
    remap(reviewed.get('location'), '/traits/location')
    waypoints(reviewed.get('waypoints'), '/traits/waypoints')
    if isinstance(reviewed.get('sentinel'), dict):
        for name in ('spawnPoint', 'avoidReturnPoint'):
            remap(reviewed['sentinel'].get(name), '/traits/sentinel/' + name)
    if isinstance(reviewed.get('sittrait'), dict):
        remap(reviewed['sittrait'].get('sittingAt'), '/traits/sittrait/sittingAt')
    reviewed.pop('skintrait', None)
    statistics = {}
    if isinstance(reviewed.get('sentinel'), dict):
        for field in list(reviewed['sentinel']):
            if field.startswith('stats_'): statistics[field] = reviewed['sentinel'].pop(field)
    return reviewed, changes, statistics

records = sorted((stage / 'records').glob('*.json'))
assert len(records) == summary['records'] == args.expected_records, 'Record count differs'
for record in records:
    original = json.loads(record.read_text())
    key = record.stem
    located = [name for name in summary['documents'] if name.startswith('npcs/') and name.endswith(f'/citizens-{key}.json')]
    assert len(located) == 1, f'Expected one definition file for record {key}'
    candidate = json.loads((stage / located[0]).read_text())
    if version >= 7:
        assert located[0] == f"npcs/{candidate['group']}/citizens-{key}.json", f'Definition folder differs from its group in record {key}'
    report = json.loads((stage / f'reports/{key}.json').read_text())
    expected = paths(original)
    assert len(set(expected)) == len(expected), f'Ambiguous field paths in record {key}'
    assert set(expected) == set(report['fields']), f'Incomplete field report for record {key}'
    assert not report['validationErrors'], f'Invalid candidate {key}'
    leaves += len(expected)
    fields.update(report['fields'].values())
    original_traits = original.get('traits', {})
    traits.update(original_traits.keys())
    if version >= 2:
        reviewed, remaps, statistics = remap_review(original_traits)
        assert candidate['modules']['citizens:review'] == reviewed, f'Trait review transformation mismatch in record {key}'
        assert report['worldRemaps'] == remaps, f'World remap report mismatch in record {key}'
        remapped_locations += len(remaps)
        assert candidate['migration']['references'] == report['references'], f'Reference report mismatch in record {key}'
        for reference in report['references']:
            status = reference['status']
            if status == 'resolved':
                resolved_references += 1
                target = str(reference['citizensId'])
                assert summary['idMap'][target] == reference['npcUuid'], f'Reference identity mismatch for {key}'
                assert reference['npcId'] == 'citizens-' + target, f'Reference readable ID mismatch for {key}'
            elif status != 'none': unresolved_references += 1
    else:
        assert candidate['modules']['citizens:preserved'] == {k: v for k, v in original_traits.items() if k != 'skintrait'}, f'Trait preservation mismatch in record {key}'
    if 'skintrait' in original_traits:
        assert json.loads((stage / f"skins/{candidate['skin']}.json").read_text()) == original_traits['skintrait'], f'Skin mismatch in record {key}'
    assert candidate['uuid'] == summary['idMap'][key], f'Identity map mismatch for {key}'
    assert candidate['spawned'] is False, f'Candidate {key} is not dormant'
    if 'sentinel' in original_traits:
        combat = candidate['modules']['animus:combat']
        assert combat['enabled'] is False and combat['reviewAccepted'] is False, f'Combat enabled without review in record {key}'
        if version >= 3:
            assert candidate['modules']['citizens:review']['sentinel'] == reviewed['sentinel'], f'Sentinel settings mismatch in record {key}'
            assert not set(combat['preservedSettings']) & set(statistics), f'Sentinel history mixed into settings in record {key}'
            assert combat['historicalStatistics'] == statistics, f'Sentinel history mismatch in record {key}'
        elif version >= 2:
            assert combat['preservedSettings'] == reviewed['sentinel'], f'Sentinel settings mismatch in record {key}'
            assert combat['historicalStatistics'] == statistics, f'Sentinel history mismatch in record {key}'
        else:
            assert combat['preservedSettings'] == original_traits['sentinel'], f'Sentinel mismatch in record {key}'
if version >= 2:
    assert remapped_locations == summary['remappedLocations'], 'Remap total differs'
    assert resolved_references == summary['resolvedReferences'], 'Resolved reference count differs'
    assert unresolved_references == summary['unresolvedReferences'], 'Unresolved reference count differs'
assert dict(traits) == summary['traits'], 'Trait inventory differs'
for name, digest in summary['documents'].items():
    path = (stage / name).resolve()
    assert path.is_relative_to(stage), 'Manifest path leaves the staged directory'
    assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, f'Manifest hash mismatch for {name}'
print(json.dumps({'converterVersion': version, 'remappedLocations': remapped_locations, 'resolvedReferences': resolved_references, 'unresolvedReferences': unresolved_references, 'records': len(records), 'classifiedLeafFields': leaves, 'classifications': dict(fields), 'distinctTraits': len(traits), 'sourceBytesPreserved': True, 'allManifestHashesMatch': True, 'allOriginalTraitsPreserved': True}, indent=2))
