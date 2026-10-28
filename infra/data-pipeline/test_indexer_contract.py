import ast
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock


class IndexerContractTests(unittest.TestCase):
    def setUp(self):
        tree = ast.parse((Path(__file__).parent / "index_to_qdrant.py").read_text(encoding="utf-8"))
        selected = [node for node in tree.body if isinstance(node, ast.FunctionDef)
                    and node.name in {"normalize_measurements", "make_payload", "load_image", "load_product_manifest"}]
        self.scope = {"requests": Mock(), "json": json}
        exec(compile(ast.Module(body=selected, type_ignores=[]), "indexer-functions", "exec"), self.scope)

    def test_measurement_contract_preserved(self):
        result = self.scope["normalize_measurements"]("상의", {"어깨너비": "44", "총장": "70", "가슴단면": -1})
        self.assertEqual(result, {"shoulder": 44.0, "top_length": 70.0})

    def test_payload_preserves_size_season_and_color_fields(self):
        product = {"product_id": 12, "name": "top", "dataset": {"logical_category": "상의"},
                   "season": {"primary_candidates": ["봄"]},
                   "size": {"measurements": [{"size": "M", "measurements": {"어깨너비": 44}}]}}
        # 1) When s3_key is provided
        s3_key = "pipeline/runs/run-01/images/products/12/001.jpg"
        payload = self.scope["make_payload"](product, "https://image.msscdn.net/a.jpg", "BK", "black", s3_key=s3_key)
        self.assertEqual(payload["product_id"], 12)
        self.assertEqual(payload["color_normalized"], ["black"])
        self.assertEqual(payload["season_candidates"], ["봄"])
        self.assertEqual(payload["sizes"][0]["measurements"], {"shoulder": 44.0})
        self.assertEqual(payload["image_url"], s3_key)
        self.assertEqual(payload["image_key"], s3_key)
        self.assertEqual(payload["image_source_url"], "https://image.msscdn.net/a.jpg")

        # 2) Fallback when s3_key is None
        fallback_payload = self.scope["make_payload"](product, "https://image.msscdn.net/a.jpg", "BK", "black")
        self.assertEqual(fallback_payload["image_url"], "https://image.msscdn.net/a.jpg")
        self.assertIsNone(fallback_payload["image_key"])
        self.assertEqual(fallback_payload["image_source_url"], "https://image.msscdn.net/a.jpg")

    def test_load_product_manifest(self):
        load_manifest = self.scope["load_product_manifest"]
        # None or non-existent path
        self.assertEqual(load_manifest(None, 12), {})
        self.assertEqual(load_manifest(Path("/nonexistent/path"), 12), {})

        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            prod_dir = root / "12"
            prod_dir.mkdir(parents=True)
            # Missing manifest
            self.assertEqual(load_manifest(root, 12), {})

            # Valid manifest with local file
            (prod_dir / "001.jpg").write_bytes(b"dummy")
            manifest_data = {
                "images": [
                    {
                        "source_url": "https://image.msscdn.net/a.jpg",
                        "s3_key": "pipeline/runs/run-01/images/products/12/001.jpg"
                    },
                    {
                        "source_url": "https://image.msscdn.net/b.jpg",
                        "s3_key": "pipeline/runs/run-01/images/products/12/002.jpg"
                    }
                ]
            }
            (prod_dir / "manifest.json").write_text(json.dumps(manifest_data), encoding="utf-8")

            mapping = load_manifest(root, 12)
            self.assertIn("https://image.msscdn.net/a.jpg", mapping)
            entry_a = mapping["https://image.msscdn.net/a.jpg"]
            self.assertEqual(entry_a["s3_key"], "pipeline/runs/run-01/images/products/12/001.jpg")
            self.assertEqual(entry_a["local_path"], prod_dir / "001.jpg")

            entry_b = mapping["https://image.msscdn.net/b.jpg"]
            self.assertEqual(entry_b["s3_key"], "pipeline/runs/run-01/images/products/12/002.jpg")
            self.assertIsNone(entry_b["local_path"])  # 002.jpg was not created on disk

    def test_internal_and_unapproved_image_hosts_are_blocked(self):
        for url in ("http://169.254.169.254/", "https://localhost/a.jpg", "https://example.com/a.jpg"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                self.scope["load_image"](url)
        self.scope["requests"].get.assert_not_called()

    def test_no_collection_delete_call(self):
        tree = ast.parse((Path(__file__).parent / "index_to_qdrant.py").read_text(encoding="utf-8"))
        self.assertFalse(any(isinstance(node, ast.Attribute) and node.attr == "delete_collection"
                             for node in ast.walk(tree)))
