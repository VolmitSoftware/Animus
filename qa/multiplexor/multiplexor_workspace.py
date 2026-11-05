"""Paths in the one shared ServerMultiplexor workspace that every Animus QA script uses.

The workspace rule lives in VolmitSoftware/AGENTS.md: one workspace with its default root and build cache,
`--isolated` instances named <project>-<purpose>-<platform>-<version>, and no second workspace in a project.
"""
from pathlib import Path

REPO: Path = Path(__file__).resolve().parents[2]


def default_workspace() -> Path:
    """The sibling ServerMultiplexor checkout, also found from a worktree nested inside the Animus repository."""
    for parent in REPO.parents:
        candidate: Path = parent / 'ServerMultiplexor'
        if (candidate / 'multiplexor').exists():
            return candidate
    return REPO.parent / 'ServerMultiplexor'


def default_binary() -> Path:
    return default_workspace() / 'multiplexor'


def evidence_root(workspace: Path) -> Path:
    """Animus evidence stays in the workspace's ignored plugin consumer state, never at the workspace root."""
    return workspace / 'consumers/plugin-consumers/state/animus-evidence'


def baseline_name(project: str, version: str) -> str:
    """The pinned-server instance that managed-runtime.py provisions for one exact platform and version."""
    return f'animus-baseline-{project}-{version.replace(".", "-")}'
