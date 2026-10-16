#!/usr/bin/env python3
"""Exercise Qdrant collection, index, search, snapshot, and recovery APIs."""

from __future__ import annotations

import argparse
import json
import os
import urllib.error
import urllib.parse
import urllib.request
import uuid


SOURCE_COLLECTION = "fitzza_pipeline_smoke"
RESTORE_COLLECTION = "fitzza_pipeline_smoke_restore"
VECTOR_SIZE = 512


def request(base_url: str, method: str, path: str, body: dict | None = None) -> dict:
    headers = {"Content-Type": "application/json"}
    api_key = os.getenv("QDRANT_API_KEY")
    if api_key:
        headers["api-key"] = api_key
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"{base_url.rstrip('/')}{path}", data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def safe_delete(base_url: str, collection: str) -> None:
    if collection not in {SOURCE_COLLECTION, RESTORE_COLLECTION}:
        raise ValueError(f"refusing to delete non-smoke collection: {collection}")
    try:
        request(base_url, "DELETE", f"/collections/{collection}")
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise


def run(base_url: str) -> None:
    safe_delete(base_url, RESTORE_COLLECTION)
    safe_delete(base_url, SOURCE_COLLECTION)

    request(base_url, "PUT", f"/collections/{SOURCE_COLLECTION}", {"vectors": {"size": VECTOR_SIZE, "distance": "Cosine"}})
    for field, schema in {
        "product_id": "keyword",
        "color_variant": "keyword",
        "normalized_color": "keyword",
        "brand": "keyword",
        "category": "keyword",
        "price": "integer",
    }.items():
        request(base_url, "PUT", f"/collections/{SOURCE_COLLECTION}/index", {"field_name": field, "field_schema": schema})

    point_id = str(uuid.uuid5(uuid.NAMESPACE_URL, "smoke-product|black|https://example.com/item.jpg"))
    vector = [0.0] * VECTOR_SIZE
    vector[0] = 1.0
    request(
        base_url,
        "PUT",
        f"/collections/{SOURCE_COLLECTION}/points?wait=true",
        {"points": [{"id": point_id, "vector": vector, "payload": {"product_id": "smoke-product", "color_variant": "black", "normalized_color": "black", "brand": "fitzza", "category": "outer", "price": 10000, "image_url": "https://example.com/item.jpg"}}]},
    )
    search = request(base_url, "POST", f"/collections/{SOURCE_COLLECTION}/points/search", {"vector": vector, "limit": 1, "with_payload": True})
    if search["result"][0]["id"] != point_id:
        raise RuntimeError("source collection search did not return the inserted point")

    snapshot = request(base_url, "POST", f"/collections/{SOURCE_COLLECTION}/snapshots")["result"]["name"]
    location = f"{base_url.rstrip('/')}/collections/{SOURCE_COLLECTION}/snapshots/{urllib.parse.quote(snapshot)}"
    request(base_url, "PUT", f"/collections/{RESTORE_COLLECTION}/snapshots/recover?wait=true", {"location": location, "priority": "snapshot"})
    restored = request(base_url, "GET", f"/collections/{RESTORE_COLLECTION}/points/{point_id}")
    if restored["result"]["payload"]["product_id"] != "smoke-product":
        raise RuntimeError("restored collection does not contain the inserted point")

    print(f"Qdrant smoke test passed: {SOURCE_COLLECTION} -> {RESTORE_COLLECTION}")


def verify_restored_collection(base_url: str) -> None:
    result = request(base_url, "GET", f"/collections/{RESTORE_COLLECTION}")["result"]
    if result["points_count"] < 1:
        raise RuntimeError("restored collection has no persisted points")
    print(f"Qdrant persistence check passed: {RESTORE_COLLECTION}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=os.getenv("QDRANT_URL", "http://localhost:6333"))
    parser.add_argument("--verify-restore", action="store_true")
    args = parser.parse_args()
    if args.verify_restore:
        verify_restored_collection(args.url)
    else:
        run(args.url)


if __name__ == "__main__":
    main()
