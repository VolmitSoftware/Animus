"""Clip sheets: one JSON file per tutorial clip, validated and compiled into an Animus Camera step script."""
from __future__ import annotations

import json
import re
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

CLICK_REST_MS: int = 400
CLICK_REST_RANGE: tuple[int, int] = (300, 800)
HOVER_MS: int = 1500
RESULT_MS: int = 2000
RESULT_RANGE: tuple[int, int] = (1500, 2500)
CLOSING_MS: int = 3000
SCREEN_TIMEOUT_MS: int = 8000
WORLDS: tuple[str, ...] = ('town', 'flat')
PLATFORMS: tuple[str, ...] = ('paper', 'fabric')
PLACEHOLDERS: tuple[str, ...] = ('{player}', '{address}')
PERSPECTIVES: tuple[str, ...] = ('first', 'third-back', 'third-front')
ID_PATTERN: re.Pattern[str] = re.compile(r'^(ux-)?\d\d[a-z]?-[a-z0-9-]+$')
# Sheets for reviewing menu changes; recorded on request, never part of the tutorial set the docs embed.
REVIEW_PREFIX: str = 'ux-'
MENU_COMMAND: str = '/animus gui'
HOME_TITLE: str = 'Animus'
TYPING_SECONDS_PER_CHAR: float = 0.05
TYPED_HOLD_SECONDS: float = 1.0

# Every beat has one action key. The value lists the extra keys that action accepts; "note" is allowed everywhere.
ACTIONS: dict[str, set[str]] = {
    'tp': set(),
    'run': set(),
    'type': {'tab', 'send', 'hold'},
    # The Animus menu: types /animus gui and waits for Home.
    'menu': set(),
    'click': {'button', 'sneak', 'rest', 'glide'},
    'clickSlot': {'button', 'sneak', 'rest', 'glide'},
    'hover': {'ms', 'glide'},
    'hoverSlot': {'ms', 'glide'},
    'anvil': set(),
    'screen': {'timeout'},
    'expect': {'timeout'},
    'result': {'ms'},
    'cue': set(),
    'wait': set(),
    'perspective': set(),
    'hotbar': set(),
    'look': set(),
    'lookAt': set(),
    'lookAtEntity': {'ms'},
    'attackTarget': {'sneak'},
    'useItem': {'sneak'},
    'sneakHold': set(),
    'sprint': set(),
    'move': {'ms'},
    'key': {'hold', 'release'},
    'closeScreen': set(),
    'clearChat': set(),
    'clickChat': {'rest', 'glide'},
    'hoverChat': {'ms', 'glide'},
    'clickButton': {'rest', 'glide'},
    'caption': {'ms'},
    'join': set(),
    'waitForWorld': {'timeout'},
    'waitForScreen': {'timeout'},
    'waitForChat': {'timeout'},
    'cursor': set(),
    'log': set(),
}
NOTE: str = 'note'


@dataclass(frozen=True)
class Sheet:
    path: Path
    data: dict[str, Any]

    @property
    def id(self) -> str:
        return str(self.data.get('id', ''))

    @property
    def title(self) -> str:
        return str(self.data.get('title', ''))

    @property
    def target(self) -> float:
        return float(self.data.get('target', 0))

    @property
    def stage(self) -> dict[str, Any]:
        return dict(self.data.get('stage', {}))

    @property
    def world(self) -> str:
        return str(self.stage.get('world', ''))

    @property
    def platform(self) -> str:
        return str(self.stage.get('platform', 'paper'))

    @property
    def title_screen(self) -> bool:
        return bool(self.stage.get('titleScreen', False))

    @property
    def resets(self) -> bool:
        return bool(self.stage.get('reset', True))

    @property
    def after(self) -> list[str]:
        return [str(v) for v in self.stage.get('after', [])]

    @property
    def plugins(self) -> list[str]:
        return [str(v) for v in self.stage.get('plugins', [])]

    @property
    def files(self) -> dict[str, Any]:
        return dict(self.stage.get('files', {}))

    @property
    def console(self) -> list[str]:
        return [str(v) for v in self.stage.get('console', [])]

    @property
    def client_options(self) -> dict[str, str]:
        return {str(k): str(v) for k, v in dict(dict(self.data.get('client', {})).get('options', {})).items()}

    @property
    def client_packs(self) -> dict[str, str]:
        """Resource packs the client loads for this clip: file name in resourcepacks/ to its source under the stage's plugins/."""
        return {str(k): str(v) for k, v in dict(dict(self.data.get('client', {})).get('packs', {})).items()}

    @property
    def cues(self) -> dict[str, list[str]]:
        return {str(k): [str(c) for c in v] for k, v in dict(self.data.get('cues', {})).items()}

    @property
    def setup(self) -> list[dict[str, Any]]:
        return list(self.data.get('setup', []))

    @property
    def beats(self) -> list[dict[str, Any]]:
        return list(self.data.get('beats', []))

    @property
    def seed(self) -> int:
        return int(self.data['seed']) if 'seed' in self.data else zlib.crc32(self.id.encode('utf-8'))

    @property
    def click_rest(self) -> int:
        return int(dict(self.data.get('pace', {})).get('clickRest', CLICK_REST_MS))

    @property
    def tutorial(self) -> bool:
        """Whether the sheet belongs to the tutorial set the docs embed, which record --all records."""
        return not self.id.startswith(REVIEW_PREFIX)

    @property
    def output_name(self) -> str:
        return f'{self.id}-ingame.webm'


