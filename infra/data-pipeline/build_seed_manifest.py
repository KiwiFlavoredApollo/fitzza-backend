"""Inventory existing products without treating them as human-approved data."""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

TARGETS = {"상의": 900, "하의": 750, "신발": 600, "아우터": 450, "모자": 300}


def build_manifest(source: Path) -> dict:
    if not source.is_dir():
        raise ValueError("Seed source must be an existing directory")
    records = []
    seen = set()
    counts = Counter()
    for path in sorted(source.rglob("*.json")):
        content = path.read_bytes()
        product = json.loads(content.decode("utf-8"))
        product_id = str(product.get("product_id", ""))
        if not product_id.isascii() or not product_id.isdecimal() or int(product_id) <= 0:
            raise ValueError("Invalid product_id")
        product_id = str(int(product_id))
        if product_id in seen:
            raise ValueError(f"Duplicate product_id: {product_id}")
        category = (product.get("dataset") or {}).get("logical_category")
        if category not in TARGETS:
            raise ValueError(f"Unknown category for product {product_id}")
        seen.add(product_id)
        counts[category] += 1
        records.append({"product_id": product_id, "category": category,
                        "path": path.relative_to(source).as_posix(),
                        "sha256": hashlib.sha256(content).hexdigest()})
    if not records:
        raise ValueError("No seed products found")
    if any(counts[category] > target for category, target in TARGETS.items()):
        raise ValueError("Seed category count exceeds target")
    return {"schema_version": 1, "status": "pending_quality_and_human_review",
            "unique_product_count": len(seen), "target_by_category": TARGETS,
            "seed_by_category": {category: counts[category] for category in TARGETS},
            "remaining_by_category": {category: target - counts[category]
                                      for category, target in TARGETS.items()},
            "products": records}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, help="Write a new manifest; never overwrite an existing file")
    args = parser.parse_args()
    manifest = build_manifest(args.source)
    serialized = json.dumps(manifest, ensure_ascii=False, indent=2)
    if args.output:
        with args.output.open("x", encoding="utf-8") as file:
            file.write(serialized + "\n")
        print(f"Seed inventory written: {manifest['unique_product_count']} products")
    else:
        print(serialized)


if __name__ == "__main__":
    main()
