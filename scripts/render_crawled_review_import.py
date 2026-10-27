"""Render validated crawler output as an idempotent psql import script."""

from __future__ import annotations

import argparse
import csv
import io
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class ImportRow:
    product_id: int
    source_review_id: int
    rating: int
    content: str
    image_url: str | None
    created_at: str


def parse_timestamp(value: object) -> str:
    if not isinstance(value, str):
        raise ValueError("created_at must be an ISO-8601 string")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("created_at must include a UTC offset")
    return parsed.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def parse_review(product_id: int, value: object) -> ImportRow:
    if not isinstance(value, dict):
        raise ValueError("review must be an object")
    source_review_id = value.get("source_review_id")
    if not isinstance(source_review_id, int) or source_review_id <= 0:
        raise ValueError("source_review_id must be a positive integer")
    try:
        rating = int(value.get("grade"))
    except (TypeError, ValueError) as error:
        raise ValueError("grade must be an integer") from error
    if rating < 1 or rating > 5:
        raise ValueError("grade must be between 1 and 5")
    content = value.get("content")
    if not isinstance(content, str) or not content.strip():
        raise ValueError("content must not be blank")
    if len(content) > 1000:
        raise ValueError("content must not exceed 1000 characters")
    if any(line == "\\." for line in content.splitlines()):
        raise ValueError("content must not contain a line consisting of \\.")

    image_urls = value.get("image_urls", [])
    if not isinstance(image_urls, list):
        raise ValueError("image_urls must be a list")
    image_url = next(
        (
            url
            for url in image_urls
            if isinstance(url, str) and url.startswith("https://")
        ),
        None,
    )
    if image_url is not None and len(image_url) > 500:
        raise ValueError("image URL must not exceed 500 characters")

    return ImportRow(
        product_id=product_id,
        source_review_id=source_review_id,
        rating=rating,
        content=content,
        image_url=image_url,
        created_at=parse_timestamp(value.get("created_at")),
    )


def load_rows(input_directory: Path, report_path: Path, skip_invalid: bool) -> tuple[list[ImportRow], list[str]]:
    report = json.loads(report_path.read_text(encoding="utf-8"))
    if report.get("schema_version") != 1:
        raise ValueError("unsupported report schema_version")
    if report.get("failed_product_count") != 0:
        raise ValueError("crawler report contains failed products")
    if report.get("completed_product_count") != report.get("selected_product_count"):
        raise ValueError("crawler report is incomplete")

    files = sorted(input_directory.glob("*.json"), key=lambda path: int(path.stem))
    if len(files) != report["completed_product_count"]:
        raise ValueError("crawler file count does not match the report")

    rows: list[ImportRow] = []
    skipped: list[str] = []
    identities: set[int] = set()
    for path in files:
        document = json.loads(path.read_text(encoding="utf-8"))
        if document.get("schema_version") != 1 or document.get("source") != "musinsa":
            raise ValueError(f"{path.name}: unsupported crawler document")
        product_id = int(document.get("product_id"))
        if product_id <= 0 or path.stem != str(product_id):
            raise ValueError(f"{path.name}: product identity mismatch")
        reviews = document.get("reviews")
        if not isinstance(reviews, list) or document.get("collected_review_count") != len(reviews):
            raise ValueError(f"{path.name}: review count mismatch")
        for index, review in enumerate(reviews):
            try:
                row = parse_review(product_id, review)
                if row.source_review_id in identities:
                    raise ValueError("duplicate source_review_id")
                identities.add(row.source_review_id)
                rows.append(row)
            except (TypeError, ValueError) as error:
                message = f"{path.name}:reviews[{index}]: {error}"
                if not skip_invalid:
                    raise ValueError(message) from error
                skipped.append(message)

    if not rows:
        raise ValueError("no valid reviews found")
    return rows, skipped


def render_sql(rows: list[ImportRow]) -> str:
    output = io.StringIO(newline="")
    output.write("\\set ON_ERROR_STOP on\nBEGIN;\n")
    output.write(
        "CREATE TEMP TABLE crawled_review_import ("
        "product_id bigint, source_review_id bigint, rating integer, content text, "
        "image_url varchar(500), created_at timestamptz) ON COMMIT DROP;\n"
    )
    output.write(
        "COPY crawled_review_import "
        "(product_id, source_review_id, rating, content, image_url, created_at) "
        "FROM STDIN WITH (FORMAT csv, HEADER true);\n"
    )
    writer = csv.writer(output, lineterminator="\n")
    writer.writerow(
        [
            "product_id",
            "source_review_id",
            "rating",
            "content",
            "image_url",
            "created_at",
        ]
    )
    for row in rows:
        writer.writerow([
            row.product_id,
            row.source_review_id,
            row.rating,
            row.content,
            row.image_url or "",
            row.created_at,
        ])
    output.write("\\.\n")
    output.write(
        "INSERT INTO review_service.reviews AS existing "
        "(user_id, product_id, order_item_id, source, source_review_id, rating, content, image_url, "
        "version, created_at, updated_at) "
        "SELECT NULL, product_id, NULL, 'MUSINSA', source_review_id, rating, content, "
        "NULLIF(image_url, ''), 0, created_at, created_at FROM crawled_review_import "
        "ON CONFLICT ON CONSTRAINT uk_reviews_source_identity DO UPDATE SET "
        "product_id = EXCLUDED.product_id, rating = EXCLUDED.rating, content = EXCLUDED.content, "
        "image_url = EXCLUDED.image_url, created_at = EXCLUDED.created_at, "
        "updated_at = EXCLUDED.updated_at, version = existing.version + 1;\n"
    )
    output.write("COMMIT;\n")
    return output.getvalue()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Validate review crawler output and render an idempotent psql import."
    )
    parser.add_argument(
        "--input",
        type=Path,
        required=True,
        help="directory containing one JSON file per product",
    )
    parser.add_argument("--report", type=Path, required=True, help="completed crawler report.json")
    parser.add_argument(
        "--output",
        type=Path,
        required=True,
        help="destination .sql file (keep outside Git)",
    )
    parser.add_argument(
        "--skip-invalid",
        action="store_true",
        help="exclude invalid reviews and report their count",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    rows, skipped = load_rows(args.input, args.report, args.skip_invalid)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render_sql(rows), encoding="utf-8", newline="\n")
    print(
        json.dumps(
            {"rendered": len(rows), "skipped": len(skipped), "output": str(args.output)},
            ensure_ascii=False,
        )
    )
    for message in skipped:
        print(message)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
