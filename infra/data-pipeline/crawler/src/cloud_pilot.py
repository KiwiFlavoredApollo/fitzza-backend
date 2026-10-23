"""Bounded cloud discovery/screening pilot; never marks a dataset approved."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

import boto3
from botocore.config import Config


def upload_checkpoints(s3, bucket: str, prefix: str) -> int:
    count = 0
    for path in sorted(Path("data").rglob("*.json")):
        if path.is_symlink():
            raise ValueError("Symlinks are not valid checkpoints")
        s3.upload_file(str(path), bucket, f"{prefix}/crawler/{path.as_posix()}")
        count += 1
    return count


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--category", choices=["상의", "하의", "신발", "아우터", "모자"], required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}", args.run_id):
        raise ValueError("Invalid run ID")
    prefix = f"pipeline/runs/{args.run_id}"
    s3 = boto3.client("s3", config=Config(connect_timeout=5, read_timeout=30,
        retries={"mode": "standard", "total_max_attempts": 3}))
    seed_path = Path("seed-manifest.json").resolve()
    s3.download_file(args.bucket, f"{prefix}/seed/manifest.json", str(seed_path))
    os.environ["SEED_MANIFEST"] = str(seed_path)
    report = {"run_id": args.run_id, "status": "failed", "approved_for_indexing": False}
    try:
        subprocess.run([sys.executable, "-m", "src.cli", "discover", "--category", args.category,
            "--page-size", "20", "--max-pages-per-sort", "2"], check=True, timeout=300)
        upload_checkpoints(s3, args.bucket, prefix)
        subprocess.run([sys.executable, "-m", "src.cli", "screen", "--category", args.category,
            "--chunk-size", "5", "--max-new", "5", "--delay", "0.8"], check=True, timeout=600)
        report["status"] = "pilot_finished_quality_review_pending"
    finally:
        report["checkpoint_files"] = upload_checkpoints(s3, args.bucket, prefix)
        s3.put_object(Bucket=args.bucket, Key=f"{prefix}/crawler/pilot-report.json",
            Body=json.dumps(report).encode("utf-8"), ContentType="application/json")
        print(json.dumps(report))


if __name__ == "__main__":
    main()
