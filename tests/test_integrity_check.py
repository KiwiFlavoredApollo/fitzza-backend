import json
import sys
from pathlib import Path


sys.path.insert(0, str(Path(__file__).parents[1] / "data-pipeline-integrity"))
import integrity_check


class Body:
    def __init__(self, value):
        self.value = value
        self.closed = False

    def read(self):
        return self.value

    def close(self):
        self.closed = True


class FakeS3:
    def __init__(self, objects):
        self.objects = objects

    def get_object(self, *, Bucket, Key):
        return {"Body": Body(self.objects[Key])}


def test_integrity_checks_objects_images_and_qdrant(monkeypatch):
    manifest_key = "pipeline/current/manifest.json"
    digest = "a" * 64
    objects = {
        manifest_key: json.dumps({"products": [{"product_id": "101", "sha256": digest}]}).encode(),
        "pipeline/runs/collection-20261025-01/curated/data/모자/101.json": json.dumps({
            "product_id": 101,
            "color_image_mappings": [{"image_url": "https://image.example/101.jpg"}],
        }).encode(),
    }
    monkeypatch.setattr(integrity_check, "_image_is_reachable", lambda url: True)
    monkeypatch.setattr(integrity_check, "_qdrant_points", lambda url, collection: 1)

    objects[manifest_key] = json.dumps({"products": [{"product_id": "101", "sha256": digest, "s3_key": "pipeline/runs/collection-20261025-01/curated/data/모자/101.json"}]}).encode()
    report = integrity_check.check_catalog(FakeS3(objects), "bucket", manifest_key, "http://qdrant:6333", "products")

    assert report["ok"] is True
    assert report["qdrant"]["points_count"] == 1
    assert report["image"]["unreachable_count"] == 0


def test_integrity_fails_when_index_has_too_few_points(monkeypatch):
    manifest_key = "pipeline/current/manifest.json"
    digest = "b" * 64
    objects = {
        manifest_key: json.dumps({"products": [{"product_id": "101", "sha256": digest}]}).encode(),
        f"pipeline/current/objects/{digest}": json.dumps({"product_id": 101, "images": ["https://image.example/101.jpg"]}).encode(),
    }
    monkeypatch.setattr(integrity_check, "_image_is_reachable", lambda url: True)
    monkeypatch.setattr(integrity_check, "_qdrant_points", lambda url, collection: 0)

    report = integrity_check.check_catalog(FakeS3(objects), "bucket", manifest_key, "http://qdrant:6333", "products")

    assert report["ok"] is False
