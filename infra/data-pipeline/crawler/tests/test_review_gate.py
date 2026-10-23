import json
from pathlib import Path
import tempfile
import unittest

from src.review_gate import approve, verify


class ReviewGateTests(unittest.TestCase):
    def bundle(self, root):
        (root / "data").mkdir()
        (root / "config").mkdir()
        (root / "data/1.json").write_text(json.dumps({"product_id": 1,
            "color_image_mappings": [{"color_raw": "BK"}]}), encoding="utf-8")
        (root / "config/color_map.json").write_text('{"bk":"black"}', encoding="utf-8")
        (root / "config/color_map_review.csv").write_text("color_raw\nBK\n", encoding="utf-8")
        (root / "config/reviewed_colors.json").write_text('{"bk":"black"}', encoding="utf-8")

    def test_approved_bundle_verifies(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.bundle(root)
            approve(root)
            verify(root)

    def test_pending_review_blocks_approval(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.bundle(root)
            (root / "config/reviewed_colors.json").write_text("{}", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "incomplete"):
                approve(root)

    def test_changed_product_invalidates_approval(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.bundle(root)
            approve(root)
            path = root / "data/1.json"
            product = json.loads(path.read_text(encoding="utf-8"))
            product["name"] = "changed"
            path.write_text(json.dumps(product), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "changed"):
                verify(root)

    def test_overrides_are_not_automatic_human_approval(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.bundle(root)
            (root / "config/color_map_review.csv").write_text("color_raw\n", encoding="utf-8")
            (root / "config/reviewed_colors.json").write_text("{}", encoding="utf-8")
            (root / "config/color_map_overrides.json").write_text('{"bk":"black"}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "incomplete"):
                approve(root)
