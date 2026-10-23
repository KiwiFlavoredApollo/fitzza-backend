from __future__ import annotations

import json
from pathlib import Path


ROOTS = [
    Path("data/screened"),
    Path("data/selection"),
    Path("data/products"),
    Path("data/final_products"),
]


def normalize_year(year):
    if year in ("", None):
        return None

    try:
        parsed = int(year)
    except (TypeError, ValueError):
        return year

    return None if parsed <= 0 else year


def normalize_collection_year(value):
    if isinstance(value, dict):
        collection = value.get("collection")
        if isinstance(collection, dict):
            year = collection.get("year")
            collection["year"] = normalize_year(year)

        season = value.get("season")
        if isinstance(season, dict):
            collection = season.get("collection")
            if isinstance(collection, dict):
                year = collection.get("year")
                collection["year"] = normalize_year(year)

        for child in value.values():
            normalize_collection_year(child)

    elif isinstance(value, list):
        for child in value:
            normalize_collection_year(child)


def main() -> None:
    changed_files = 0

    for root in ROOTS:
        if not root.exists():
            continue

        for path in root.rglob("*.json"):
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
            except Exception:
                continue

            before = json.dumps(data, ensure_ascii=False, sort_keys=True)
            normalize_collection_year(data)
            after = json.dumps(data, ensure_ascii=False, sort_keys=True)

            if before != after:
                path.write_text(
                    json.dumps(data, ensure_ascii=False, indent=2),
                    encoding="utf-8",
                )
                changed_files += 1
                print(f"[FIXED] {path}")

    print(f"\nchanged files: {changed_files}")


if __name__ == "__main__":
    main()
