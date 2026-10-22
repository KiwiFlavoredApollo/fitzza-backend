from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from src.collector.config import CATEGORY_CONFIG
from src.collector.quota import empty_progress


SEASONS = ["봄", "여름", "가을", "겨울"]


def load_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return default


def save_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def rebuild_progress(products_root: Path) -> dict[str, dict[str, int]]:
    progress = empty_progress()

    if not products_root.exists():
        return progress

    for category in CATEGORY_CONFIG:
        category_dir = products_root / category
        if not category_dir.exists():
            continue

        for product_path in category_dir.glob("*.json"):
            try:
                product = json.loads(
                    product_path.read_text(encoding="utf-8")
                )
            except Exception:
                continue

            primary = product.get("season", {}).get("primary")
            if primary in SEASONS:
                progress[category][primary] += 1

    return progress


def load_rejected(path: Path) -> dict[str, Any]:
    return load_json(path, {})


def save_rejected(path: Path, rejected: dict[str, Any]) -> None:
    save_json(path, rejected)


def load_failed(path: Path) -> dict[str, Any]:
    return load_json(path, {})


def save_failed(path: Path, failed: dict[str, Any]) -> None:
    save_json(path, failed)