def load(path: Path) -> Sheet:
    data: Any = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(data, dict):
        raise ValueError(f'{path.name}: a sheet is a JSON object')
    return Sheet(path, data)


def load_all(folder: Path) -> tuple[dict[str, Sheet], list[str]]:
    """Loads every sheet in the folder. Returns the sheets by id and every problem found across them."""
    sheets: dict[str, Sheet] = {}
    found: list[str] = []
    for path in sorted(folder.glob('*.json')):
        try:
            loaded: Sheet = load(path)
        except (ValueError, json.JSONDecodeError) as error:
            found.append(f'{path.stem}: cannot read: {error}')
            continue
        if loaded.id in sheets:
            found.append(f'{path.stem}: id {loaded.id} is used twice')
        sheets[loaded.id] = loaded
    known: set[str] = set(sheets)
    for loaded in sheets.values():
        found.extend(problems(loaded, known))
    return sheets, found


def problems(loaded: Sheet, known_ids: set[str]) -> list[str]:
    data: dict[str, Any] = loaded.data
    name: str = loaded.path.stem
    found: list[str] = []

    def problem(text: str) -> None:
        found.append(f'{name}: {text}')

    for key in ('id', 'title', 'description', 'closing'):
        if not isinstance(data.get(key), str) or not data[key].strip():
            problem(f'{key} must be a non-empty string')
    if loaded.id and loaded.id != name:
        problem(f'id {loaded.id} does not match the file name')
    if loaded.id and not ID_PATTERN.match(loaded.id):
        problem(f'id {loaded.id} must look like 04-palette or ux-01-first-menu')
    target: Any = data.get('target')
    if not isinstance(target, (int, float)) or not 5 <= target <= 90:
        problem('target must be the intended length in seconds (5 to 90)')
    known_keys: set[str] = {'id', 'title', 'description', 'target', 'stage', 'client', 'cues', 'pace', 'seed', 'setup', 'beats', 'closing'}
    for key in sorted(set(data) - known_keys):
        problem(f'unknown top-level key {key}')

    stage: Any = data.get('stage')
    if not isinstance(stage, dict):
        problem('stage must be an object')
        stage = {}
    if stage.get('world') not in WORLDS:
        problem(f'stage.world must be one of {", ".join(WORLDS)}')
    for key in sorted(set(stage) - {'world', 'platform', 'titleScreen', 'reset', 'after', 'plugins', 'files', 'console'}):
        problem(f'unknown stage key {key}')
    if stage.get('platform', 'paper') not in PLATFORMS:
        problem(f'stage.platform must be one of {", ".join(PLATFORMS)}')
    for key in ('titleScreen', 'reset'):
        if key in stage and not isinstance(stage[key], bool):
            problem(f'stage.{key} must be true or false')
    for other in stage.get('after', []):
        if other not in known_ids:
            problem(f'stage.after names {other}, which has no sheet')
    for key in ('plugins', 'console'):
        if not all(isinstance(v, str) and v for v in stage.get(key, [])):
            problem(f'stage.{key} must be a list of strings')
    files: Any = stage.get('files', {})
    if not isinstance(files, dict):
        problem('stage.files must map a path under plugins/ to its content')
        files = {}
    for target, content in files.items():
        if target.startswith('/') or '..' in Path(target).parts:
            problem(f'stage.files path {target} must stay inside plugins/')
        if isinstance(content, dict) and set(content) == {'from'}:
            if not (loaded.path.parent.parent / str(content['from'])).is_file():
                problem(f'stage.files {target} copies {content["from"]}, which does not exist')
        elif content is not None and not isinstance(content, (dict, list, str)):
            problem(f'stage.files {target} must be text, JSON, {{"from": "<file under qa/demo>"}}, or null to remove it')

    client: Any = data.get('client', {})
    if not isinstance(client, dict) or set(client) - {'options', 'packs'} or not all(
            isinstance(k, str) and isinstance(v, str) for k, v in dict(client.get('options', {})).items()):
        problem('client only takes options, a map of options.txt keys to values written as text, and packs')
    elif not isinstance(client.get('packs', {}), dict) or not all(
            isinstance(k, str) and re.fullmatch(r'[A-Za-z0-9_.-]+\.zip', k) and isinstance(v, str) and v
            and not Path(v).is_absolute() and '..' not in Path(v).parts
            for k, v in dict(client.get('packs', {})).items()):
        problem('client.packs maps a pack file name (name.zip) to its path under the stage\'s plugins/')

    pace: Any = data.get('pace', {})
    if not isinstance(pace, dict) or set(pace) - {'clickRest'}:
        problem('pace only takes clickRest')
    elif 'clickRest' in pace and not (isinstance(pace['clickRest'], int) and CLICK_REST_RANGE[0] <= pace['clickRest'] <= CLICK_REST_RANGE[1]):
        problem(f'pace.clickRest must be {CLICK_REST_RANGE[0]} to {CLICK_REST_RANGE[1]} ms')
    if 'seed' in data and not isinstance(data['seed'], int):
        problem('seed must be an integer')

    cues: Any = data.get('cues', {})
    if not isinstance(cues, dict) or not all(isinstance(v, list) and v and all(isinstance(c, str) for c in v) for v in cues.values()):
        problem('cues must map a name to a list of console commands')
        cues = {}
    used_cues: set[str] = set()

    for section in ('setup', 'beats'):
        beats: Any = data.get(section, [])
        if not isinstance(beats, list):
            problem(f'{section} must be a list')
            continue
        for index, beat in enumerate(beats):
            where: str = f'{section}[{index}]'
            for text in _beat_problems(beat):
                problem(f'{where}: {text}')
            if isinstance(beat, dict) and 'cue' in beat:
                used_cues.add(str(beat['cue']))
                if beat['cue'] not in cues:
                    problem(f'{where}: cue {beat["cue"]} is not declared in cues')
    if not isinstance(data.get('beats'), list) or not data.get('beats'):
        problem('beats must list what is recorded')
    elif not any(isinstance(b, dict) and 'result' in b for b in data['beats']):
        problem('beats must hold at least one result on screen')
    for cue in sorted(set(cues) - used_cues):
        problem(f'cue {cue} is declared but never used')
    return found


