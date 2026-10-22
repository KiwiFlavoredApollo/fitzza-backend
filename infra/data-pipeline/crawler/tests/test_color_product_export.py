from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from src.collector.color_product_export import export_dataset, export_product, split_label


def example_product() -> dict:
    return {
        "source": "musinsa",
        "product_id": 123,
        "name": "셔츠",
        "brand": {"id": "brand"},
        "dataset": {"logical_category": "상의"},
        "images": ["https://example.com/common.jpg"],
        "options": [
            {"name": "사이즈", "values": [{"name": "BLACK-M", "image_url": "https://example.com/swatch.jpg"}]}
        ],
        "option_items": [
            {"id": 1, "managed_code": "B-M", "values": ["BLACK-M"], "activated": True, "price_delta": 0},
            {"id": 2, "managed_code": "B-L", "values": ["BLACK-L"], "activated": False, "price_delta": 100},
            {"id": 3, "managed_code": "N-M", "values": ["NAVY-M"], "activated": True, "price_delta": 0},
            {"id": 4, "managed_code": "SET", "values": ["BLACK/WHITE SET", "M"], "activated": True},
        ],
        "size": {
            "unit": "cm",
            "measurements": [
                {"size": "BLACK-M", "measurements": {"총장": 70.0, "가슴단면": 55.0}},
                {"size": "BLACK-L", "measurements": {"총장": 0.0}},
                {"size": "NAVY-M", "measurements": {"총장": 71.0}},
            ],
        },
    }


class ColorProductExportTests(unittest.TestCase):
    def test_split_ambiguous_and_shoe_size(self) -> None:
        self.assertEqual(split_label("BLACK - M"), ("BLACK", "M"))
        self.assertEqual(split_label("225-230"), (None, "225-230"))
        self.assertEqual(split_label("카모 카키 - 베이비 핑크"), (None, None))

    def test_color_products_keep_raw_options_and_flat_measurements(self) -> None:
        source = example_product()
        original = json.dumps(source, sort_keys=True, ensure_ascii=False)
        products, unresolved, evidence = export_product(source)
        self.assertEqual(len(products), 2)
        self.assertEqual(len({item["product_id"] for item in products}), 2)
        self.assertEqual({item["id"] for item in unresolved}, {4})
        self.assertEqual(sum(len(item["sizes"]) for item in products), 3)
        self.assertEqual(original, json.dumps(source, sort_keys=True, ensure_ascii=False))
        black = next(item for item in products if item["color"]["raw"] == "BLACK")
        self.assertEqual(black["source_product_id"], 123)
        self.assertIsNone(black["thumbnail_url"])
        self.assertEqual(black["source_images"], source["images"])
        medium, large = black["sizes"]
        self.assertEqual((medium["size"], medium["총장"], medium["가슴단면"]), ("M", 70.0, 55.0))
        self.assertEqual(medium["raw_label"], "BLACK-M")
        self.assertNotIn("measurements", medium)
        self.assertEqual(large["measurement_status"], "invalid_zero_values")
        self.assertNotIn("총장", large)
        self.assertFalse(large["activated"])
        self.assertEqual(large["price_delta"], 100)
        self.assertTrue(any(item["thumbnail_candidates"] for item in evidence))

    def test_export_is_repeatable_and_keeps_unresolved_options(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source" / "상의"
            source.mkdir(parents=True)
            source_path = source / "123.json"
            source_path.write_text(json.dumps(example_product(), ensure_ascii=False), encoding="utf-8")
            before = source_path.read_bytes()
            output = root / "result"
            first = export_dataset(root / "source", output)
            second = export_dataset(root / "source", output)
            self.assertEqual(first, second)
            self.assertEqual(source_path.read_bytes(), before)
            self.assertEqual((first["candidate_options"], first["unresolved_options"]), (3, 1))
            self.assertEqual(len(list((output / "candidates").glob("*/*.json"))), 2)
            audit = json.loads((output / "audit" / "상의" / "123.json").read_text(encoding="utf-8"))
            self.assertEqual(audit["unresolved_options"][0]["id"], 4)

    def test_reordered_options_keep_ids_and_do_not_invent_combinations(self) -> None:
        source = example_product()
        first, _, _ = export_product(source)
        source["option_items"].reverse()
        source["size"]["measurements"].reverse()
        second, _, _ = export_product(source)
        self.assertEqual(first, second)
        pairs = {
            (item["color"]["raw"], row["size"])
            for item in first for row in item["sizes"]
        }
        self.assertEqual(pairs, {("BLACK", "M"), ("BLACK", "L"), ("NAVY", "M")})

    def test_shared_or_ambiguous_measurements_are_not_copied(self) -> None:
        source = example_product()
        source["size"]["measurements"] = [
            {"size": "M", "measurements": {"총장": 70.0}},
            {"size": "BLACK-L", "measurements": {"총장": 72.0}},
            {"size": "BLACK-L", "measurements": {"총장": 73.0}},
        ]
        products, _, _ = export_product(source)
        rows = {row["source_option_id"]: row for item in products for row in item["sizes"]}
        self.assertEqual(rows[1]["measurement_status"], "shared_scope_unverified")
        self.assertEqual(rows[2]["measurement_status"], "ambiguous")
        self.assertNotIn("총장", rows[1])
        self.assertNotIn("총장", rows[2])


if __name__ == "__main__":
    unittest.main()
