from __future__ import annotations

from typing import Any

from src.collector.config import CATEGORY_CONFIG


SEASON_ORDER = ["봄", "여름", "가을", "겨울"]


def possible_seasons(product: dict[str, Any]) -> tuple[list[str], str]:
    """
    1순위: 무신사의 실제 계절 태그
    2순위: 공식 SS/FW 시즌을 이용한 범위 추론

    반환:
      (가능한 대표 계절 후보, 근거)
    """
    season = product.get("season") or {}
    original = [
        s for s in season.get("original", [])
        if s in SEASON_ORDER
    ]

    if original:
        return list(dict.fromkeys(original)), "musinsa_season_tags"

    collection = season.get("collection") or {}
    collection_type = collection.get("type")

    if collection_type == "SS":
        return ["봄", "여름"], "collection_fallback"
    if collection_type == "FW":
        return ["가을", "겨울"], "collection_fallback"

    return [], "unknown"


def allocate_primary_season(
    product: dict[str, Any],
    logical_category: str,
    progress: dict[str, dict[str, int]],
) -> str | None:
    """
    가능한 계절 후보 중 해당 카테고리 quota가 가장 많이 남은 계절을 배정한다.
    """
    candidates, _ = possible_seasons(product)
    if not candidates:
        return None

    targets = CATEGORY_CONFIG[logical_category]["season_quota"]
    current = progress.setdefault(
        logical_category,
        {season: 0 for season in SEASON_ORDER},
    )

    scored: list[tuple[int, int, str]] = []

    for index, season in enumerate(SEASON_ORDER):
        if season not in candidates:
            continue

        remaining = targets[season] - current.get(season, 0)
        if remaining <= 0:
            continue

        # remaining이 큰 계절 우선, 동일하면 고정된 사계절 순서
        scored.append((remaining, -index, season))

    if not scored:
        return None

    scored.sort(reverse=True)
    return scored[0][2]


def attach_primary_season(
    product: dict[str, Any],
    logical_category: str,
    progress: dict[str, dict[str, int]],
) -> str | None:
    primary = allocate_primary_season(product, logical_category, progress)
    if primary is None:
        return None

    candidates, source = possible_seasons(product)

    product.setdefault("season", {})["primary"] = primary
    product["season"]["primary_source"] = source
    product["season"]["primary_candidates"] = candidates

    return primary
