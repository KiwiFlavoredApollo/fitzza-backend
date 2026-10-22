"""Collect privacy-minimized MUSINSA reviews after a product crawl completes.

The product manifest is the hand-off contract between the two stages.  A digest of
that manifest is stored in the checkpoint so a resumed run cannot silently switch
to a different product set.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


REVIEW_LIST_URL = "https://goods.musinsa.com/api2/review/v1/view/list"
IMAGE_BASE_URL = "https://image.msscdn.net"
SCHEMA_VERSION = 1


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


REPLACE_ATTEMPTS = 6
REPLACE_BASE_DELAY_SECONDS = 0.2


def write_json_atomic(
    path: Path, value: object, sleep: Callable[[float], None] = time.sleep
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    # Windows refuses to replace a file that another process (antivirus, the search
    # indexer, a reader on a shared folder) briefly holds open.  Retrying keeps one
    # transient lock from aborting a long batch run.
    for attempt in range(REPLACE_ATTEMPTS):
        try:
            temporary.replace(path)
            return
        except PermissionError:
            if attempt + 1 == REPLACE_ATTEMPTS:
                raise
            sleep(REPLACE_BASE_DELAY_SECONDS * 2**attempt)


@dataclass(frozen=True)
class ProductManifest:
    digest: str
    status: str | None
    product_ids: tuple[str, ...]


def load_product_manifest(path: Path) -> ProductManifest:
    raw = path.read_bytes()
    try:
        document = json.loads(raw)
    except json.JSONDecodeError as error:
        raise ValueError(f"invalid product manifest JSON: {error}") from error

    if not isinstance(document, dict) or document.get("schema_version") != 1:
        raise ValueError("product manifest schema_version must be 1")
    products = document.get("products")
    if not isinstance(products, list) or not products:
        raise ValueError("product manifest products must be a non-empty list")

    product_ids: list[str] = []
    for index, product in enumerate(products):
        if not isinstance(product, dict):
            raise ValueError(f"products[{index}] must be an object")
        product_id = str(product.get("product_id", ""))
        if not product_id.isdecimal():
            raise ValueError(f"products[{index}].product_id must contain only digits")
        product_ids.append(product_id)

    if len(product_ids) != len(set(product_ids)):
        raise ValueError("product manifest contains duplicate product IDs")
    expected_count = document.get("unique_product_count")
    if expected_count is not None and expected_count != len(product_ids):
        raise ValueError("unique_product_count does not match products")

    return ProductManifest(
        digest=hashlib.sha256(raw).hexdigest(),
        status=document.get("status"),
        product_ids=tuple(product_ids),
    )


def retry_after_seconds(value: str | None, now: Callable[[], datetime]) -> float | None:
    if value is None:
        return None
    try:
        return max(0.0, float(value))
    except ValueError:
        try:
            retry_at = parsedate_to_datetime(value)
            if retry_at.tzinfo is None:
                retry_at = retry_at.replace(tzinfo=timezone.utc)
            return max(0.0, (retry_at - now()).total_seconds())
        except (TypeError, ValueError, OverflowError):
            return None


class MusinsaReviewClient:
    def __init__(
        self,
        request_interval: float,
        timeout: float,
        max_attempts: int,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self.request_interval = request_interval
        self.timeout = timeout
        self.max_attempts = max_attempts
        self.sleep = sleep
        self.last_request_started: float | None = None

    def _wait_for_rate_limit(self) -> None:
        if self.last_request_started is None:
            return
        remaining = self.request_interval - (time.monotonic() - self.last_request_started)
        if remaining > 0:
            self.sleep(remaining)

    def fetch_page(self, product_id: str, page: int, page_size: int) -> dict[str, Any]:
        query = urlencode(
            {
                "page": page,
                "pageSize": page_size,
                "goodsNo": product_id,
                "sort": "new",
                "selectedSimilarNo": product_id,
                "myFilter": "false",
                "hasPhoto": "false",
                "isExperience": "false",
            }
        )
        request = Request(
            f"{REVIEW_LIST_URL}?{query}",
            headers={
                "Accept": "application/json",
                "Referer": f"https://www.musinsa.com/products/{product_id}",
                "User-Agent": "Mozilla/5.0 (compatible; FitzzaReviewCrawler/1.0)",
            },
        )

        for attempt in range(self.max_attempts):
            self._wait_for_rate_limit()
            self.last_request_started = time.monotonic()
            try:
                with urlopen(request, timeout=self.timeout) as response:
                    payload = json.load(response)
                if not isinstance(payload, dict):
                    raise ValueError("review API response must be an object")
                return payload
            except HTTPError as error:
                retryable = error.code == 429 or 500 <= error.code < 600
                if not retryable or attempt + 1 == self.max_attempts:
                    raise RuntimeError(f"review API returned HTTP {error.code}") from error
                retry_after_header = (
                    error.headers.get("Retry-After") if error.headers is not None else None
                )
                retry_after = retry_after_seconds(
                    retry_after_header, lambda: datetime.now(timezone.utc)
                )
                delay = retry_after if retry_after is not None else 2**attempt
            except (URLError, TimeoutError) as error:
                if attempt + 1 == self.max_attempts:
                    reason = getattr(error, "reason", str(error))
                    raise RuntimeError(f"review API request failed: {reason}") from error
                delay = 2**attempt
            self.sleep(delay + random.uniform(0.0, 0.25))

        raise AssertionError("unreachable")


def normalize_image_url(value: object) -> str | None:
    if not isinstance(value, str) or not value:
        return None
    if value.startswith("https://"):
        return value
    if value.startswith("/"):
        return f"{IMAGE_BASE_URL}{value}"
    return None


def normalize_review(review: object, include_image_urls: bool = False) -> dict[str, Any]:
    if not isinstance(review, dict):
        raise ValueError("review item must be an object")
    review_id = review.get("no")
    if not isinstance(review_id, int):
        raise ValueError("review item has no numeric review ID")

    normalized: dict[str, Any] = {
        "source_review_id": review_id,
        "review_type": review.get("type"),
        "content": review.get("content"),
        "grade": review.get("grade"),
        "goods_option": review.get("goodsOption"),
        "created_at": review.get("createDate"),
        "like_count": review.get("likeCount"),
    }

    survey = review.get("reviewSurveySatisfaction")
    questions = survey.get("questions", []) if isinstance(survey, dict) else []
    normalized["survey"] = [
        {
            "attribute": question.get("attribute"),
            "answers": [
                answer.get("answerShortText")
                for answer in question.get("answers", [])
                if isinstance(answer, dict) and answer.get("answerShortText") is not None
            ],
        }
        for question in questions
        if isinstance(question, dict)
    ]

    if include_image_urls:
        images = review.get("images") or []
        image_urls = []
        for image in images:
            if not isinstance(image, dict):
                continue
            url = normalize_image_url(image.get("image"))
            if url is not None:
                image_urls.append(url)
        normalized["image_urls"] = image_urls

    return normalized


def parse_page(payload: dict[str, Any]) -> tuple[list[object], int, int]:
    meta = payload.get("meta")
    if isinstance(meta, dict) and meta.get("result") not in (None, "SUCCESS"):
        raise RuntimeError(f"review API failed: {meta.get('errorCode') or meta.get('message')}")
    data = payload.get("data")
    if not isinstance(data, dict) or not isinstance(data.get("list"), list):
        raise ValueError("review API response has no data.list")
    total = data.get("total", len(data["list"]))
    page = data.get("page")
    total_pages = page.get("totalPages", 0) if isinstance(page, dict) else 0
    if not isinstance(total, int) or not isinstance(total_pages, int):
        raise ValueError("review API pagination fields must be integers")
    return data["list"], total, total_pages


def crawl_product(
    client: MusinsaReviewClient,
    product_id: str,
    page_size: int,
    max_reviews: int,
    include_image_urls: bool,
) -> dict[str, Any]:
    page_number = 0
    reported_total = 0
    seen_ids: set[int] = set()
    reviews: list[dict[str, Any]] = []

    while True:
        payload = client.fetch_page(product_id, page_number, page_size)
        items, reported_total, total_pages = parse_page(payload)
        for item in items:
            normalized = normalize_review(item, include_image_urls)
            review_id = normalized["source_review_id"]
            if review_id not in seen_ids:
                seen_ids.add(review_id)
                reviews.append(normalized)
            if max_reviews and len(reviews) >= max_reviews:
                break

        page_number += 1
        if (max_reviews and len(reviews) >= max_reviews) or not items:
            break
        if total_pages and page_number >= total_pages:
            break

    return {
        "schema_version": SCHEMA_VERSION,
        "source": "musinsa",
        "product_id": product_id,
        "collected_at": utc_now(),
        "reported_review_count": reported_total,
        "collected_review_count": len(reviews),
        "truncated": len(reviews) < reported_total,
        "includes_review_image_urls": include_image_urls,
        "reviews": reviews,
    }


def load_checkpoint(path: Path, manifest_digest: str) -> dict[str, Any]:
    if not path.exists():
        return {
            "schema_version": SCHEMA_VERSION,
            "manifest_sha256": manifest_digest,
            "completed_product_ids": [],
            "failures": {},
        }
    checkpoint = json.loads(path.read_text(encoding="utf-8"))
    if checkpoint.get("schema_version") != SCHEMA_VERSION:
        raise ValueError("unsupported review checkpoint schema_version")
    if checkpoint.get("manifest_sha256") != manifest_digest:
        raise ValueError("checkpoint belongs to a different product manifest")
    if not isinstance(checkpoint.get("completed_product_ids"), list):
        raise ValueError("checkpoint completed_product_ids must be a list")
    if not isinstance(checkpoint.get("failures"), dict):
        raise ValueError("checkpoint failures must be an object")
    return checkpoint


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be greater than zero")
    return parsed


def non_negative_int(value: str) -> int:
    parsed = int(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be zero or greater")
    return parsed


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Sequentially collect reviews for products in a completed crawl manifest."
    )
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--page-size", type=positive_int, default=20)
    parser.add_argument(
        "--max-reviews-per-product",
        type=non_negative_int,
        default=100,
        help="recent-review cap per product; use 0 only when an uncapped run is intended",
    )
    parser.add_argument("--product-limit", type=non_negative_int, default=0)
    parser.add_argument("--request-interval", type=float, default=1.0)
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--max-attempts", type=positive_int, default=4)
    parser.add_argument("--include-review-image-urls", action="store_true")
    parser.add_argument("--fail-fast", action="store_true")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    if args.request_interval < 0:
        raise ValueError("request interval must be zero or greater")
    if args.timeout <= 0:
        raise ValueError("timeout must be greater than zero")

    manifest = load_product_manifest(args.manifest)
    product_ids = list(manifest.product_ids)
    if args.product_limit:
        product_ids = product_ids[: args.product_limit]

    checkpoint_path = args.output / "checkpoint.json"
    checkpoint = load_checkpoint(checkpoint_path, manifest.digest)
    completed = set(map(str, checkpoint["completed_product_ids"]))
    failures: dict[str, Any] = checkpoint["failures"]
    client = MusinsaReviewClient(
        request_interval=args.request_interval,
        timeout=args.timeout,
        max_attempts=args.max_attempts,
    )
    completed_this_run = 0

    for index, product_id in enumerate(product_ids, start=1):
        if product_id in completed:
            continue
        print(f"[{index}/{len(product_ids)}] collecting reviews for product {product_id}")
        try:
            result = crawl_product(
                client,
                product_id,
                args.page_size,
                args.max_reviews_per_product,
                args.include_review_image_urls,
            )
            write_json_atomic(args.output / "data" / f"{product_id}.json", result)
            completed.add(product_id)
            failures.pop(product_id, None)
            completed_this_run += 1
        except Exception as error:  # Keep the batch restartable after one product fails.
            failures[product_id] = {
                "failed_at": utc_now(),
                "error_type": type(error).__name__,
                "message": str(error),
            }
            print(f"product {product_id} failed: {error}", file=sys.stderr)
        finally:
            checkpoint["completed_product_ids"] = sorted(completed, key=int)
            checkpoint["failures"] = failures
            checkpoint["updated_at"] = utc_now()
            write_json_atomic(checkpoint_path, checkpoint)
        if args.fail_fast and product_id in failures:
            break

    selected_failures = {key: value for key, value in failures.items() if key in product_ids}
    report = {
        "schema_version": SCHEMA_VERSION,
        "manifest_sha256": manifest.digest,
        "manifest_status": manifest.status,
        "finished_at": utc_now(),
        "selected_product_count": len(product_ids),
        "completed_product_count": sum(product_id in completed for product_id in product_ids),
        "completed_this_run": completed_this_run,
        "failed_product_count": len(selected_failures),
        "failed_product_ids": sorted(selected_failures, key=int),
        "max_reviews_per_product": args.max_reviews_per_product,
        "page_size": args.page_size,
        "request_interval_seconds": args.request_interval,
        "includes_review_image_urls": args.include_review_image_urls,
    }
    write_json_atomic(args.output / "report.json", report)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 1 if selected_failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
