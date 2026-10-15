"""Validate crawler output and build the deterministic input for Jev color normalization."""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path
from urllib.parse import urlparse


SCHEMA_VERSION = "1.0"


class ContractError(ValueError):
    """Raised when crawler output cannot safely enter the Jev stage."""


def _required_text(value: object, field: str, source: Path) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ContractError(f"{source}: {field} must be a non-empty string")
    return value.strip()


def _category(product: dict, source: Path) -> str:
    category = product.get("category") or {}
    dataset = product.get("dataset") or {}
    for value in (
        category.get("categoryDepth1Name"),
        category.get("categoryDepth1Title"),
        dataset.get("logical_category"),
    ):
        if isinstance(value, str) and value.strip():
            return value.strip()
    raise ContractError(f"{source}: category depth-1 name is missing")


def _image_url(value: object, source: Path) -> str:
    url = _required_text(value, "color_image_mappings[].image_url", source)
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ContractError(f"{source}: invalid color image URL: {url}")
    return url


def _product_context(product: dict, source: Path) -> list[tuple[str, dict]]:
    product_id = product.get("product_id")
    if isinstance(product_id, bool) or not isinstance(product_id, int) or product_id <= 0:
        raise ContractError(f"{source}: product_id must be a positive integer")

    name = _required_text(product.get("name"), "name", source)
    category = _category(product, source)
    mappings = product.get("color_image_mappings")
    if mappings is None:
        return []
    if not isinstance(mappings, list):
        raise ContractError(f"{source}: color_image_mappings must be an array")

    parsed_mappings: list[tuple[str, str]] = []
    for mapping in mappings:
        if not isinstance(mapping, dict):
            raise ContractError(f"{source}: color_image_mappings[] must be an object")
        color_raw = _required_text(mapping.get("color_raw"), "color_image_mappings[].color_raw", source)
        parsed_mappings.append((color_raw, _image_url(mapping.get("image_url"), source)))

    sibling_colors = sorted({color for color, _ in parsed_mappings}, key=lambda value: (value.casefold(), value))
    relative_source = source.as_posix()
    return [
        (
            color_raw,
            {
                "product_id": product_id,
                "product_name": name,
                "category": category,
                "sibling_colors": sibling_colors,
                "image_url": image_url,
                "source_file": relative_source,
            },
        )
        for color_raw, image_url in parsed_mappings
    ]


def build_jev_input(source_root: Path) -> dict:
    source_root = source_root.resolve()
    if not source_root.is_dir():
        raise ContractError(f"source directory does not exist: {source_root}")

    paths = sorted(source_root.rglob("*.json"))
    if not paths:
        raise ContractError(f"no product JSON files found: {source_root}")

    contexts_by_color: dict[str, list[dict]] = defaultdict(list)
    products_with_colors = 0
    for path in paths:
        try:
            product = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise ContractError(f"{path}: invalid JSON: {exc}") from exc
        if not isinstance(product, dict):
            raise ContractError(f"{path}: product JSON root must be an object")

        relative_path = path.relative_to(source_root)
        contexts = _product_context(product, relative_path)
        if contexts:
            products_with_colors += 1
        for color_raw, context in contexts:
            contexts_by_color[color_raw].append(context)

    if not contexts_by_color:
        raise ContractError("crawler output contains no color_image_mappings")

    colors = []
    for color_raw in sorted(contexts_by_color, key=lambda value: (value.casefold(), value)):
        contexts = sorted(
            contexts_by_color[color_raw],
            key=lambda item: (item["product_id"], item["image_url"], item["source_file"]),
        )
        colors.append({"color_raw": color_raw, "contexts": contexts})

    return {
        "schema_version": SCHEMA_VERSION,
        "source_product_count": len(paths),
        "products_with_colors": products_with_colors,
        "unique_color_count": len(colors),
        "colors": colors,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="Crawler data directory containing product JSON")
    parser.add_argument("--output", required=True, type=Path, help="Jev input JSON path")
    args = parser.parse_args()

    output = args.output.resolve()
    source = args.source.resolve()
    if output == source or source in output.parents:
        raise ContractError("output must be outside the crawler source directory")

    result = build_jev_input(source)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "colors"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
