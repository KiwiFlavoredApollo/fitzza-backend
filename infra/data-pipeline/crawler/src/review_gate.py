"""Bind explicit human color review to an immutable local handoff bundle.

This gate validates review completeness and file consistency, not image/size quality.
It makes no AWS, Jev, model or Qdrant calls.
"""

import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

COLORS = {"black", "white", "ivory", "beige", "gray", "charcoal", "navy", "blue",
          "green", "khaki", "brown", "red", "pink", "purple", "yellow", "orange", "multi", "etc"}
FILES = ("config/color_map.json", "config/color_map_review.csv", "config/reviewed_colors.json")


def fingerprint(bundle: Path) -> dict:
    paths = list(FILES)
    products = sorted((bundle / "data").rglob("*.json"))
    if not products:
        raise ValueError("Bundle contains no product JSON")
    ids = set()
    raw_colors = set()
    for path in products:
        if path.is_symlink():
            raise ValueError("Product symlinks are not permitted")
        product = json.loads(path.read_text(encoding="utf-8"))
        identifier = str(product["product_id"])
        if not identifier.isascii() or not identifier.isdecimal() or int(identifier) <= 0:
            raise ValueError("Invalid product ID")
        identifier = str(int(identifier))
        if identifier in ids:
            raise ValueError("Duplicate product ID")
        ids.add(identifier)
        mappings = product.get("color_image_mappings") or []
        if not mappings:
            raise ValueError("Product has no color-image mappings")
        for mapping in mappings:
            raw = mapping["color_raw"].strip().lower()
            if not raw:
                raise ValueError("Empty color key")
            raw_colors.add(raw)
        paths.append(path.relative_to(bundle).as_posix())
    color_map = json.loads((bundle / FILES[0]).read_text(encoding="utf-8"))
    decisions = json.loads((bundle / FILES[2]).read_text(encoding="utf-8"))
    if not isinstance(color_map, dict) or not isinstance(decisions, dict):
        raise ValueError("Color map and reviewed colors must be objects")
    if any(key != key.strip().lower() or value not in COLORS for key, value in color_map.items()):
        raise ValueError("Invalid normalized color map")
    if not raw_colors <= color_map.keys():
        raise ValueError("Some dataset colors are unmapped")
    with (bundle / FILES[1]).open(encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file)
        if not reader.fieldnames or "color_raw" not in reader.fieldnames:
            raise ValueError("Review CSV requires color_raw column")
        required = {row["color_raw"].strip().lower() for row in reader}
    # Jev excludes overrides from its CSV. Require explicit review of all applied overrides too.
    override_path = bundle / "config/color_map_overrides.json"
    if override_path.exists():
        overrides = json.loads(override_path.read_text(encoding="utf-8"))
        required.update(key.strip().lower() for key in overrides
                        if not key.startswith("_") and key.strip().lower() in raw_colors)
        paths.append("config/color_map_overrides.json")
    required |= {key for key in raw_colors if color_map[key] in {"etc", "multi"}}
    if not required <= decisions.keys():
        raise ValueError("Human review is incomplete")
    if any(key not in color_map or value != color_map[key] for key, value in decisions.items()):
        raise ValueError("Human decisions disagree with final color map")
    hashes = {}
    for relative in sorted(paths):
        path = bundle / relative
        if path.is_symlink():
            raise ValueError("Bundle symlinks are not permitted")
        hashes[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
    return {"product_count": len(ids), "unique_color_count": len(raw_colors),
            "required_review_count": len(required), "files": hashes}


def approve(bundle: Path) -> None:
    record = fingerprint(bundle)
    record.update(schema_version=1, human_color_review_approved=True,
                  dataset_quality_approved=False,
                  approved_at=datetime.now(timezone.utc).isoformat())
    with (bundle / "config/_APPROVED.json").open("x", encoding="utf-8") as file:
        json.dump(record, file, ensure_ascii=False, indent=2)


def verify(bundle: Path) -> None:
    record = json.loads((bundle / "config/_APPROVED.json").read_text(encoding="utf-8"))
    if record.get("human_color_review_approved") is not True:
        raise ValueError("Human approval is missing")
    current = fingerprint(bundle)
    if any(record.get(key) != value for key, value in current.items()):
        raise ValueError("Bundle changed after approval")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["approve", "verify"])
    parser.add_argument("--bundle", type=Path, required=True)
    args = parser.parse_args()
    (approve if args.action == "approve" else verify)(args.bundle)
    print("Color-review gate passed; separate dataset quality approval is still required")


if __name__ == "__main__":
    main()
