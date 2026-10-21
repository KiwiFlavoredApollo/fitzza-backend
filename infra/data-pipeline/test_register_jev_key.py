import unittest
from unittest.mock import patch

import register_jev_key


class RegisterJevKeyTests(unittest.TestCase):
    def test_registration_uses_secure_string_without_overwrite(self):
        with patch("sys.stdin.isatty", return_value=True), \
                patch.object(register_jev_key.getpass, "getpass", return_value="not-a-real-secret"), \
                patch.object(register_jev_key.boto3, "Session") as session:
            register_jev_key.main()
            session.return_value.client.return_value.put_parameter.assert_called_once_with(
                Name=register_jev_key.PARAMETER, Type="SecureString", Tier="Standard",
                Value="not-a-real-secret", Overwrite=False)

    def test_noninteractive_input_is_refused(self):
        with patch("sys.stdin.isatty", return_value=False), \
                patch.object(register_jev_key.getpass, "getpass") as prompt, \
                patch.object(register_jev_key.boto3, "Session") as session:
            with self.assertRaises(ValueError):
                register_jev_key.main()
            prompt.assert_not_called()
            session.assert_not_called()

    def test_empty_key_never_calls_aws(self):
        with patch("sys.stdin.isatty", return_value=True), \
                patch.object(register_jev_key.getpass, "getpass", return_value=" "), \
                patch.object(register_jev_key.boto3, "Session") as session:
            with self.assertRaises(ValueError):
                register_jev_key.main()
            session.assert_not_called()
