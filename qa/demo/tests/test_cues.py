from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import demo  # noqa: E402


class Cues(unittest.TestCase):
    def test_each_cue_fires_once_in_log_order(self) -> None:
        log: str = ('[INFO] Steve issued server command: /execute as @s[tag=!cue_wheat] if entity @s\n'
                    '[INFO] Steve issued server command: /execute as @s[tag=!cue_husk] if entity @s\n'
                    '[INFO] Steve issued server command: /execute as @s[tag=!cue_wheat] if entity @s\n')
        fired: set[str] = set()
        self.assertEqual(['wheat', 'husk'], demo.new_cues(log, {'wheat', 'husk'}, fired))
        self.assertEqual([], demo.new_cues(log, {'wheat', 'husk'}, fired))

    def test_unknown_cues_are_ignored(self) -> None:
        self.assertEqual([], demo.new_cues('cue_other', {'wheat'}, set()))


if __name__ == '__main__':
    unittest.main()
