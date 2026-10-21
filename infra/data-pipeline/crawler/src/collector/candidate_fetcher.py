from __future__ import annotations

import asyncio
from typing import Any
from urllib.parse import urlencode

from playwright.async_api import APIRequestContext

from src.collector.config import (
    GENDER_FILTER,
    REQUEST_DELAY_SECONDS,
)


PLP_API = "https://api.musinsa.com/api2/dp/v1/plp/goods"


def _collect_goods_nodes(value: Any, output: list[dict[str, Any]]) -> None:
    """
    PLP 응답 구조 변경에 조금 더 버틸 수 있도록
    goodsNo가 들어있는 상품 dict를 재귀적으로 탐색한다.
    """
    if isinstance(value, dict):
        if "goodsNo" in value and (
            "goodsName" in value
            or "goodsNm" in value
            or "imageUrl" in value
        ):
            output.append(value)

        for child in value.values():
            _collect_goods_nodes(child, output)

    elif isinstance(value, list):
        for child in value:
            _collect_goods_nodes(child, output)


def _dedupe_goods(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[int] = set()
    result: list[dict[str, Any]] = []

    for item in items:
        try:
            goods_no = int(item.get("goodsNo"))
        except (TypeError, ValueError):
            continue

        if goods_no in seen:
            continue

        seen.add(goods_no)

        result.append(
            {
                "product_id": goods_no,
                "name": item.get("goodsName") or item.get("goodsNm"),
                "brand": item.get("brandName"),
                "gender_hint": item.get("gender")
                or item.get("displayGenderText"),
                "image_url": item.get("imageUrl")
                or item.get("thumbnail"),
                "normal_price": item.get("normalPrice"),
                "price": item.get("finalPrice")
                or item.get("price"),
                "sale_rate": item.get("saleRate")
                or item.get("discountRate"),
                "review_count": item.get("reviewCount"),
                "is_sold_out": item.get("isSoldOut"),
            }
        )

    return result


async def fetch_candidate_page(
    request: APIRequestContext,
    *,
    category_code: str,
    page: int,
    page_size: int,
    sort_code: str,
) -> list[dict[str, Any]]:
    params = {
        "gf": GENDER_FILTER,
        "sortCode": sort_code,
        "caller": "CATEGORY",
        "category": category_code,
        "page": page,
        "size": page_size,
    }

    url = f"{PLP_API}?{urlencode(params)}"

    response = await request.get(
        url,
        headers={
            "Referer": (
                f"https://www.musinsa.com/category/"
                f"{category_code}/goods?gf={GENDER_FILTER}"
            ),
            "Accept": "application/json",
        },
        timeout=30_000,
    )

    if not response.ok:
        raise RuntimeError(
            f"candidate API failed: status={response.status}, url={url}"
        )

    payload = await response.json()

    raw_items: list[dict[str, Any]] = []
    _collect_goods_nodes(payload, raw_items)

    await asyncio.sleep(REQUEST_DELAY_SECONDS)

    return _dedupe_goods(raw_items)
