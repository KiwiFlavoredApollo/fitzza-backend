from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path
from typing import Any

from playwright.async_api import async_playwright

from src.collector.candidate_fetcher import fetch_candidate_page
from src.collector.config import (
    CATEGORY_CONFIG,
    DEFAULT_PAGE_SIZE,
    DEFAULT_PAGES_PER_SORT,
    DISCOVERY_SORT_CODES,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="무신사 남성+공용 상품 후보 Product ID 수집"
    )
    parser.add_argument(
        "--pages",
        type=int,
        default=DEFAULT_PAGES_PER_SORT,
        help="카테고리/정렬별 조회 페이지 수",
    )
    parser.add_argument(
        "--page-size",
        type=int,
        default=DEFAULT_PAGE_SIZE,
        help="API 한 페이지 요청 상품 수",
    )
    return parser.parse_args()


def merge_candidates(
    destination: dict[int, dict[str, Any]],
    candidates: list[dict[str, Any]],
    *,
    category: str,
    sort_code: str,
) -> None:
    for item in candidates:
        product_id = int(item["product_id"])

        if product_id not in destination:
            destination[product_id] = {
                **item,
                "source_category": category,
                "discovered_by": [],
            }

        marker = sort_code
        if marker not in destination[product_id]["discovered_by"]:
            destination[product_id]["discovered_by"].append(marker)


async def run() -> None:
    args = parse_args()

    if args.pages < 1:
        raise ValueError("--pages는 1 이상이어야 합니다.")
    if not 1 <= args.page_size <= 60:
        raise ValueError("--page-size는 1~60 범위로 사용하세요.")

    output_dir = Path("data/candidates")
    output_dir.mkdir(parents=True, exist_ok=True)

    summary: dict[str, Any] = {
        "pages_per_sort": args.pages,
        "page_size": args.page_size,
        "sort_codes": DISCOVERY_SORT_CODES,
        "categories": {},
        "total_unique_candidates": 0,
    }

    async with async_playwright() as p:
        request = await p.request.new_context(
            extra_http_headers={
                "User-Agent": (
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    "AppleWebKit/537.36 Chrome/154 Safari/537.36"
                )
            }
        )

        try:
            global_ids: set[int] = set()

            for logical_category, config in CATEGORY_CONFIG.items():
                category_code = config["source_category"]
                collected: dict[int, dict[str, Any]] = {}

                print(
                    f"\n[{logical_category}] "
                    f"source category={category_code}"
                )

                for sort_code in DISCOVERY_SORT_CODES:
                    for page in range(1, args.pages + 1):
                        print(
                            f"  - {sort_code} page={page}...",
                            end=" ",
                            flush=True,
                        )

                        items = await fetch_candidate_page(
                            request,
                            category_code=category_code,
                            page=page,
                            page_size=args.page_size,
                            sort_code=sort_code,
                        )

                        merge_candidates(
                            collected,
                            items,
                            category=logical_category,
                            sort_code=sort_code,
                        )

                        print(f"{len(items)} fetched")

                result = list(collected.values())
                result.sort(key=lambda x: x["product_id"])

                path = output_dir / f"{logical_category}.json"
                path.write_text(
                    json.dumps(result, ensure_ascii=False, indent=2),
                    encoding="utf-8",
                )

                ids = {int(item["product_id"]) for item in result}
                global_ids.update(ids)

                summary["categories"][logical_category] = {
                    "source_category": category_code,
                    "unique_candidates": len(result),
                    "target_total": config["total_quota"],
                    "output": str(path),
                }

                print(
                    f"  => unique {len(result)} candidates "
                    f"saved to {path}"
                )

            summary["total_unique_candidates"] = len(global_ids)

        finally:
            await request.dispose()

    summary_path = output_dir / "summary.json"
    summary_path.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print("\n=== Candidate discovery complete ===")
    for category, info in summary["categories"].items():
        print(
            f"{category:4} : "
            f"{info['unique_candidates']:4} candidates "
            f"/ target {info['target_total']}"
        )

    print(f"TOTAL UNIQUE: {summary['total_unique_candidates']}")
    print(f"SUMMARY     : {summary_path}")


if __name__ == "__main__":
    asyncio.run(run())
