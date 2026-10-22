import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from scripts.crawl_product_reviews import (
    crawl_product,
    load_checkpoint,
    load_product_manifest,
    normalize_review,
    parse_page,
    write_json_atomic,
)


class FakeClient:
    def __init__(self, pages):
        self.pages = pages
        self.requests = []

    def fetch_page(self, product_id, page, page_size):
        self.requests.append((product_id, page, page_size))
        return self.pages[page]


def api_page(review_ids, total, page, total_pages):
    return {
        "meta": {"result": "SUCCESS"},
        "data": {
            "list": [
                {
                    "no": review_id,
                    "type": "general",
                    "content": f"review-{review_id}",
                    "grade": "5",
                    "goodsOption": "M",
                    "createDate": "2026-10-22T00:00:00+09:00",
                    "likeCount": 0,
                    "encryptedUserId": "must-not-be-stored",
                    "userProfileInfo": {"userNickName": "must-not-be-stored"},
                    "images": [{"image": f"/review/{review_id}.jpg"}],
                    "reviewSurveySatisfaction": {
                        "questions": [
                            {
                                "attribute": "사이즈 만족도",
                                "answers": [{"answerShortText": "적당함"}],
                            }
                        ]
                    },
                }
                for review_id in review_ids
            ],
            "total": total,
            "page": {"page": page, "totalPages": total_pages},
        },
    }


class ReviewCrawlerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).parent)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write_manifest(self, products=None):
        products = products or [{"product_id": "123"}]
        encoded = json.dumps(
            {
                "schema_version": 1,
                "status": "completed",
                "unique_product_count": len(products),
                "products": products,
            }
        ).encode()
        path = self.root / "manifest.json"
        path.write_bytes(encoded)
        return path, encoded

    def test_manifest_is_validated_and_fingerprinted(self):
        path, encoded = self.write_manifest()
        manifest = load_product_manifest(path)
        self.assertEqual(manifest.product_ids, ("123",))
        self.assertEqual(manifest.digest, hashlib.sha256(encoded).hexdigest())

    def test_duplicate_product_ids_are_rejected(self):
        path, _ = self.write_manifest([{"product_id": "123"}, {"product_id": 123}])
        with self.assertRaises(ValueError):
            load_product_manifest(path)

    def test_normalization_omits_user_identifiers_by_default(self):
        source = api_page([1], 1, 0, 1)["data"]["list"][0]
        normalized = normalize_review(source)
        serialized = json.dumps(normalized)
        self.assertNotIn("encryptedUserId", serialized)
        self.assertNotIn("userNickName", serialized)
        self.assertNotIn("image_urls", normalized)
        self.assertEqual(normalized["survey"][0]["answers"], ["적당함"])

    def test_images_are_opt_in_and_use_https(self):
        source = api_page([1], 1, 0, 1)["data"]["list"][0]
        normalized = normalize_review(source, include_image_urls=True)
        self.assertEqual(
            normalized["image_urls"], ["https://image.msscdn.net/review/1.jpg"]
        )

    def test_product_crawl_uses_zero_based_pages_and_cap(self):
        client = FakeClient(
            [api_page([1, 2], 5, 0, 3), api_page([3, 4], 5, 1, 3)]
        )
        result = crawl_product(client, "123", 2, 3, False)
        self.assertEqual(client.requests, [("123", 0, 2), ("123", 1, 2)])
        self.assertEqual(result["collected_review_count"], 3)
        self.assertTrue(result["truncated"])

    def test_invalid_page_contract_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_page({"data": {}})

    def test_checkpoint_refuses_another_manifest(self):
        checkpoint_path = self.root / "checkpoint.json"
        checkpoint_path.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "manifest_sha256": "old",
                    "completed_product_ids": [],
                    "failures": {},
                }
            ),
            encoding="utf-8",
        )
        with self.assertRaises(ValueError):
            load_checkpoint(checkpoint_path, "new")

    def test_atomic_write_retries_transient_replace_lock(self):
        target = self.root / "checkpoint.json"
        original_replace = Path.replace
        calls = []

        def flaky_replace(source, destination):
            calls.append(destination)
            if len(calls) < 3:
                raise PermissionError("file is being used by another process")
            return original_replace(source, destination)

        delays = []
        with mock.patch.object(Path, "replace", flaky_replace):
            write_json_atomic(target, {"ok": True}, sleep=delays.append)

        self.assertEqual(json.loads(target.read_text(encoding="utf-8")), {"ok": True})
        self.assertEqual(len(calls), 3)
        self.assertEqual(len(delays), 2)
        self.assertFalse((self.root / ".checkpoint.json.tmp").exists())

    def test_atomic_write_gives_up_after_persistent_lock(self):
        target = self.root / "checkpoint.json"

        def locked_replace(source, destination):
            raise PermissionError("file is being used by another process")

        with mock.patch.object(Path, "replace", locked_replace):
            with self.assertRaises(PermissionError):
                write_json_atomic(target, {"ok": True}, sleep=lambda _: None)


if __name__ == "__main__":
    unittest.main()
