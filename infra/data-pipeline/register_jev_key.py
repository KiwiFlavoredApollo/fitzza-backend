"""Explicit operator-only secret registration; never called by deployment."""

import getpass
import sys

import boto3
from botocore.config import Config

PARAMETER = "/fitzza/prod/data-pipeline/typesafe-api-key"


def main() -> None:
    if not sys.stdin.isatty():
        raise ValueError("Use an interactive terminal; refusing an echoed or piped secret")
    value = getpass.getpass("TYPESAFE_API_KEY (hidden, new parameter only): ").strip()
    if not value:
        raise ValueError("Empty key; nothing registered")
    session = boto3.Session(profile_name="fitzza", region_name="ap-northeast-2")
    client = session.client("ssm", config=Config(connect_timeout=5, read_timeout=15,
        retries={"mode": "standard", "total_max_attempts": 3}))
    client.put_parameter(Name=PARAMETER, Type="SecureString", Tier="Standard",
                         Value=value, Overwrite=False)
    print("SecureString registered; value not displayed. No Jev request was made.")


if __name__ == "__main__":
    main()
