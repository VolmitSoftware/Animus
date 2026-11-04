#!/usr/bin/env python3
"""Independently audit a staged Citizens JSON bundle without printing private record data."""
import argparse
import collections
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

def paths(value, path='$'):
    if isinstance(value, dict) and value:
        return [leaf for key, child in value.items() for leaf in paths(child, path + '.' + key)]
    if isinstance(value, list) and value:
        return [leaf for index, child in enumerate(value) for leaf in paths(child, path + f'[{index}]')]
    return [path]

records = sorted((stage / 'records').glob('*.json'))
assert len(records) == summary['records'] == args.expected_records, 'Record count differs'
for record in records:
    original = json.loads(record.read_text())
    key = record.stem
    candidate = json.loads((stage / f'npcs/citizens/citizens-{key}.json').read_text())
    report = json.loads((stage / f'reports/{key}.json').read_text())
    expected = paths(original)
    assert len(set(expected)) == len(expected), f'Ambiguous field paths in record {key}'
    assert set(expected) == set(report['fields']), f'Incomplete field report for record {key}'
    assert not report['validationErrors'], f'Invalid candidate {key}'
    leaves += len(expected)
    fields.update(report['fields'].values())
    original_traits = original.get('traits', {})
    traits.update(original_traits.keys())
    assert candidate['modules']['citizens:preserved'] == {k: v for k, v in original_traits.items() if k != 'skintrait'}, f'Trait preservation mismatch in record {key}'
    if 'skintrait' in original_traits:
        assert json.loads((stage / f"skins/{candidate['skin']}.json").read_text()) == original_traits['skintrait'], f'Skin mismatch in record {key}'
    assert candidate['uuid'] == summary['idMap'][key], f'Identity map mismatch for {key}'
    assert candidate['spawned'] is False, f'Candidate {key} is not dormant'
    if 'sentinel' in original_traits:
        combat = candidate['modules']['animus:combat']
        assert combat['enabled'] is False and combat['reviewAccepted'] is False, f'Combat enabled without review in record {key}'
        assert combat['preservedSettings'] == original_traits['sentinel'], f'Sentinel mismatch in record {key}'
assert dict(traits) == summary['traits'], 'Trait inventory differs'
for name, digest in summary['documents'].items():
    path = (stage / name).resolve()
    assert path.is_relative_to(stage), 'Manifest path leaves the staged directory'
    assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, f'Manifest hash mismatch for {name}'
print(json.dumps({'records': len(records), 'classifiedLeafFields': leaves, 'classifications': dict(fields), 'distinctTraits': len(traits), 'sourceBytesPreserved': True, 'allManifestHashesMatch': True, 'allOriginalTraitsPreserved': True}, indent=2))
