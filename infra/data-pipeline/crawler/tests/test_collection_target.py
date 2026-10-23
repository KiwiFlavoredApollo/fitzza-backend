import unittest

from src.collector.config import CATEGORY_CONFIG, TOTAL_TARGET


class CollectionTargetTest(unittest.TestCase):
    def test_three_thousand_products_preserve_category_ratios(self):
        ratios = {"상의": 30, "하의": 25, "신발": 20, "아우터": 15, "모자": 10}
        self.assertEqual(TOTAL_TARGET, 3000)
        for category, ratio in ratios.items():
            self.assertEqual(CATEGORY_CONFIG[category]["total_quota"], 3000 * ratio // 100)

    def test_season_quotas_are_balanced_and_sum_to_category_target(self):
        for config in CATEGORY_CONFIG.values():
            counts = list(config["season_quota"].values())
            self.assertEqual(sum(counts), config["total_quota"])
            self.assertLessEqual(max(counts) - min(counts), 1)
        self.assertFalse(CATEGORY_CONFIG["신발"]["season_balance_required"])
        self.assertFalse(CATEGORY_CONFIG["모자"]["season_balance_required"])
