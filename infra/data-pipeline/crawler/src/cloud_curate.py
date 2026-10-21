"""Explicitly authorized S3 checkpoint-to-curated handoff; default is network-free."""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import tempfile

from src.curate_dataset import prepare, write_bundle

MAX_JSON_BYTES = 2 * 1024 * 1024


def read_json(s3, bucket: str, key: str) -> dict:
    with s3.get_object(Bucket=bucket, Key=key)["Body"] as body:
        data = body.read(MAX_JSON_BYTES + 1)
    if len(data) > MAX_JSON_BYTES:
        raise ValueError("JSON exceeds size limit")
    value = json.loads(data)
    if not isinstance(value, dict):
        raise ValueError("Expected JSON object")
    return value


def restore_product(s3, bucket: str, key: str, digest: str, destination: Path) -> None:
    if not re.fullmatch(r"[a-f0-9]{64}", digest):
        raise ValueError("Invalid product digest")
    with s3.get_object(Bucket=bucket, Key=key)["Body"] as body:
        data = body.read(MAX_JSON_BYTES + 1)
    if len(data) > MAX_JSON_BYTES or hashlib.sha256(data).hexdigest() != digest:
        raise ValueError("Product size or digest mismatch")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("xb") as output:
        output.write(data)


def curate(s3, bucket: str, run_id: str, root: Path) -> dict:
    prefix = f"pipeline/runs/{run_id}"
    seed = read_json(s3, bucket, f"{prefix}/seed/manifest.json")
    records = seed["products"]
    if not 1 <= len(records) <= 3000:
        raise ValueError("Invalid seed size")
    seed_root, raw_root = root / "seed", root / "raw"
    seed_root.mkdir()
    raw_root.mkdir()
    seen = set()
    for record in records:
        identifier = record["product_id"]
        if not re.fullmatch(r"[1-9][0-9]*", identifier) or identifier in seen:
            raise ValueError("Invalid or duplicate seed ID")
        seen.add(identifier)
        digest = record["sha256"]
        restore_product(s3, bucket, f"{prefix}/seed/objects/{digest}", digest,
                        seed_root / f"{identifier}.json")
        product = json.loads((seed_root / f"{identifier}.json").read_text(encoding="utf-8"))
        if str(product.get("product_id")) != identifier:
            raise ValueError("Seed identity mismatch")
    checkpoint = read_json(s3, bucket, f"{prefix}/crawler/checkpoints.json")
    if not isinstance(checkpoint["files"], dict) or len(checkpoint["files"]) > 30000:
        raise ValueError("Invalid checkpoint inventory")
    for relative, digest in checkpoint["files"].items():
        path = PurePosixPath(relative)
        if (path.is_absolute() or ".." in path.parts or "\\" in relative or ":" in relative
                or not path.parts or path.parts[0] != "data" or path.suffix != ".json"):
            raise ValueError("Unsafe checkpoint path")
        # Candidates, rejected products and status manifests are never product inputs.
        if len(path.parts) != 4 or path.parts[1] != "screened":
            continue
        restore_product(s3, bucket, f"{prefix}/crawler/objects/{digest}", digest,
                        raw_root.joinpath(*path.parts[2:]))
    report, files = prepare([seed_root, raw_root])
    if not report["ready_for_jev"]:
        return report
    archive = root / "curated.tar.gz"
    write_bundle(archive, report, files)
    # Publication is create-only. Never replace a bundle consumed by Jev or human review.
    with archive.open("rb") as body:
        s3.put_object(Bucket=bucket, Key=f"{prefix}/curated/bundle.tar.gz", Body=body,
                      ContentType="application/gzip", IfNoneMatch="*")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--execute-approved-run", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}", args.run_id):
        raise ValueError("Invalid run ID")
    if not args.execute_approved_run:
        print(json.dumps({"mode": "deployment_only", "network_calls": 0}))
        return
    import boto3
    from botocore.config import Config

    s3 = boto3.client("s3", config=Config(connect_timeout=5, read_timeout=30,
        retries={"mode": "standard", "total_max_attempts": 3}))
    with tempfile.TemporaryDirectory() as folder:
        report = curate(s3, args.bucket, args.run_id, Path(folder))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if not report["ready_for_jev"]:
        raise ValueError("Curated dataset incomplete; nothing published and Jev not started")


if __name__ == "__main__":
    main()
