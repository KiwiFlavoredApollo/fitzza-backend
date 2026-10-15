import json
import tempfile
import unittest
from pathlib import Path

from scripts.prepare_jev_input import ContractError, build_jev_input


class PrepareJevInputTest(unittest.TestCase):
    def test_builds_sorted_unique_color_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self._write_product(
                root / "상의" / "2.json",
                product_id=2,
                name="셔츠",
                colors=[("BLACK", "https://img.example/black-2.jpg"), ("Ivory", "https://img.example/ivory.jpg")],
            )
            self._write_product(
                root / "상의" / "1.json",
                product_id=1,
                name="티셔츠",
                colors=[("BLACK", "https://img.example/black-1.jpg")],
            )

            result = build_jev_input(root)

            self.assertEqual(result["schema_version"], "1.0")
            self.assertEqual(result["source_product_count"], 2)
            self.assertEqual(result["products_with_colors"], 2)
            self.assertEqual(result["unique_color_count"], 2)
            self.assertEqual([item["color_raw"] for item in result["colors"]], ["BLACK", "Ivory"])
            black_contexts = result["colors"][0]["contexts"]
            self.assertEqual([item["product_id"] for item in black_contexts], [1, 2])
            self.assertEqual(black_contexts[1]["sibling_colors"], ["BLACK", "Ivory"])

    def test_rejects_color_without_verified_image_url(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self._write_product(root / "상의" / "1.json", 1, "티셔츠", [("BLACK", None)])

            with self.assertRaisesRegex(ContractError, "image_url"):
                build_jev_input(root)

    def test_skips_products_without_color_mapping(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self._write_product(root / "상의" / "1.json", 1, "티셔츠", [("BLACK", "https://img/1.jpg")])
            path = root / "모자" / "2.json"
            path.parent.mkdir(parents=True)
            path.write_text(json.dumps({
                "product_id": 2,
                "name": "볼캡",
                "category": {"categoryDepth1Title": "모자"},
                "color_image_mappings": None,
            }, ensure_ascii=False), encoding="utf-8")

            result = build_jev_input(root)

            self.assertEqual(result["source_product_count"], 2)
            self.assertEqual(result["products_with_colors"], 1)

    def _write_product(
            self,
            path: Path,
            product_id: int,
            name: str,
            colors: list[tuple[str, str | None]],
    ) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({
            "product_id": product_id,
            "name": name,
            "category": {"categoryDepth1Title": "상의"},
            "color_image_mappings": [
                {"color_raw": color, "image_url": image_url}
                for color, image_url in colors
            ],
        }, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
