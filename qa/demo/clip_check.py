"""Checks a recorded clip: VP9, 1920x1080, and a length near the sheet's target."""
from __future__ import annotations

import json
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any

WIDTH: int = 1920
HEIGHT: int = 1080
LENGTH_TOLERANCE: float = 1.0 / 3.0


@dataclass(frozen=True)
class Facts:
    codec: str
    width: int
    height: int
    seconds: float
    size: int


def parse(probe_json: str) -> Facts:
    data: dict[str, Any] = json.loads(probe_json)
    video: dict[str, Any] = next(s for s in data['streams'] if 'width' in s)
    return Facts(str(video['codec_name']), int(video['width']), int(video['height']), round(float(data['format']['duration']), 3), int(data['format']['size']))


def probe(path: Path) -> Facts:
    ffprobe: str = shutil.which('ffprobe') or 'ffprobe'
    done: subprocess.CompletedProcess[str] = subprocess.run(
        [ffprobe, '-v', 'error', '-show_entries', 'stream=codec_name,width,height:format=duration,size', '-of', 'json', str(path)],
        capture_output=True, text=True, check=True)
    return parse(done.stdout)


def problems(facts: Facts, target: float) -> list[str]:
    found: list[str] = []
    if facts.codec != 'vp9':
        found.append(f'codec is {facts.codec}, not vp9')
    if (facts.width, facts.height) != (WIDTH, HEIGHT):
        found.append(f'size is {facts.width}x{facts.height}, not {WIDTH}x{HEIGHT}')
    if abs(facts.seconds - target) > target * LENGTH_TOLERANCE:
        found.append(f'{facts.seconds:.1f} s is far from the {target:.0f} s target')
    return found
