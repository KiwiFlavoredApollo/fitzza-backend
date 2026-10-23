from __future__ import annotations

import json
from pathlib import Path

from src.collector.config import CATEGORY_CONFIG


def empty_progress() -> dict[str, dict[str, int]]:
    return {
        category: {
            season: 0
            for season in config["season_quota"]
        }
        for category, config in CATEGORY_CONFIG.items()
    }


def load_progress(path: Path) -> dict[str, dict[str, int]]:
    if not path.exists():
        return empty_progress()

    return json.loads(path.read_text(encoding="utf-8"))


def save_progress(path: Path, progress: dict[str, dict[str, int]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(progress, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def category_complete(
    category: str,
    progress: dict[str, dict[str, int]],
) -> bool:
    target = CATEGORY_CONFIG[category]["season_quota"]
    current = progress.get(category, {})

    return all(
        current.get(season, 0) >= quota
        for season, quota in target.items()
    )


def all_complete(progress: dict[str, dict[str, int]]) -> bool:
    return all(
        category_complete(category, progress)
        for category in CATEGORY_CONFIG
    )
