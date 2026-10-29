"""Check the approved catalog's S3 objects, representative images, and Qdrant index."""

from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

import boto3
from botocore.config import Config


MAX_FAILURE_SAMPLES = 100
HTTP_TIMEOUT_SECONDS = 10


def _s3() -> Any:
    return boto3.client(
        "s3",
        config=Config(
            retries={"mode": "standard", "total_max_attempts": 3},
            connect_timeout=5,
            read_timeout=30,
        ),
    )


def _read_object(client: Any, bucket: str, key: str) -> bytes:
    response = client.get_object(Bucket=bucket, Key=key)
    body = response["Body"]
    try:
        return body.read()
    finally:
        body.close()


def _image_url(product: dict[str, Any]) -> str | None:
    for mapping in product.get("color_image_mappings") or []:
        if isinstance(mapping, dict) and isinstance(mapping.get("image_url"), str):
            return mapping["image_url"]
    images = product.get("images") or []
    return images[0] if images and isinstance(images[0], str) else None


def _image_is_reachable(url: str) -> bool:
    headers = {"User-Agent": "fitzza-integrity-check/1.0"}
    for method in ("HEAD", "GET"):
        request = Request(url, headers=headers, method=method)
        if method == "GET":
            request.add_header("Range", "bytes=0-0")
        try:
            with urlopen(request, timeout=HTTP_TIMEOUT_SECONDS) as response:
                content_type = response.headers.get("Content-Type", "").lower()
                if response.status < 200 or response.status >= 300:
                    continue
                if not content_type or content_type.startswith("image/"):
                    return True
        except (HTTPError, URLError, TimeoutError):
            continue
    return False


def _qdrant_points(url: str, collection: str) -> int | None:
    request = Request(f"{url.rstrip('/')}/collections/{collection}", method="GET")
    try:
        with urlopen(request, timeout=HTTP_TIMEOUT_SECONDS) as response:
            if response.status != 200:
                return None
            payload = json.loads(response.read())
    except (HTTPError, URLError, TimeoutError, json.JSONDecodeError):
        return None
    points = (payload.get("result") or {}).get("points_count")
    return points if isinstance(points, int) else None


def _object_key(manifest_key: str, record: dict[str, Any]) -> str:
    explicit = record.get("s3_key") or record.get("object_key") or record.get("object_path")
    if isinstance(explicit, str) and explicit:
        return explicit
    digest = record.get("sha256")
    if not isinstance(digest, str) or len(digest) != 64:
        raise ValueError("manifest record is missing sha256")
    parent = manifest_key.rsplit("/", 1)[0]
    return f"{parent}/objects/{digest}"


def check_catalog(client: Any, bucket: str, manifest_key: str, qdrant_url: str, collection: str) -> dict[str, Any]:
    manifest = json.loads(_read_object(client, bucket, manifest_key))
    products = manifest.get("products")
    if not isinstance(products, list) or not products:
        raise ValueError("baseline manifest must have non-empty products")

    missing_objects: list[str] = []
    invalid_products: list[str] = []
    inaccessible_images: list[str] = []
    missing_object_count = invalid_image_reference_count = unreachable_image_count = 0
    for record in products:
        product_id = str(record.get("product_id", "")) if isinstance(record, dict) else ""
        try:
            raw = _read_object(client, bucket, _object_key(manifest_key, record))
            product = json.loads(raw)
            if str(product.get("product_id")) != product_id:
                raise ValueError("product id mismatch")
        except Exception:
            missing_object_count += 1
            if len(missing_objects) < MAX_FAILURE_SAMPLES:
                missing_objects.append(product_id)
            continue
        image = _image_url(product)
        if not image:
            invalid_image_reference_count += 1
            if len(invalid_products) < MAX_FAILURE_SAMPLES:
                invalid_products.append(product_id)
        elif not _image_is_reachable(image):
            unreachable_image_count += 1
            if len(inaccessible_images) < MAX_FAILURE_SAMPLES:
                inaccessible_images.append(product_id)

    points = _qdrant_points(qdrant_url, collection)
    report = {
        "schema_version": 1,
        "checked_at": datetime.now(timezone.utc).isoformat(),
        "product_count": len(products),
        "s3": {"missing_object_count": missing_object_count, "sample_product_ids": missing_objects},
        "product": {"invalid_image_reference_count": invalid_image_reference_count, "sample_product_ids": invalid_products},
        "image": {"unreachable_count": unreachable_image_count, "sample_product_ids": inaccessible_images},
        "qdrant": {"collection": collection, "points_count": points, "minimum_expected_points": len(products)},
    }
    report["ok"] = (
        missing_object_count == 0
        and invalid_image_reference_count == 0
        and unreachable_image_count == 0
        and points is not None
        and points >= len(products)
    )
    return report


def main() -> int:
    bucket = os.environ["PIPELINE_S3_BUCKET"]
    manifest_key = os.environ["PIPELINE_BASELINE_MANIFEST_KEY"]
    prefix = os.environ["PIPELINE_S3_PREFIX"].rstrip("/")
    client = _s3()
    report = check_catalog(
        client,
        bucket,
        manifest_key,
        os.environ.get("QDRANT_URL", "http://qdrant:6333"),
        os.environ.get("QDRANT_COLLECTION", "musinsa_products"),
    )
    client.put_object(
        Bucket=bucket,
        Key=f"{prefix}/integrity/report.json",
        Body=json.dumps(report, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
        ContentType="application/json",
        ServerSideEncryption="AES256",
    )
    print(json.dumps({"ok": report["ok"], "product_count": report["product_count"]}))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