def _beat_problems(beat: Any) -> list[str]:
    if not isinstance(beat, dict):
        return ['a beat is an object']
    actions: list[str] = [k for k in beat if k in ACTIONS]
    if len(actions) != 1:
        unknown: list[str] = [k for k in beat if k not in ACTIONS and k != NOTE]
        if not actions and unknown:
            return [f'unknown action {", ".join(unknown)}']
        return [f'a beat has exactly one action, found {", ".join(actions) or "none"}']
    action: str = actions[0]
    extra: set[str] = set(beat) - {action, NOTE} - ACTIONS[action]
    found: list[str] = [f'{action} does not take {key}' for key in sorted(extra)]
    value: Any = beat[action]
    check: Callable[[Any, dict[str, Any]], str | None] | None = _VALUE_CHECKS.get(action)
    if check is not None:
        text: str | None = check(value, beat)
        if text:
            found.append(text)
    for key in ('ms', 'rest', 'glide', 'hold', 'timeout'):
        if key in beat and not (isinstance(beat[key], int) and beat[key] >= 0):
            found.append(f'{key} must be a whole number of milliseconds')
    if 'button' in beat and beat['button'] not in ('left', 'right'):
        found.append('button must be left or right')
    if NOTE in beat and not isinstance(beat[NOTE], str):
        found.append('note must be text')
    return found


def _text(value: Any, _: dict[str, Any]) -> str | None:
    return None if isinstance(value, str) and value else 'needs text'


def _answer(value: Any, _: dict[str, Any]) -> str | None:
    """An anvil answer may be empty: typing nothing resets a number or a height to its default."""
    return None if isinstance(value, str) else 'needs text, empty to type nothing'


def _command(value: Any, _: dict[str, Any]) -> str | None:
    return None if isinstance(value, str) and value.startswith('/') else 'needs a command starting with /'


def _slot(value: Any, _: dict[str, Any]) -> str | None:
    return None if isinstance(value, int) and value >= 0 else 'needs a slot index'


