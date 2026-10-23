import json
from pathlib import Path
import tempfile
import unittest

from build_seed_manifest import build_manifest


class SeedManifestTests(unittest.TestCase):
    def test_counts_and_pending_review(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "1.json").write_text(json.dumps({"product_id": 1,
                "dataset": {"logical_category": "상의"}}), encoding="utf-8")
            result = build_manifest(root)
            self.assertEqual(result["remaining_by_category"]["상의"], 899)
            self.assertEqual(result["unique_product_count"], 1)
            self.assertEqual(result["status"], "pending_quality_and_human_review")
            self.assertEqual(len(result["products"][0]["sha256"]), 64)

    def test_duplicate_ids_fail(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for name, identifier in (("a", 1), ("b", "001")):
                (root / f"{name}.json").write_text(json.dumps({"product_id": identifier,
                    "dataset": {"logical_category": "상의"}}), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                build_manifest(root)

    def test_empty_source_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, "No seed"):
                build_manifest(Path(folder))


if __name__ == "__main__":
    unittest.main()
