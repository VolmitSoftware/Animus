from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

DEMO: Path = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DEMO))
import client  # noqa: E402


class PackTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp: tempfile.TemporaryDirectory[str] = tempfile.TemporaryDirectory()
        root: Path = Path(self.tmp.name)
        self.client: client.Client = client.Client(root / 'state')
        self.client.root = root / 'instance'
        self.client.game = self.client.root / 'minecraft'
        self.client.camera = self.client.game / 'config' / 'animuscamera'
        self.packs: Path = self.client.game / 'resourcepacks'
        self.packs.mkdir(parents=True)
        (self.packs / 'Own.zip').write_bytes(b'own')
        backup: Path = root / 'state' / 'client-backup' / 'minecraft' / 'options.txt'
        backup.parent.mkdir(parents=True)
        backup.write_text('resourcePacks:["vanilla","file/Own.zip"]\n')
        self.client.changes.write_text(json.dumps({'disabled': [], 'mod': '', 'backups': {'minecraft/options.txt': str(backup)}, 'packs': []}))
        self.source: Path = root / 'stage' / 'plugins' / 'ModelEngine' / 'resource pack.zip'
        self.source.parent.mkdir(parents=True)
        self.source.write_bytes(b'models')

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_pack_is_copied_and_listed_after_the_instance_packs(self) -> None:
        option: dict[str, str] = self.client.use_packs({'models.zip': self.source})
        self.assertEqual(b'models', (self.packs / 'models.zip').read_bytes())
        self.assertEqual(['vanilla', 'file/Own.zip', 'file/models.zip'], json.loads(option['resourcePacks']))

    def test_next_clip_without_packs_removes_it(self) -> None:
        self.client.use_packs({'models.zip': self.source})
        option: dict[str, str] = self.client.use_packs({})
        self.assertFalse((self.packs / 'models.zip').exists())
        self.assertTrue((self.packs / 'Own.zip').exists())
        self.assertEqual(['vanilla', 'file/Own.zip'], json.loads(option['resourcePacks']))

    def test_restore_removes_added_packs_only(self) -> None:
        self.client.use_packs({'models.zip': self.source})
        self.client.restore()
        self.assertEqual(['Own.zip'], sorted(p.name for p in self.packs.iterdir()))

    def test_missing_source_is_reported(self) -> None:
        with self.assertRaises(FileNotFoundError):
            self.client.use_packs({'models.zip': self.source.with_name('none.zip')})


if __name__ == '__main__':
    unittest.main()
