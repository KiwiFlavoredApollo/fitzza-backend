from __future__ import annotations

from typing import Any


ALLOWED_GENDERS = {"MALE", "UNISEX"}

CATEGORY_NAME_MAP = {
    "상의": "상의",
    "바지": "하의",
    "하의": "하의",
    "신발": "신발",
    "아우터": "아우터",
    "모자": "모자",
}


def logical_category(product: dict[str, Any]) -> str | None:
    category = product.get("category") or {}
    raw = (
        category.get("categoryDepth1Name")
        or category.get("categoryDepth1Title")
    )
    return CATEGORY_NAME_MAP.get(raw)


def is_allowed_gender(product: dict[str, Any]) -> bool:
    gender = product.get("gender") or {}
    return gender.get("normalized") in ALLOWED_GENDERS


def is_target_category(product: dict[str, Any], expected: str) -> bool:
    return logical_category(product) == expected


def has_actual_size(product: dict[str, Any]) -> bool:
    size = product.get("size") or {}
    measurements = size.get("measurements") or []
    return bool(size.get("available") and measurements)


def is_eligible_product(product: dict[str, Any], expected_category: str) -> bool:
    return (
        is_allowed_gender(product)
        and is_target_category(product, expected_category)
        and has_actual_size(product)
    )
