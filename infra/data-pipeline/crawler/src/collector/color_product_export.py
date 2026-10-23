"""Export color-level product candidates without changing the source dataset.

Unknown color, measurement, and image relationships remain unresolved. The
export is intentionally not a ready-to-index dataset.
"""
from __future__ import annotations

import json
import re
import unicodedata
import uuid
from collections import Counter
from copy import deepcopy
from pathlib import Path
from typing import Any


COLOR_WORDS = frozenset(
    "BLACK WHITE IVORY CREAM NAVY BLUE SKY GREEN KHAKI OLIVE BROWN BEIGE "
    "GRAY GREY CHARCOAL PINK RED PURPLE YELLOW ORANGE WINE BURGUNDY "
    "INDIGO DENIM SILVER GOLD 블랙 화이트 아이보리 크림 네이비 블루 그린 "
    "카키 브라운 베이지 그레이 차콜 핑크 레드 퍼플 와인 버건디 "
    "검정 흰색 남색 갈색 회색 연청 중청 진청 흑청".split()
)
SIZE_PATTERN = re.compile(
    r"(?:[2-5]?X{0,3}[SML]|FREE|ONE\s*SIZE|OS|\d{1,3}(?:\.5)?(?:호|MM|CM)?|\d{2,3}\s*[-~]\s*\d{2,3})$",
    re.IGNORECASE,
)
OTHER_ATTRIBUTE_PATTERN = re.compile(
    r"기모|논기모|원단|크롭|긴바지|PACK|팩|세트|SET|자수|DTX_|DIFD_",
    re.IGNORECASE,
)
RESERVED_SIZE_FIELDS = frozenset(
    {"source_option_id", "raw_label", "raw_values", "size", "measurement_status", "activated", "price_delta", "managed_code"}
)


def normalized(value: Any) -> str:
    text = unicodedata.normalize("NFKC", str(value or "")).upper()
    return re.sub(r"\s+", " ", text).strip().strip("[]{}").strip()


def size_label(value: Any) -> str | None:
    text = normalized(value)
    return text if SIZE_PATTERN.fullmatch(text) else None


def color_label(value: Any) -> str | None:
    text = normalized(value)
    if not text or size_label(text) or OTHER_ATTRIBUTE_PATTERN.search(text):
        return None
    if re.search(r"[/+_\d-]", text):
        return None
    if any(re.search(rf"(?<![A-Z]){re.escape(word)}(?![A-Z])", text) for word in COLOR_WORDS):
        return text
    return None


def split_label(value: Any) -> tuple[str | None, str | None]:
    text = normalized(value)
    if size_label(text):
        return None, size_label(text)
    if color_label(text):
        return color_label(text), None
    matches: set[tuple[str, str]] = set()
    for divider in re.finditer(r"\s*[-/_]\s*|\s+", text):
        left, right = text[:divider.start()], text[divider.end():]
        if color_label(left) and size_label(right):
            matches.add((color_label(left), size_label(right)))
        if size_label(left) and color_label(right):
            matches.add((color_label(right), size_label(left)))
    return next(iter(matches)) if len(matches) == 1 else (None, None)


def parsed_option(option: dict[str, Any]) -> tuple[str | None, str | None]:
    pieces = [split_label(value) for value in option.get("values") or []]
    if any(piece == (None, None) for piece in pieces):
        return None, None
    colors = {color for color, _ in pieces if color}
    sizes = {size for _, size in pieces if size}
    if len(colors) > 1 or len(sizes) > 1:
        return None, None
    return next(iter(colors), None), next(iter(sizes), None)


def positive_measurement(row: dict[str, Any]) -> bool:
    return any(
        isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0
        for value in (row.get("measurements") or {}).values()
    )


def measurement_match(
    option: dict[str, Any], color: str, size: str | None, rows: list[dict[str, Any]]
) -> tuple[dict[str, Any] | None, str]:
    if size is None:
        return None, "size_unresolved"
    values = option.get("values") or []
    exact = [row for row in rows if len(values) == 1 and normalized(row.get("size")) == normalized(values[0])]
    paired = [row for row in rows if size and split_label(row.get("size")) == (color, size)]
    shared = [row for row in rows if size and size_label(row.get("size")) == size]
    if len(exact) == 1:
        return (exact[0], "label_match") if positive_measurement(exact[0]) else (None, "invalid_zero_values")
    if len(exact) > 1:
        return None, "ambiguous"
    if len(paired) == 1:
        return (paired[0], "label_match") if positive_measurement(paired[0]) else (None, "invalid_zero_values")
    if len(paired) > 1:
        return None, "ambiguous"
    if shared:
        return None, "shared_scope_unverified" if len(shared) == 1 else "ambiguous"
    return None, "unresolved"


