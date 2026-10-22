import unittest
from unittest.mock import patch

from src.jev_job import main


class JevJobTests(unittest.TestCase):
    def test_default_never_calls_jev_or_registers_secrets(self):
        args = ["jev_job", "--bucket", "unused", "--run-id", "deployment-only"]
        with patch("sys.argv", args), patch("boto3.client") as aws, \
                patch("src.jev_job.subprocess.run") as jev:
            main()
            aws.assert_not_called()
            jev.assert_not_called()
