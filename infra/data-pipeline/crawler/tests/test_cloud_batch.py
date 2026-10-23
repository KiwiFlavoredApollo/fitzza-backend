import unittest
from unittest.mock import patch

from src.cloud_batch import main, safe_checkpoint_path


class CloudBatchTests(unittest.TestCase):
    def test_checkpoint_path_is_confined(self):
        self.assertEqual(safe_checkpoint_path("data/screened/1.json").as_posix(), "data/screened/1.json")
        for value in ("../secret.json", "/data/a.json", "data/../../a.json", "data/a.txt", "data\\a.json"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                safe_checkpoint_path(value)

    def test_default_does_not_start_any_aws_or_crawler_calls(self):
        args = ["cloud_batch", "--bucket", "unused", "--run-id", "deployment-test",
                "--category", "상의", "--stage", "discover"]
        with patch("sys.argv", args), patch("src.cloud_batch.boto3.client") as aws, \
                patch("src.cloud_batch.subprocess.run") as crawler:
            main()
            aws.assert_not_called()
            crawler.assert_not_called()
