import ast
from pathlib import Path
import unittest
from unittest.mock import Mock


class IndexerContractTests(unittest.TestCase):
    def setUp(self):
        tree = ast.parse((Path(__file__).parent / "index_to_qdrant.py").read_text(encoding="utf-8"))
        selected = [node for node in tree.body if isinstance(node, ast.FunctionDef)
                    and node.name in {"normalize_measurements", "make_payload", "load_image"}]
        self.scope = {"requests": Mock()}
        exec(compile(ast.Module(body=selected, type_ignores=[]), "indexer-functions", "exec"), self.scope)

    def test_measurement_contract_preserved(self):
        result = self.scope["normalize_measurements"]("상의", {"어깨너비": "44", "총장": "70", "가슴단면": -1})
        self.assertEqual(result, {"shoulder": 44.0, "top_length": 70.0})

    def test_payload_preserves_size_season_and_color_fields(self):
        product = {"product_id": 12, "name": "top", "dataset": {"logical_category": "상의"},
                   "season": {"primary_candidates": ["봄"]},
                   "size": {"measurements": [{"size": "M", "measurements": {"어깨너비": 44}}]}}
        payload = self.scope["make_payload"](product, "https://image.msscdn.net/a.jpg", "BK", "black")
        self.assertEqual(payload["product_id"], 12)
        self.assertEqual(payload["color_normalized"], ["black"])
        self.assertEqual(payload["season_candidates"], ["봄"])
        self.assertEqual(payload["sizes"][0]["measurements"], {"shoulder": 44.0})

    def test_internal_and_unapproved_image_hosts_are_blocked(self):
        for url in ("http://169.254.169.254/", "https://localhost/a.jpg", "https://example.com/a.jpg"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                self.scope["load_image"](url)
        self.scope["requests"].get.assert_not_called()

    def test_no_collection_delete_call(self):
        tree = ast.parse((Path(__file__).parent / "index_to_qdrant.py").read_text(encoding="utf-8"))
        self.assertFalse(any(isinstance(node, ast.Attribute) and node.attr == "delete_collection"
                             for node in ast.walk(tree)))
