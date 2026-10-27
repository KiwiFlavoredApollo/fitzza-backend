import json
import tempfile
import unittest
from pathlib import Path

from src.backfill_seed_attributes import backfill_product, run


def essential(name, value):
    return {"meta": {"result": "SUCCESS"}, "data": {"essentials": [{"name": name, "value": value, "seq": 1}]}}


def seed(product_id, features):
    return {"product_id": product_id, "name": "상품", "features": features, "size": {"available": True}}


class BackfillSeedAttributesTests(unittest.TestCase):
    def test_adds_material_and_fit_after_features(self):
        product = backfill_product(seed(1, {"핏": ["루즈"], "두께": ["보통"]}), essential("제품 소재", "면 100%"))
        self.assertEqual(product["material"], "면 100%")
        self.assertEqual(product["fit_type"], "RELAXED")
        self.assertEqual(list(product), ["product_id", "name", "features", "material", "fit_type", "attribute_evidence", "size"])
        self.assertEqual(product["attribute_evidence"]["fit"], {"raw": "루즈", "source": "goodsMaterial"})

    def test_missing_values_stay_null(self):
        product = backfill_product(seed(2, {"두께": ["보통"]}), essential("소재", "상세페이지 참조"))
        self.assertIsNone(product["material"])
        self.assertIsNone(product["fit_type"])
        self.assertFalse(product["attribute_evidence"]["material"]["usable"])
        product = backfill_product(seed(3, {}), None)
        self.assertIsNone(product["attribute_evidence"]["material"])

    def test_rerun_is_idempotent_and_keeps_format(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "top" / "1.json"
            path.parent.mkdir()
            path.write_text(json.dumps(seed(1, {"핏": ["슬림"]}), ensure_ascii=False, indent=2), encoding="utf-8")
            essentials = {"1": essential("제품 소재", "폴리에스터 100%")}

            report = run(Path(directory), essentials)
            first = path.read_text(encoding="utf-8")
            run(Path(directory), essentials)

            self.assertEqual(path.read_text(encoding="utf-8"), first)
            self.assertFalse(first.endswith("\n"))
            self.assertEqual(report["total"], {"products": 1, "material": 1, "fit": 1, "material_not_declared": 0})

    def test_fetch_is_used_only_for_missing_products(self):
        with tempfile.TemporaryDirectory() as directory:
            for product_id in ("1", "2"):
                path = Path(directory) / "cap" / f"{product_id}.json"
                path.parent.mkdir(exist_ok=True)
                path.write_text(json.dumps(seed(int(product_id), {})), encoding="utf-8")
            calls = []
            essentials = {"1": essential("소재", "코튼 100%")}

            run(Path(directory), essentials, fetch=lambda pid: calls.append(pid) or essential("소재", "울 100%"), sleep=lambda _: None)

            self.assertEqual(calls, ["2"])
            self.assertEqual(json.loads((Path(directory) / "cap" / "2.json").read_text())["material"], "울 100%")


if __name__ == "__main__":
    unittest.main()