def _true(value: Any, _: dict[str, Any]) -> str | None:
    return None if value is True else 'takes true'


def _flag(value: Any, _: dict[str, Any]) -> str | None:
    return None if isinstance(value, bool) else 'takes true or false'


def _millis(value: Any, _: dict[str, Any]) -> str | None:
    return None if isinstance(value, int) and value >= 0 else 'needs milliseconds'


def _tp(value: Any, _: dict[str, Any]) -> str | None:
    if isinstance(value, list) and len(value) == 5 and all(isinstance(v, (int, float)) for v in value):
        return None
    return 'tp takes [x, y, z, yaw, pitch]'


def _result(value: Any, beat: dict[str, Any]) -> str | None:
    if not isinstance(value, str) or not value:
        return 'result names what the viewer reads'
    hold: Any = beat.get('ms', RESULT_MS)
    if not isinstance(hold, int) or not RESULT_RANGE[0] <= hold <= RESULT_RANGE[1]:
        return f'result holds {RESULT_RANGE[0]} to {RESULT_RANGE[1]} ms'
    return None


def _perspective(value: Any, _: dict[str, Any]) -> str | None:
    return None if value in PERSPECTIVES else f'perspective is one of {", ".join(PERSPECTIVES)}'


def _hotbar(value: Any, _: dict[str, Any]) -> str | None:
    return None if isinstance(value, int) and 0 <= value <= 8 else 'hotbar is 0 to 8'


def _look(value: Any, _: dict[str, Any]) -> str | None:
    return None if isinstance(value, dict) and {'yaw', 'pitch'} <= set(value) else 'look takes {yaw, pitch, ms}'


def _look_at(value: Any, _: dict[str, Any]) -> str | None:
    return None if isinstance(value, dict) and {'x', 'y', 'z'} <= set(value) else 'lookAt takes {x, y, z, ms}'


def _move(value: Any, _: dict[str, Any]) -> str | None:
    return None if value in ('forward', 'back', 'left', 'right') else 'move is forward, back, left or right'


def _cursor(value: Any, _: dict[str, Any]) -> str | None:
    return None if value in ('show', 'hide') else 'cursor is show or hide'


_VALUE_CHECKS: dict[str, Callable[[Any, dict[str, Any]], str | None]] = {
    'tp': _tp, 'run': _command, 'type': _text, 'menu': _true, 'click': _text, 'clickSlot': _slot,
    'hover': _text, 'hoverSlot': _slot, 'anvil': _answer, 'screen': _text, 'expect': _text, 'result': _result,
    'cue': _text, 'wait': _millis, 'perspective': _perspective, 'hotbar': _hotbar, 'look': _look, 'lookAt': _look_at,
    'lookAtEntity': _text, 'attackTarget': _true, 'useItem': _true, 'sneakHold': _flag, 'sprint': _flag, 'move': _move,
    'key': _text, 'closeScreen': _true, 'clearChat': _true, 'clickChat': _text, 'hoverChat': _text, 'clickButton': _text,
    'caption': _text, 'join': _text, 'waitForWorld': _true, 'waitForScreen': _text, 'waitForChat': _text, 'cursor': _cursor, 'log': _text,
}


def _number(value: float) -> str:
    return str(int(value)) if float(value).is_integer() else repr(float(value))


def expand(beat: dict[str, Any]) -> list[dict[str, Any]]:
    """Turns one sheet beat into Animus Camera steps."""
    body: dict[str, Any] = {k: v for k, v in beat.items() if k != NOTE}
    action: str = next(k for k in body if k in ACTIONS)
    value: Any = body.pop(action)
    if action == 'tp':
        return [{'chat': '/tp @s ' + ' '.join(_number(v) for v in value), 'typing': False}]
    if action == 'run':
        return [{'chat': value, 'typing': False}]
    if action == 'type':
        return [{'chat': value, 'typing': True, **body}]
    if action == 'menu':
        return [{'chat': MENU_COMMAND, 'typing': True}, {'waitForScreen': HOME_TITLE, 'timeout': SCREEN_TIMEOUT_MS}, {'wait': 350}]
    if action == 'click':
        return [{'clickSlotNamed': value, **body}]
    if action == 'hover':
        return [{'hoverSlotNamed': value, 'ms': body.pop('ms', HOVER_MS), **body}]
    if action == 'hoverSlot':
        return [{'hoverSlot': value, 'ms': body.pop('ms', HOVER_MS), **body}]
    if action == 'anvil':
        return [{'anvilText': value}]
    if action == 'screen':
        return [{'waitForScreen': value, 'timeout': body.get('timeout', SCREEN_TIMEOUT_MS)}]
    if action == 'expect':
        return [{'waitForChat': value, 'timeout': body.get('timeout', SCREEN_TIMEOUT_MS)}]
    if action == 'result':
        return [{'log': f'Result: {value}'}, {'wait': body.get('ms', RESULT_MS)}]
    if action == 'cue':
        return [{'chat': f'/execute as @s[tag=!cue_{value}] if entity @s', 'typing': False}]
    return [{action: value, **body}]


