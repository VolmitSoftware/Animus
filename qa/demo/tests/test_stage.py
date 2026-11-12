from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import stage  # noqa: E402


class Platforms(unittest.TestCase):
    def test_paper_sheet_files_stay_under_plugins(self) -> None:
        self.assertEqual('plugins/Animus/quests/live/rescue.json', stage.content_path('paper', 'Animus/quests/live/rescue.json'))
        self.assertEqual('plugins/ModelEngine/blueprints/a.bbmodel', stage.content_path('paper', 'ModelEngine/blueprints/a.bbmodel'))

    def test_fabric_animus_files_go_to_the_config_folder(self) -> None:
        self.assertEqual('config/animus/quests/live/rescue.json', stage.content_path('fabric', 'Animus/quests/live/rescue.json'))
        self.assertEqual('config/animus', stage.content_path('fabric', 'Animus'))
        self.assertEqual('mods/AnimusExtra/x.json', stage.content_path('fabric', 'AnimusExtra/x.json'))

    def test_fabric_support_jars_keep_apart_from_paper_ones(self) -> None:
        folder: Path = Path('/tmp/plugins')
        self.assertEqual(folder, stage.support_folder(folder, 'paper'))
        self.assertEqual(folder / 'fabric', stage.support_folder(folder, 'fabric'))
        self.assertIn('viafabric', [project for project, _ in stage.SUPPORT['fabric']])
        self.assertIn('fabric-api', [project for project, _ in stage.SUPPORT['fabric']])

    def test_each_platform_has_its_own_instance_session_and_cache(self) -> None:
        paper: stage.Stage = stage.Stage(Path('/tmp/cache'), 'Steve', 0, 'paper', Path('/tmp/mux'))
        fabric: stage.Stage = stage.Stage(Path('/tmp/cache'), 'Steve', 0, 'fabric', Path('/tmp/mux'))
        self.assertEqual('animus-clips-fabric-26-3', fabric.name)
        self.assertEqual('mc-fabric-animus-clips-fabric-26-3', fabric.session)
        self.assertEqual(f'mc-plugin-{paper.name}', paper.session)
        self.assertNotEqual(paper.cache, fabric.cache)


if __name__ == '__main__':
    unittest.main()
