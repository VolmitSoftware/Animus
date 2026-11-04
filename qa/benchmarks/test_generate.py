import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('benchmark_generate', Path(__file__).with_name('generate.py'))
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class WorkloadTests(unittest.TestCase):
    def test_all_baselines_share_synthetic_identities_locations_and_spawn_counts(self):
        with tempfile.TemporaryDirectory() as temporary:
            for case in generator.CASES:
                output = Path(temporary) / case
                manifest = generator.generate(output, case)
                canonical = json.loads((output / 'workload.json').read_text())['npcs']
                self.assertEqual(len({n['uuid'] for n in canonical}), len(canonical))
                for npc in canonical:
                    saved = json.loads((output / 'animus/npcs/benchmark' / f"{npc['id']}.json").read_text())
                    self.assertEqual(saved, npc)
                baseline = json.loads((output / 'no-plugin/expectations.json').read_text())
                self.assertEqual(baseline['expectedNpcCount'], 0)
                self.assertEqual(baseline['viewers'], len(manifest['viewers']))
                visible = [uuid for viewer in manifest['viewers'] for uuid in viewer['expectedNpcUuids']]
                self.assertEqual(set(visible), {n['uuid'] for n in canonical if n['spawned']})
                self.assertEqual(len(visible), manifest['activeCount'])
                self.assertTrue(all(v['expectedNpcCount'] <= 128 and v['animusReservedEntityIds'] <= 256 for v in manifest['viewers']))
                for file, expected in manifest['files'].items():
                    self.assertEqual(hashlib.sha256((output / file).read_bytes()).hexdigest(), expected)
                if case == 'dormant-5000':
                    self.assertEqual((manifest['definitionCount'], manifest['activeCount'], manifest['dormantCount']), (5000, 64, 4936))

    def test_generation_is_byte_reproducible_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as temporary:
            one, two = Path(temporary) / 'one', Path(temporary) / 'two'
            generator.generate(one, 'reference-495')
            generator.generate(two, 'reference-495')
            self.assertEqual((one / 'manifest.json').read_bytes(), (two / 'manifest.json').read_bytes())
            with self.assertRaises(FileExistsError):
                generator.generate(one, 'reference-495')
            first, _ = generator.records('dense-32', 'seed-a', 'world')
            changed, _ = generator.records('dense-32', 'seed-b', 'world')
            self.assertNotEqual(first[0]['uuid'], changed[0]['uuid'])
            self.assertEqual(first[0]['position'], changed[0]['position'])


if __name__ == '__main__':
    unittest.main()
