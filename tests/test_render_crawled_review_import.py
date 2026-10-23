import json
from pathlib import Path
import tempfile
import unittest

from scripts.render_crawled_review_import import load_rows, render_sql


class CrawledReviewImportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).parent)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.data = self.root / "data"
        self.data.mkdir()
        self.report = self.root / "report.json"
        self.report.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "selected_product_count": 1,
                    "completed_product_count": 1,
                    "failed_product_count": 0,
                }
            ),
            encoding="utf-8",
        )

    def write_product(self, reviews):
        (self.data / "123.json").write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "source": "musinsa",
                    "product_id": "123",
                    "collected_review_count": len(reviews),
                    "reviews": reviews,
                }
            ),
            encoding="utf-8",
        )

    def test_renders_idempotent_import_with_escaped_content(self):
        self.write_product(
            [
                {
                    "source_review_id": 77,
                    "grade": "5",
                    "content": "좋아요, \"정말\"\n편해요",
                    "created_at": "2026-10-22T12:00:00+09:00",
                }
            ]
        )

        rows, skipped = load_rows(self.data, self.report, False)
        sql = render_sql(rows)

        self.assertEqual(skipped, [])
        self.assertIn("좋아요, \"\"정말\"\"\n편해요", sql)
        self.assertIn("2026-10-22T03:00:00Z", sql)
        self.assertIn("ON CONFLICT ON CONSTRAINT uk_reviews_source_identity", sql)
        self.assertIn("BEGIN;", sql)
        self.assertIn("COMMIT;", sql)

    def test_invalid_reviews_fail_by_default(self):
        self.write_product(
            [
                {
                    "source_review_id": 77,
                    "grade": "0",
                    "content": "invalid",
                    "created_at": "2026-10-22T12:00:00+09:00",
                }
            ]
        )

        with self.assertRaisesRegex(ValueError, "grade must be between 1 and 5"):
            load_rows(self.data, self.report, False)

    def test_rejects_psql_copy_terminator_in_content(self):
        self.write_product(
            [
                {
                    "source_review_id": 77,
                    "grade": "5",
                    "content": "safe prefix\n\\.\nDROP TABLE reviews;",
                    "created_at": "2026-10-22T12:00:00+09:00",
                }
            ]
        )

        with self.assertRaisesRegex(ValueError, "line consisting"):
            load_rows(self.data, self.report, False)

    def test_skips_psql_copy_terminator_when_explicitly_requested(self):
        self.write_product(
            [
                {
                    "source_review_id": 77,
                    "grade": "5",
                    "content": "safe prefix\n\\.\nDROP TABLE reviews;",
                    "created_at": "2026-10-22T12:00:00+09:00",
                },
                {
                    "source_review_id": 78,
                    "grade": "5",
                    "content": "valid review",
                    "created_at": "2026-10-22T12:01:00+09:00",
                },
            ]
        )

        rows, skipped = load_rows(self.data, self.report, True)

        self.assertEqual([row.source_review_id for row in rows], [78])
        self.assertEqual(len(skipped), 1)
        self.assertIn("line consisting of \\.", skipped[0])

    def test_skip_invalid_requires_at_least_one_valid_review(self):
        self.write_product(
            [
                {
                    "source_review_id": 77,
                    "grade": "0",
                    "content": "invalid",
                    "created_at": "2026-10-22T12:00:00+09:00",
                }
            ]
        )

        with self.assertRaisesRegex(ValueError, "no valid reviews found"):
            load_rows(self.data, self.report, True)

    def test_rejects_incomplete_crawl_report(self):
        self.report.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "selected_product_count": 2,
                    "completed_product_count": 1,
                    "failed_product_count": 1,
                }
            ),
            encoding="utf-8",
        )

        with self.assertRaisesRegex(ValueError, "failed products"):
            load_rows(self.data, self.report, False)


if __name__ == "__main__":
    unittest.main()
