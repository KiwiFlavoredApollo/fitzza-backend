import asyncio
import unittest

from src.collector.batch_crawler import BatchProductCrawler
from src.musinsa_essential import (
    MATERIAL_MAX_LENGTH,
    apply_essential_material,
    clean_material,
    extract_material,
)


def essential(*entries, result="SUCCESS"):
    return {
        "meta": {"result": result},
        "data": {
            "essentials": [
                {"name": name, "value": value, "seq": seq}
                for seq, (name, value) in enumerate(entries, start=1)
            ]
        },
    }


class EssentialMaterialTests(unittest.TestCase):
    def test_product_material_drops_tolerance_note(self):
        payload = essential(
            ("제품 소재", "겉감 면 70%, 나일론 30%(색상 또는 품번에 따라 기재된 혼용률에서 허용 오차 범위 내 차이가 있을 수 있습니다)"),
            ("색상", "네이비"),
        )
        material, evidence = extract_material(payload)
        self.assertEqual(material, "겉감 면 70%, 나일론 30%")
        self.assertEqual(evidence["source"], "goodsEssential")
        self.assertEqual(evidence["name"], "제품 소재")
        self.assertTrue(evidence["usable"])
        self.assertIn("허용 오차", evidence["raw"])

    def test_cap_uses_plain_material_name(self):
        payload = essential(("종류", "모자"), ("소재", "ACRYL 100%"), ("치수", "상품 Tag 참고"))
        self.assertEqual(extract_material(payload)[0], "ACRYL 100%")

    def test_line_breaks_become_separators(self):
        raw = "겉감 :  나일론 100%<br />\n안감 : 폴리에스터 100%<br />\n겉창 : EVA + 합성고무"
        self.assertEqual(
            clean_material(raw),
            "겉감 : 나일론 100% / 안감 : 폴리에스터 100% / 겉창 : EVA + 합성고무",
        )

    def test_placeholder_is_not_guessed(self):
        for raw in ("상세페이지 참조", "상세 페이지 참조", "상품 Tag 참고", "상품상세 참조", "-"):
            self.assertIsNone(clean_material(raw), raw)
        material, evidence = extract_material(essential(("소재", "상세페이지 참조")))
        self.assertIsNone(material)
        self.assertFalse(evidence["usable"])
        self.assertEqual(evidence["raw"], "상세페이지 참조")

    def test_missing_or_failed_response_has_no_evidence(self):
        self.assertEqual(extract_material(essential(("색상", "블랙"))), (None, None))
        self.assertEqual(extract_material(essential(("제품 소재", "면 100%"), result="FAIL")), (None, None))
        self.assertEqual(extract_material(None), (None, None))
        self.assertEqual(extract_material({"data": {"essentials": "bad"}}), (None, None))

    def test_material_fits_database_column(self):
        material = clean_material("면 " + "가" * 400)
        self.assertEqual(len(material), MATERIAL_MAX_LENGTH)
        self.assertTrue(material.endswith("…"))

    def test_apply_prefers_notice_and_keeps_structured_fallback(self):
        product = {"material": None, "attribute_evidence": {"material": None, "fit": None}}
        apply_essential_material(product, essential(("제품 소재", "Cotton 100%")))
        self.assertEqual(product["material"], "Cotton 100%")
        self.assertEqual(product["attribute_evidence"]["material"]["source"], "goodsEssential")

        structured = {"material": "면", "attribute_evidence": {"material": {"raw": "면", "source": "goodsMaterial"}}}
        apply_essential_material(structured, essential(("소재", "상세페이지 참조")))
        self.assertEqual(structured["material"], "면")
        self.assertEqual(structured["attribute_evidence"]["material"]["source"], "goodsMaterial")

        missing = {}
        apply_essential_material(missing, None)
        self.assertIsNone(missing["material"])
        self.assertIsNone(missing["attribute_evidence"]["material"])


class FakeResponse:
    def __init__(self, status, payload):
        self.status = status
        self.ok = 200 <= status < 300
        self.payload = payload

    async def json(self):
        return self.payload


class FakeRequest:
    def __init__(self, response):
        self.response = response
        self.urls = []

    async def get(self, url, headers=None, timeout=None):
        self.urls.append(url)
        return self.response


class FakePage:
    def __init__(self, response):
        self.request = FakeRequest(response)
        self.context = self

    @property
    def requests(self):
        return self.request.urls


class BatchCrawlerEssentialTests(unittest.TestCase):
    def crawler(self, response):
        crawler = BatchProductCrawler(playwright=None, delay_seconds=0)
        crawler.page = FakePage(response)
        return crawler

    def test_attach_calls_essential_api_for_the_same_product(self):
        crawler = self.crawler(FakeResponse(200, essential(("제품 소재", "폴리에스터 100%"))))
        product = asyncio.run(crawler.attach_essential_material({"product_id": 5234650, "material": None}))
        self.assertEqual(product["material"], "폴리에스터 100%")
        self.assertEqual(
            crawler.page.requests,
            ["https://goods-detail.musinsa.com/api2/goods/5234650/essential"],
        )

    def test_http_failure_keeps_null_unless_strict(self):
        crawler = self.crawler(FakeResponse(503, None))
        product = asyncio.run(crawler.attach_essential_material({"product_id": 1, "material": None}))
        self.assertIsNone(product["material"])
        with self.assertRaises(RuntimeError):
            asyncio.run(crawler.attach_essential_material({"product_id": 1}, strict=True))


if __name__ == "__main__":
    unittest.main()
