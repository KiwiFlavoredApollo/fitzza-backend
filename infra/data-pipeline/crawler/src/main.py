from __future__ import annotations

import argparse
import asyncio
import json
from datetime import datetime
from pathlib import Path

from src.crawler import crawl_product


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="무신사 상품 상세 페이지 수집")
    parser.add_argument("--url", required=True)
    parser.add_argument("--headful", action="store_true")
    return parser.parse_args()


async def run() -> None:
    args = parse_args()

    raw, normalized, html, network, body_text = await crawl_product(
        url=args.url,
        headless=not args.headful,
    )

    output_dir = Path("data/raw")
    output_dir.mkdir(parents=True, exist_ok=True)

    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    paths = {
        "raw": output_dir / f"raw_{stamp}.json",
        "product": output_dir / f"product_{stamp}.json",
        "html": output_dir / f"product_{stamp}.html",
        "network": output_dir / f"network_{stamp}.json",
        "text": output_dir / f"page_text_{stamp}.txt",
    }

    paths["raw"].write_text(
        json.dumps(raw, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    paths["product"].write_text(
        json.dumps(normalized, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    paths["html"].write_text(html, encoding="utf-8")
    paths["network"].write_text(
        json.dumps(network, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    paths["text"].write_text(body_text, encoding="utf-8")

    print(f"[OK] HTTP        : {raw['http_status']}")
    print(f"[OK] product     : {normalized['name']}")
    print(f"[OK] product JSON: {paths['product']}")
    print(f"[OK] network JSON: {paths['network']}")
    print(f"[OK] page text   : {paths['text']}")


if __name__ == "__main__":
    asyncio.run(run())
