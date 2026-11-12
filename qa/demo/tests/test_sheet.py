from __future__ import annotations

import json
import sys
import tempfile
import unittest
import zlib
from pathlib import Path
from typing import Any

DEMO: Path = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DEMO))
import sheet  # noqa: E402


def minimal(**changes: Any) -> dict[str, Any]:
    data: dict[str, Any] = {
        'id': '99-sample',
        'title': 'Sample',
        'description': 'Opening the palette',
        'target': 20,
        'stage': {'world': 'town'},
        'beats': [{'palette': True}, {'click': 'Edit'}, {'result': 'Edit screen lists the fields'}],
        'closing': 'The Edit screen is open',
    }
    data.update(changes)
    return data


def write(folder: Path, data: dict[str, Any], name: str | None = None) -> Path:
    path: Path = folder / f"{name or data['id']}.json"
    path.write_text(json.dumps(data))
    return path


def problems_of(data: dict[str, Any], name: str | None = None) -> list[str]:
    with tempfile.TemporaryDirectory() as tmp:
        loaded: sheet.Sheet = sheet.load(write(Path(tmp), data, name))
        return sheet.problems(loaded, {loaded.id})


class LoadAndValidate(unittest.TestCase):
    def test_minimal_sheet_is_valid(self) -> None:
        self.assertEqual([], problems_of(minimal()))

    def test_id_must_match_the_file_name(self) -> None:
        found: list[str] = problems_of(minimal(), name='98-other')
        self.assertTrue(any('file name' in p for p in found), found)

    def test_required_fields(self) -> None:
        data: dict[str, Any] = minimal()
        del data['closing']
        del data['title']
        found: list[str] = problems_of(data)
        self.assertTrue(any('closing' in p for p in found), found)
        self.assertTrue(any('title' in p for p in found), found)

    def test_unknown_beat_is_reported_with_its_place(self) -> None:
        found: list[str] = problems_of(minimal(beats=[{'teleport': [0, 0, 0]}, {'result': 'x'}]))
        self.assertTrue(any('beats[0]' in p and 'teleport' in p for p in found), found)

    def test_park_option_no_longer_exists(self) -> None:
        found: list[str] = problems_of(minimal(beats=[{'click': 'Edit', 'park': False}, {'result': 'x'}]))
        self.assertTrue(any('park' in p for p in found), found)

    def test_beat_with_two_actions_is_refused(self) -> None:
        found: list[str] = problems_of(minimal(beats=[{'click': 'Edit', 'hover': 'Body'}, {'result': 'x'}]))
        self.assertTrue(any('beats[0]' in p for p in found), found)

    def test_result_hold_must_be_one_and_a_half_to_two_and_a_half_seconds(self) -> None:
        self.assertTrue(problems_of(minimal(beats=[{'result': 'reply', 'ms': 900}])))
        self.assertTrue(problems_of(minimal(beats=[{'result': 'reply', 'ms': 4000}])))
        self.assertEqual([], problems_of(minimal(beats=[{'result': 'reply', 'ms': 1500}])))
        self.assertEqual([], problems_of(minimal(beats=[{'result': 'reply', 'ms': 2500}])))

    def test_every_clip_shows_at_least_one_result(self) -> None:
        found: list[str] = problems_of(minimal(beats=[{'palette': True}]))
        self.assertTrue(any('result' in p for p in found), found)

    def test_click_rest_must_stay_short(self) -> None:
        self.assertTrue(problems_of(minimal(pace={'clickRest': 100})))
        self.assertTrue(problems_of(minimal(pace={'clickRest': 1200})))
        self.assertEqual([], problems_of(minimal(pace={'clickRest': 450})))

    def test_tp_needs_five_numbers(self) -> None:
        self.assertTrue(problems_of(minimal(setup=[{'tp': [1, 2, 3]}])))
        self.assertEqual([], problems_of(minimal(setup=[{'tp': [1, 64, 3, 90, 5]}])))

    def test_world_must_be_known(self) -> None:
        self.assertTrue(problems_of(minimal(stage={'world': 'nether'})))

    def test_cues_must_be_declared_and_used(self) -> None:
        undeclared: list[str] = problems_of(minimal(beats=[{'cue': 'wheat'}, {'result': 'x'}]))
        self.assertTrue(any('wheat' in p for p in undeclared), undeclared)
        unused: list[str] = problems_of(minimal(cues={'husk': ['summon husk']}))
        self.assertTrue(any('husk' in p for p in unused), unused)
        self.assertEqual([], problems_of(minimal(cues={'wheat': ['give {player} wheat 5']}, beats=[{'cue': 'wheat'}, {'result': 'x'}])))

    def test_after_must_name_a_known_sheet(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            folder: Path = Path(tmp)
            write(folder, minimal(stage={'world': 'town', 'after': ['06-new-npc']}))
            sheets, found = sheet.load_all(folder)
            self.assertIn('99-sample', sheets)
            self.assertTrue(any('06-new-npc' in p for p in found), found)

    def test_load_all_reports_bad_json(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / '01-broken.json').write_text('{')
            _, found = sheet.load_all(Path(tmp))
            self.assertTrue(any('01-broken' in p for p in found), found)

    def test_stage_files_stay_inside_plugins(self) -> None:
        self.assertTrue(problems_of(minimal(stage={'world': 'flat', 'files': {'../server.properties': 'x'}})))
        self.assertTrue(problems_of(minimal(stage={'world': 'flat', 'files': {'Animus/x.json': {'from': 'stage/missing.bbmodel'}}})))
        self.assertEqual([], problems_of(minimal(stage={'world': 'flat', 'files': {'Animus/behaviors/live/hits.json': {'id': 'live:hits'}}})))

    def test_stage_files_can_remove_kit_files(self) -> None:
        self.assertEqual([], problems_of(minimal(stage={'world': 'town', 'files': {'Animus/npcs': None}})))

    def test_client_options_are_text(self) -> None:
        self.assertEqual([], problems_of(minimal(client={'options': {'chatWidth': '1.0'}})))
        self.assertTrue(problems_of(minimal(client={'options': {'chatWidth': 1.0}})))
        self.assertTrue(problems_of(minimal(client={'mods': []})))

    def test_client_packs_name_a_zip_under_plugins(self) -> None:
        self.assertEqual([], problems_of(minimal(client={'packs': {'models.zip': 'ModelEngine/resource pack.zip'}})))
        self.assertTrue(problems_of(minimal(client={'packs': {'models': 'ModelEngine/resource pack.zip'}})))
        self.assertTrue(problems_of(minimal(client={'packs': {'a/models.zip': 'ModelEngine/pack.zip'}})))
        self.assertTrue(problems_of(minimal(client={'packs': {'models.zip': '../server.properties'}})))
        self.assertTrue(problems_of(minimal(client={'packs': {'models.zip': '/tmp/pack.zip'}})))
        self.assertTrue(problems_of(minimal(client={'packs': ['models.zip']})))

    def test_platform_must_be_known(self) -> None:
        self.assertTrue(problems_of(minimal(stage={'world': 'flat', 'platform': 'forge'})))
        self.assertEqual([], problems_of(minimal(stage={'world': 'flat', 'platform': 'fabric'})))

    def test_notes_are_allowed_on_any_beat(self) -> None:
        self.assertEqual([], problems_of(minimal(beats=[{'click': 'Edit', 'note': 'opens Edit'}, {'result': 'x'}])))


class Compile(unittest.TestCase):
    def compiled(self, **changes: Any) -> list[dict[str, Any]]:
        with tempfile.TemporaryDirectory() as tmp:
            loaded: sheet.Sheet = sheet.load(write(Path(tmp), minimal(**changes)))
            return sheet.compile_script(loaded, Path('/clips/99-sample-ingame.webm'))

    def test_script_frame(self) -> None:
        steps: list[dict[str, Any]] = self.compiled()
        self.assertEqual({'waitForWorld': True, 'timeout': 180000}, steps[0])
        self.assertEqual({'settings': {'seed': zlib.crc32(b'99-sample'), 'clickRest': sheet.CLICK_REST_MS}}, steps[1])
        self.assertEqual({'record': 'start', 'file': '/clips/99-sample-ingame.webm'}, steps[2])
        self.assertEqual([
            {'caption': 'The Edit screen is open', 'ms': sheet.CLOSING_MS},
            {'wait': sheet.CLOSING_MS},
            {'record': 'stop'},
            {'disconnect': True},
            {'quit': True},
        ], steps[-5:])

    def test_setup_runs_before_recording(self) -> None:
        steps: list[dict[str, Any]] = self.compiled(setup=[{'run': '/gamemode creative'}])
        self.assertEqual({'chat': '/gamemode creative', 'typing': False}, steps[2])
        self.assertEqual('start', steps[3]['record'])

    def test_seed_and_pace_from_the_sheet(self) -> None:
        steps: list[dict[str, Any]] = self.compiled(seed=12, pace={'clickRest': 500})
        self.assertEqual({'settings': {'seed': 12, 'clickRest': 500}}, steps[1])

    def test_macros_expand(self) -> None:
        steps: list[dict[str, Any]] = self.compiled(beats=[
            {'tp': [0.5, 64, -5.5, 104.1, 5]},
            {'type': '/animus inspect trader'},
            {'palette': True},
            {'click': 'Behaviors', 'button': 'right', 'rest': 600},
            {'clickSlot': 8},
            {'hover': 'Line 1', 'ms': 1800},
            {'hoverSlot': 10},
            {'anvil': 'plaza:welcome'},
            {'screen': 'Anvil'},
            {'expect': 'Saved'},
            {'result': 'Saved line', 'ms': 2200},
            {'cue': 'wheat'},
            {'perspective': 'first', 'note': 'back to the eyes'},
        ], cues={'wheat': ['give {player} minecraft:wheat 5']})
        body: list[dict[str, Any]] = steps[3:-5]
        self.assertEqual([
            {'chat': '/tp @s 0.5 64 -5.5 104.1 5', 'typing': False},
            {'chat': '/animus inspect trader', 'typing': True},
            {'chat': '/animus tools', 'typing': True},
            {'waitForScreen': 'Animus', 'timeout': 8000},
            {'wait': 350},
            {'clickSlotNamed': 'Behaviors', 'button': 'right', 'rest': 600},
            {'clickSlot': 8},
            {'hoverSlotNamed': 'Line 1', 'ms': 1800},
            {'hoverSlot': 10, 'ms': sheet.HOVER_MS},
            {'anvilText': 'plaza:welcome'},
            {'waitForScreen': 'Anvil', 'timeout': 8000},
            {'waitForChat': 'Saved', 'timeout': 8000},
            {'log': 'Result: Saved line'},
            {'wait': 2200},
            {'chat': '/execute as @s[tag=!cue_wheat] if entity @s', 'typing': False},
            {'perspective': 'first'},
        ], body)

    def test_placeholders_are_filled_but_json_braces_kept(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            loaded: sheet.Sheet = sheet.load(write(Path(tmp), minimal(beats=[
                {'type': '/animus edit greeter {"name":"Greeter"}'},
                {'run': '/give {player} minecraft:bread'},
                {'join': '{address}'},
                {'result': 'x'},
            ])))
            steps: list[dict[str, Any]] = sheet.compile_script(loaded, Path('/c.webm'), {'player': 'Steve', 'address': '127.0.0.1:25640'})
        self.assertIn({'chat': '/animus edit greeter {"name":"Greeter"}', 'typing': True}, steps)
        self.assertIn({'chat': '/give Steve minecraft:bread', 'typing': False}, steps)
        self.assertIn({'join': '127.0.0.1:25640'}, steps)

    def test_title_screen_clip_waits_for_the_menu(self) -> None:
        steps: list[dict[str, Any]] = self.compiled(stage={'world': 'town', 'titleScreen': True})
        self.assertEqual({'waitForScreen': 'TitleScreen', 'timeout': 180000}, steps[0])

    def test_prep_runs_the_clip_without_recording_or_quitting(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            loaded: sheet.Sheet = sheet.load(write(Path(tmp), minimal(setup=[{'run': '/gamemode creative'}])))
            steps: list[dict[str, Any]] = sheet.compile_prep(loaded)
        keys: set[str] = {k for step in steps for k in step}
        self.assertFalse(keys & {'record', 'quit', 'disconnect', 'caption'}, keys)
        self.assertEqual({'chat': '/gamemode creative', 'typing': False}, steps[2])
        self.assertEqual({'closeScreen': True}, steps[-1])

    def test_result_defaults_to_two_seconds(self) -> None:
        steps: list[dict[str, Any]] = self.compiled()
        self.assertIn({'wait': 2000}, steps)

    def test_compiled_script_is_json(self) -> None:
        json.dumps(self.compiled())


class Estimate(unittest.TestCase):
    def test_waits_add_up(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            short: sheet.Sheet = sheet.load(write(Path(tmp), minimal()))
            longer: sheet.Sheet = sheet.load(write(Path(tmp), minimal(beats=minimal()['beats'] + [{'wait': 3000}])))
            self.assertAlmostEqual(sheet.estimate_seconds(short) + 3.0, sheet.estimate_seconds(longer), places=3)
            self.assertGreater(sheet.estimate_seconds(short), sheet.CLOSING_MS / 1000.0)


class TrackedSheets(unittest.TestCase):
    def test_every_tracked_sheet_is_valid(self) -> None:
        sheets, found = sheet.load_all(DEMO / 'sheets')
        self.assertEqual([], found)
        self.assertGreaterEqual(len(sheets), 43)


if __name__ == '__main__':
    unittest.main()
