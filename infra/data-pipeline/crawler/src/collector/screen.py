from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path

from playwright.async_api import async_playwright

from src.collector.batch_crawler import BatchProductCrawler
from src.collector.config import CATEGORY_CONFIG
from src.collector.rules import is_allowed_gender, is_target_category


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="후보 상품 상세를 읽어 성별/카테고리/계절 metadata screening"
    )
    parser.add_argument(
        "--category",
        choices=list(CATEGORY_CONFIG.keys()),
        required=True,
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=100,
        help="이번 실행에서 새로 screen할 최대 후보 수",
    )
    parser.add_argument("--headful", action="store_true")
    parser.add_argument("--delay", type=float, default=0.8)
    return parser.parse_args()


async def run() -> None:
    args = parse_args()

    candidate_path = Path("data/candidates") / f"{args.category}.json"
    if not candidate_path.exists():
        raise FileNotFoundError(candidate_path)

    candidates = json.loads(candidate_path.read_text(encoding="utf-8"))

    screened_dir = Path("data/screened") / args.category
    rejected_dir = Path("data/screened_rejected") / args.category
    failed_dir = Path("data/screened_failed") / args.category

    screened_dir.mkdir(parents=True, exist_ok=True)
    rejected_dir.mkdir(parents=True, exist_ok=True)
    failed_dir.mkdir(parents=True, exist_ok=True)

    attempted = 0
    accepted = 0
    rejected = 0
    failed = 0

    async with async_playwright() as p:
        crawler = BatchProductCrawler(
            p,
            headless=not args.headful,
            delay_seconds=max(args.delay, 0.3),
        )
        await crawler.start()

        try:
            for candidate in candidates:
                if attempted >= args.limit:
                    break

                product_id = int(candidate["product_id"])

                accepted_path = screened_dir / f"{product_id}.json"
                rejected_path = rejected_dir / f"{product_id}.json"
                failed_path = failed_dir / f"{product_id}.json"

                if accepted_path.exists() or rejected_path.exists():
                    continue

                attempted += 1
                print(
                    f"[{attempted}/{args.limit}] {product_id} ... ",
                    end="",
                    flush=True,
                )

                try:
                    product = await crawler.fetch_basic_product(product_id)

                    if not is_allowed_gender(product):
                        rejected_path.write_text(
                            json.dumps(
                                {
                                    "product_id": product_id,
                                    "reason": "gender_not_allowed",
                                    "gender": product.get("gender"),
                                },
                                ensure_ascii=False,
                                indent=2,
                            ),
                            encoding="utf-8",
                        )
                        rejected += 1
                        print("REJECT gender")
                        continue

                    if not is_target_category(product, args.category):
                        rejected_path.write_text(
                            json.dumps(
                                {
                                    "product_id": product_id,
                                    "reason": "category_mismatch",
                                    "category": product.get("category"),
                                },
                                ensure_ascii=False,
                                indent=2,
                            ),
                            encoding="utf-8",
                        )
                        rejected += 1
                        print("REJECT category")
                        continue

                    accepted_path.write_text(
                        json.dumps(product, ensure_ascii=False, indent=2),
                        encoding="utf-8",
                    )

                    # 재시도 성공 시 과거 failure 제거
                    if failed_path.exists():
                        failed_path.unlink()

                    accepted += 1

                    collection = (
                        product.get("season", {})
                        .get("collection", {})
                        .get("type")
                    )
                    original = product.get("season", {}).get("original", [])

                    print(
                        f"OK gender={product['gender']['normalized']} "
                        f"tags={original} collection={collection}"
                    )

                except Exception as exc:
                    failed_path.write_text(
                        json.dumps(
                            {
                                "product_id": product_id,
                                "reason": "crawl_failed",
                                "detail": str(exc),
                            },
                            ensure_ascii=False,
                            indent=2,
                        ),
                        encoding="utf-8",
                    )
                    failed += 1
                    print(f"FAILED {type(exc).__name__}")

        finally:
            await crawler.close()

    print("\n=== screening finished ===")
    print(f"attempted: {attempted}")
    print(f"accepted : {accepted}")
    print(f"rejected : {rejected}")
    print(f"failed   : {failed}")
    print(f"saved    : {screened_dir}")


if __name__ == "__main__":
    asyncio.run(run())