def image_candidates(product: dict[str, Any], color: str) -> list[str]:
    matches = []
    for group in product.get("options") or []:
        for value in group.get("values") or []:
            option_color, _ = split_label(value.get("name"))
            if option_color == color and value.get("image_url"):
                matches.append(value["image_url"])
    return sorted(set(matches))


def export_product(product: dict[str, Any]) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    variants: dict[str, dict[str, Any]] = {}
    unresolved = []
    evidence = []
    rows = (product.get("size") or {}).get("measurements") or []
    for option in product.get("option_items") or []:
        color, size = parsed_option(option)
        if color is None:
            unresolved.append(deepcopy(option))
            continue
        product_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"musinsa:color-product:{product['product_id']}:{color}"))
        if product_id not in variants:
            base = {key: deepcopy(value) for key, value in product.items() if key not in {"product_id", "options", "option_items", "size", "images"}}
            base.update({
                "schema_version": "3.0-candidate",
                "product_id": product_id,
                "source_product_id": product["product_id"],
                "color": {"raw": color, "name": color},
                "thumbnail_url": None,
                "thumbnail_status": "unverified",
                "source_images": deepcopy(product.get("images") or []),
                "measurement_unit": (product.get("size") or {}).get("unit"),
                "sizes": [],
            })
            variants[product_id] = base
        measurement, status = measurement_match(option, color, size, rows)
        output_row = {key: deepcopy(value) for key, value in option.items() if key not in {"id", "values"}}
        output_row.update({
            "source_option_id": option.get("id"),
            "raw_values": deepcopy(option.get("values") or []),
            "raw_label": option["values"][0] if len(option.get("values") or []) == 1 else None,
            "size": size,
            "measurement_status": status,
        })
        if measurement:
            overlap = set(measurement["measurements"]) & (RESERVED_SIZE_FIELDS | set(output_row))
            if overlap:
                raise ValueError(f"Measurement key collision: {sorted(overlap)}")
            output_row.update(deepcopy(measurement["measurements"]))
        variants[product_id]["sizes"].append(output_row)
        evidence.append({
            "color_product_id": product_id,
            "source_option_id": option.get("id"),
            "measurement_status": status,
            "thumbnail_candidates": image_candidates(product, color),
        })
    for variant in variants.values():
        variant["sizes"].sort(key=lambda item: str(item["source_option_id"]))
    return sorted(variants.values(), key=lambda item: item["product_id"]), unresolved, evidence


def export_dataset(source_root: Path, output_root: Path) -> dict[str, int]:
    if not source_root.is_dir():
        raise FileNotFoundError(source_root)
    if source_root.resolve() == output_root.resolve() or source_root.resolve() in output_root.resolve().parents:
        raise ValueError("Output must be outside the source dataset")
    summary: Counter[str] = Counter()
    seen: set[str] = set()
    paths = sorted(source_root.glob("*/*.json"))
    if not paths:
        raise ValueError(f"No source products found: {source_root}")
    manifest: list[str] = []
    for source_path in paths:
        product = json.loads(source_path.read_text(encoding="utf-8"))
        candidates, unresolved, evidence = export_product(product)
        source_ids = Counter(item.get("id") for item in product.get("option_items") or [])
        exported_ids = Counter(row["source_option_id"] for item in candidates for row in item["sizes"])
        exported_ids.update(item.get("id") for item in unresolved)
        if source_ids != exported_ids:
            raise ValueError(f"Option accounting mismatch: {source_path}")
        for item in candidates:
            if item["product_id"] in seen:
                raise ValueError(f"Duplicate color product ID: {item['product_id']}")
            seen.add(item["product_id"])
            destination = output_root / "candidates" / source_path.parent.name / f"{item['product_id']}.json"
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(json.dumps(item, ensure_ascii=False, indent=2), encoding="utf-8")
            manifest.append(str(destination.relative_to(output_root)).replace("\\", "/"))
        audit_path = output_root / "audit" / source_path.parent.name / source_path.name
        audit_path.parent.mkdir(parents=True, exist_ok=True)
        audit_path.write_text(json.dumps({
            "source_product_id": product["product_id"],
            "unresolved_options": unresolved,
            "evidence": evidence,
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        summary.update({
            "source_products": 1,
            "source_options": sum(source_ids.values()),
            "candidate_products": len(candidates),
            "candidate_options": sum(exported_ids.values()) - len(unresolved),
            "unresolved_options": len(unresolved),
            "size_measurement_candidates": sum(
                row["size"] is not None and row["measurement_status"] == "label_match"
                for item in candidates for row in item["sizes"]
            ),
        })
    output_root.mkdir(parents=True, exist_ok=True)
    (output_root / "summary.json").write_text(json.dumps(dict(summary), ensure_ascii=False, indent=2), encoding="utf-8")
    (output_root / "manifest.json").write_text(json.dumps({
        "schema_version": "3.0-candidate",
        "ready_for_indexing": False,
        "product_count": len(manifest),
        "products": manifest,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    return dict(summary)
