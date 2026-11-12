from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import clip_check  # noqa: E402

PROBE: str = '''{"streams": [{"codec_name": "vp9", "width": 1920, "height": 1080}],
 "format": {"duration": "41.233000", "size": "2114996"}}'''


class ClipCheck(unittest.TestCase):
    def test_reads_ffprobe_json(self) -> None:
        facts: clip_check.Facts = clip_check.parse(PROBE)
        self.assertEqual(clip_check.Facts('vp9', 1920, 1080, 41.233, 2114996), facts)

    def test_good_clip_has_no_problems(self) -> None:
        self.assertEqual([], clip_check.problems(clip_check.parse(PROBE), target=40.0))

    def test_wrong_codec_size_or_length_are_problems(self) -> None:
        facts: clip_check.Facts = clip_check.Facts('h264', 1280, 720, 12.0, 100)
        found: list[str] = clip_check.problems(facts, target=40.0)
        self.assertTrue(any('vp9' in p for p in found), found)
        self.assertTrue(any('1920x1080' in p for p in found), found)
        self.assertTrue(any('target' in p for p in found), found)

    def test_length_within_a_third_of_the_target_passes(self) -> None:
        self.assertEqual([], clip_check.problems(clip_check.Facts('vp9', 1920, 1080, 50.0, 1), target=40.0))
        self.assertTrue(clip_check.problems(clip_check.Facts('vp9', 1920, 1080, 60.0, 1), target=40.0))


if __name__ == '__main__':
    unittest.main()
