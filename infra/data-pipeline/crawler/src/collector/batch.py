from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path
from typing import Any

from playwright.async_api import async_playwright

from src.collector.batch_crawler import BatchProductCrawler
from src.collector.config import CATEGORY_CONFIG
from src.collector.quota import (
    all_complete,
    category_complete,
    save_progress,
)
from src.collector.rules import (
    is_allowed_gender,
    is_target_category,
    logical_category,
)
from src.collector.season_allocator import attach_primary_season
from src.collector.state import (
    load_failed,
    load_rejected,
    rebuild_progress,
    save_failed,
    save_rejected,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="무신사 후보 상품을 최종 Product 데이터로 수집"
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=10,
        help="이번 실행에서 새로 저장할 최대 상품 수",
    )
    parser.add_argument(
        "--category",
        choices=list(CATEGORY_CONFIG.keys()),
        default=None,
        help="특정 카테고리만 테스트",
    )
    parser.add_argument(
        "--headful",
        action="store_true",
        help="브라우저 화면 표시",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=1.0,
        help="요청 사이 대기 시간(초)",
    )
    parser.add_argument(
        "--retry-failed",
        action="store_true",
        help="이전에 실패한 상품도 다시 시도",
    )
    return parser.parse_args()


def read_candidates(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []

    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise ValueError(f"후보 파일 형식 오류: {path}")

    return data


def reason_record(
    *,
    category: str,
    product_id: int,
    reason: str,
    detail: Any = None,
) -> dict[str, Any]:
    return {
        "product_id": product_id,
        "category": category,
        "reason": reason,
        "detail": detail,
    }


async def run() -> None:
    args = parse_args()

    if args.limit < 1:
        raise ValueError("--limit는 1 이상이어야 합니다.")

    candidate_root = Path("data/candidates")
    products_root = Path("data/products")
    state_root = Path("data/state")

    progress_path = state_root / "progress.json"
    rejected_path = state_root / "rejected.json"
    failed_path = state_root / "failed.json"

    progress = rebuild_progress(products_root)
    save_progress(progress_path, progress)

    rejected = load_rejected(rejected_path)
    failed = load_failed(failed_path)

    selected_categories = (
        [args.category]
        if args.category
        else list(CATEGORY_CONFIG.keys())
    )

    saved_this_run = 0

    print("\n=== Batch collector start ===")
    for category in selected_categories:
        counts = progress[category]
        print(
            f"{category}: "
            + ", ".join(
                f"{season} {counts[season]}/"
                f"{CATEGORY_CONFIG[category]['season_quota'][season]}"
                for season in counts
            )
        )

    async with async_playwright() as p:
        crawler = BatchProductCrawler(
            p,
            headless=not args.headful,
            delay_seconds=max(args.delay, 0.3),
        )
        await crawler.start()

        try:
            for category in selected_categories:
                if saved_this_run >= args.limit:
                    break

                if category_complete(category, progress):
                    print(f"\n[{category}] quota complete → skip")
                    continue

                candidate_path = candidate_root / f"{category}.json"
                candidates = read_candidates(candidate_path)

                if not candidates:
                    print(
                        f"\n[{category}] 후보 파일 없음: {candidate_path}"
                    )
                    continue

                print(f"\n[{category}] candidates={len(candidates)}")

                for candidate in candidates:
                    if saved_this_run >= args.limit:
                        break

                    if category_complete(category, progress):
                        break

                    try:
                        product_id = int(candidate["product_id"])
                    except (KeyError, TypeError, ValueError):
                        continue

                    product_path = (
                        products_root
                        / category
                        / f"{product_id}.json"
                    )

                    if product_path.exists():
                        continue

                    product_key = str(product_id)

                    if product_key in rejected:
                        continue

                    if product_key in failed and not args.retry_failed:
                        continue

                    print(
                        f"  [{saved_this_run + 1}/{args.limit}] "
                        f"{product_id} ...",
                        end=" ",
                        flush=True,
                    )

                    try:
                        product = await crawler.fetch_basic_product(product_id)

                        if not is_allowed_gender(product):
                            rejected[product_key] = reason_record(
                                category=category,
                                product_id=product_id,
                                reason="gender_not_allowed",
                                detail=product.get("gender"),
                            )
                            save_rejected(rejected_path, rejected)
                            print("REJECT gender")
                            continue

                        if not is_target_category(product, category):
                            rejected[product_key] = reason_record(
                                category=category,
                                product_id=product_id,
                                reason="category_mismatch",
                                detail={
                                    "expected": category,
                                    "actual": logical_category(product),
                                    "raw": product.get("category"),
                                },
                            )
                            save_rejected(rejected_path, rejected)
                            print("REJECT category")
                            continue

                        primary = attach_primary_season(
                            product,
                            category,
                            progress,
                        )

                        if primary is None:
                            rejected[product_key] = reason_record(
                                category=category,
                                product_id=product_id,
                                reason="no_available_season_quota",
                                detail=product.get("season"),
                            )
                            save_rejected(rejected_path, rejected)
                            print("REJECT season")
                            continue

                        product = await crawler.attach_actual_size(product)

                        product.setdefault("dataset", {})
                        product["dataset"].update(
                            {
                                "logical_category": category,
                                "primary_season": primary,
                                "gender_scope": "MALE_AND_UNISEX",
                            }
                        )

                        product_path.parent.mkdir(
                            parents=True,
                            exist_ok=True,
                        )
                        product_path.write_text(
                            json.dumps(
                                product,
                                ensure_ascii=False,
                                indent=2,
                            ),
                            encoding="utf-8",
                        )

                        progress[category][primary] += 1
                        save_progress(progress_path, progress)

                        if product_key in failed:
                            failed.pop(product_key, None)
                            save_failed(failed_path, failed)

                        saved_this_run += 1

                        collection = (
                            product.get("season", {})
                            .get("collection", {})
                            .get("type")
                        )

                        print(
                            f"SAVED "
                            f"gender={product['gender']['normalized']} "
                            f"season={primary} "
                            f"collection={collection}"
                        )

                    except Exception as exc:
                        failed[product_key] = reason_record(
                            category=category,
                            product_id=product_id,
                            reason="crawl_failed",
                            detail=str(exc),
                        )
                        save_failed(failed_path, failed)
                        print(f"FAILED {type(exc).__name__}")

                counts = progress[category]
                print(
                    "  progress: "
                    + ", ".join(
                        f"{season}={counts[season]}/"
                        f"{CATEGORY_CONFIG[category]['season_quota'][season]}"
                        for season in counts
                    )
                )

        finally:
            await crawler.close()

    print("\n=== Batch collector finished ===")
    print(f"새로 저장: {saved_this_run}")
    print(f"progress : {progress_path}")
    print(f"rejected : {rejected_path}")
    print(f"failed   : {failed_path}")
    print(f"전체 quota 완료: {all_complete(progress)}")


if __name__ == "__main__":
    asyncio.run(run())
