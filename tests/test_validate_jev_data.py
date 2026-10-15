import json
import tempfile
import unittest
from pathlib import Path

from scripts.validate_jev_data import ContractError, validate_jev_data


class ValidateJevDataTest(unittest.TestCase):
    def test_matches_jev_normalized_color_keys(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            root = Path(directory)
            self._write(root / "1.json", [" BLACK ", "Ivory"])
            self._write(root / "2.json", ["black"])

            result = validate_jev_data(root)

            self.assertEqual(result["source_product_count"], 2)
            self.assertEqual(result["products_with_colors"], 2)
            self.assertEqual(result["unique_color_count"], 2)

    def test_does_not_require_image_url(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            root = Path(directory)
            self._write(root / "1.json", ["navy"])
            self.assertEqual(validate_jev_data(root)["unique_color_count"], 1)

    def test_rejects_invalid_mapping(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            root = Path(directory)
            self._write(root / "1.json", [""])
            with self.assertRaisesRegex(ContractError, "color_raw"):
                validate_jev_data(root)

    @staticmethod
    def _write(path: Path, colors: list[str]) -> None:
        path.write_text(json.dumps({
            "name": "상품",
            "dataset": {"logical_category": "상의"},
            "color_image_mappings": [{"color_raw": color} for color in colors],
        }, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
