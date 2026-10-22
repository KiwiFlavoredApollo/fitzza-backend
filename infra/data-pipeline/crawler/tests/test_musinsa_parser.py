import json
import unittest

from src.musinsa_parser import normalize_musinsa_product


def page(detail):
    data = {
        "props": {
            "pageProps": {
                "dehydratedState": {
                    "queries": [
                        {
                            "queryKey": ["Detail", 123],
                            "state": {"data": {"data": detail}},
                        },
                        {
                            "queryKey": ["Options", 123],
                            "state": {"data": {"data": {}}},
                        },
                    ]
                }
            }
        }
    }
    return f'<script id="__NEXT_DATA__" type="application/json">{json.dumps(data)}</script>'


def detail(materials=None, contents=None):
    return {
        "goodsNo": 123,
        "goodsNm": "상품",
        "brandInfo": {},
        "goodsPrice": {},
        "goodsReview": {},
        "goodsMaterial": {"materials": materials or []},
        "goodsContents": contents,
    }


class MusinsaParserAttributeTests(unittest.TestCase):
    def test_selected_material_and_fit_are_normalized(self):
        groups = [
            {"name": "소재", "items": [{"name": "면", "isSelected": True}]},
            {"name": "핏", "items": [{"name": "오버 사이즈", "isSelected": True}]},
        ]
        product = normalize_musinsa_product(page(detail(groups)), "https://example/123")
        self.assertEqual(product["material"], "면")
        self.assertEqual(product["fit_type"], "OVERSIZED")
        self.assertEqual(product["attribute_evidence"]["fit"]["source"], "goodsMaterial")

    def test_explicit_detail_text_is_fit_fallback(self):
        product = normalize_musinsa_product(
            page(detail(contents="<p>드랍숄더 패턴의 오버핏 제품입니다.</p>")),
            "https://example/123",
        )
        self.assertEqual(product["fit_type"], "OVERSIZED")
        self.assertEqual(
            product["attribute_evidence"]["fit"]["source"],
            "goodsContents_explicit_text",
        )

    def test_missing_attributes_remain_unknown(self):
        product = normalize_musinsa_product(page(detail()), "https://example/123")
        self.assertIsNone(product["material"])
        self.assertIsNone(product["fit_type"])


if __name__ == "__main__":
    unittest.main()