def fill(value: Any, values: dict[str, str]) -> Any:
    """Replaces {player} and {address} in every string; other braces (JSON in commands) are left alone."""
    if isinstance(value, str):
        for name, replacement in values.items():
            value = value.replace('{' + name + '}', replacement)
        return value
    if isinstance(value, list):
        return [fill(v, values) for v in value]
    if isinstance(value, dict):
        return {k: fill(v, values) for k, v in value.items()}
    return value


def compile_script(loaded: Sheet, output: Path, values: dict[str, str] | None = None) -> list[dict[str, Any]]:
    """The full step script for one recording: join, setup, record, beats, closing caption, stop and quit."""
    first: dict[str, Any] = {'waitForScreen': 'TitleScreen', 'timeout': 180000} if loaded.title_screen else {'waitForWorld': True, 'timeout': 180000}
    steps: list[dict[str, Any]] = [first, {'settings': {'seed': loaded.seed, 'clickRest': loaded.click_rest}}]
    for beat in loaded.setup:
        steps.extend(expand(beat))
    steps.append({'record': 'start', 'file': str(output)})
    for beat in loaded.beats:
        steps.extend(expand(beat))
    steps.extend([
        {'caption': loaded.data['closing'], 'ms': CLOSING_MS},
        {'wait': CLOSING_MS},
        {'record': 'stop'},
        {'disconnect': True},
        {'quit': True},
    ])
    return fill(steps, values or {})


def compile_prep(loaded: Sheet, values: dict[str, str] | None = None) -> list[dict[str, Any]]:
    """The same clip without recording, caption or quit, run first when another clip needs the state it leaves."""
    steps: list[dict[str, Any]] = [{'waitForWorld': True, 'timeout': 180000}, {'settings': {'seed': loaded.seed, 'clickRest': loaded.click_rest}}]
    for beat in loaded.setup + loaded.beats:
        steps.extend(expand(beat))
    steps.append({'closeScreen': True})
    return fill(steps, values or {})


def estimate_seconds(loaded: Sheet) -> float:
    """Rough length of the recorded part, for comparing against the target before recording."""
    total: float = CLOSING_MS / 1000.0
    for beat in loaded.beats:
        total += _beat_seconds(beat, loaded.click_rest)
    return round(total, 3)


def _beat_seconds(beat: dict[str, Any], click_rest: int) -> float:
    action: str = next((k for k in beat if k in ACTIONS), '')
    value: Any = beat.get(action)
    glide: float = beat.get('glide', 500) / 1000.0
    if action in ('wait', 'move'):
        return (value if action == 'wait' else beat.get('ms', 800)) / 1000.0
    if action == 'result':
        return beat.get('ms', RESULT_MS) / 1000.0
    if action == 'type':
        return len(value) * TYPING_SECONDS_PER_CHAR + 0.15 + TYPED_HOLD_SECONDS
    if action == 'menu':
        return len(MENU_COMMAND) * TYPING_SECONDS_PER_CHAR + 0.15 + TYPED_HOLD_SECONDS + 0.35 + 0.3
    if action == 'anvil':
        return len(value) * TYPING_SECONDS_PER_CHAR + 0.2 + TYPED_HOLD_SECONDS + glide + click_rest / 1000.0 + 0.25
    if action in ('click', 'clickSlot', 'clickChat', 'clickButton'):
        return glide + beat.get('rest', click_rest) / 1000.0 + 0.25
    if action in ('hover', 'hoverSlot', 'hoverChat'):
        return glide + beat.get('ms', HOVER_MS) / 1000.0
    if action in ('look', 'lookAt'):
        return float(value.get('ms', 600)) / 1000.0
    if action == 'lookAtEntity':
        return beat.get('ms', 600) / 1000.0
    if action in ('tp', 'run', 'cue'):
        return 0.05
    if action in ('screen', 'expect', 'attackTarget', 'useItem', 'closeScreen'):
        return 0.3
    if action == 'key':
        return beat.get('hold', 150) / 1000.0 + 0.1
    return 0.0
