"""이미 만들어진 시드 상품 JSON에 소재·핏을 보강한다.

- 소재: 상품정보제공고시(essential) 응답의 ``제품 소재``/``소재`` (``musinsa_essential`` 규칙과 동일)
- 핏: 시드 JSON에 이미 있는 구조화 ``features["핏"]`` 값 (``musinsa_parser`` 규칙과 동일)

값이 없거나 "상세페이지 참조"면 추측하지 않고 null로 둔다. 원본 JSON의 다른 필드와 서식은 유지한다.

사용 예::

    # 저장해 둔 essential 응답으로 반영 (네트워크 없음)
    python -m src.backfill_seed_attributes --seed-dir <final_products> --essential <essential.json>

    # 무신사 API를 1초 간격으로 직접 조회해 반영
    python -m src.backfill_seed_attributes --seed-dir <final_products> --fetch --save-essential <essential.json>

``--essential`` 파일은 {product_id: essential API 응답 JSON} 형태다.
"""

from __future__ import annotations

import argparse
import json
import time
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Callable
from urllib.request import Request, urlopen

from src.musinsa_essential import ESSENTIAL_API_URL, apply_essential_material
from src.musinsa_parser import _normalize_fit

ATTRIBUTE_KEYS = ("material", "fit_type", "attribute_evidence")


def _with_attributes(product: dict[str, Any], attributes: dict[str, Any]) -> dict[str, Any]:
    """속성 키를 features 바로 뒤에 둔다(크롤러 출력 순서와 동일). 다른 키 순서는 유지."""
    result: dict[str, Any] = {}
    inserted = False
    for key, value in product.items():
        if key in ATTRIBUTE_KEYS:
            continue
        result[key] = value
        if key == "features":
            result.update(attributes)
            inserted = True
    if not inserted:
        result.update(attributes)
    return result


def backfill_product(product: dict[str, Any], essential_payload: object) -> dict[str, Any]:
    features = product.get("features") if isinstance(product.get("features"), dict) else {}
    fit_type, fit_evidence = _normalize_fit(features, None)

    working = {
        "material": product.get("material"),
        "attribute_evidence": dict(product.get("attribute_evidence") or {}),
    }
    apply_essential_material(working, essential_payload)
    evidence = working["attribute_evidence"]
    evidence["fit"] = fit_evidence

    return _with_attributes(
        product,
        {
            "material": working["material"],
            "fit_type": fit_type,
            "attribute_evidence": {"material": evidence.get("material"), "fit": fit_evidence},
        },
    )


def fetch_essential(product_id: str, timeout: float = 20.0) -> dict[str, Any] | None:
    request = Request(
        ESSENTIAL_API_URL.format(product_id=product_id),
        headers={
            "Accept": "application/json",
            "Referer": f"https://www.musinsa.com/products/{product_id}",
            "User-Agent": "Mozilla/5.0 (compatible; FitzzaSeedBackfill/1.0)",
        },
    )
    with urlopen(request, timeout=timeout) as response:
        payload = json.load(response)
    return payload if isinstance(payload, dict) else None


def run(
    seed_dir: Path,
    essentials: dict[str, Any],
    *,
    fetch: Callable[[str], dict[str, Any] | None] | None = None,
    interval: float = 1.0,
    sleep: Callable[[float], None] = time.sleep,
) -> dict[str, Any]:
    stats: dict[str, Counter] = defaultdict(Counter)
    files = sorted(seed_dir.glob("*/*.json"))
    for index, path in enumerate(files):
        raw = path.read_text(encoding="utf-8")
        product = json.loads(raw)
        product_id = str(product["product_id"])
        if product_id not in essentials and fetch is not None:
            if index:
                sleep(interval)
            try:
                essentials[product_id] = fetch(product_id)
            except Exception as error:  # 한 상품 실패가 전체를 멈추지 않게 한다.
                essentials[product_id] = None
                stats[path.parent.name]["fetch_failed"] += 1
                print(f"{product_id} essential 조회 실패: {error}")

        updated = backfill_product(product, essentials.get(product_id))
        category = path.parent.name
        stats[category]["products"] += 1
        stats[category]["material"] += updated["material"] is not None
        stats[category]["fit"] += updated["fit_type"] is not None
        evidence = updated["attribute_evidence"]["material"]
        stats[category]["material_not_declared"] += bool(evidence and not evidence.get("usable"))

        serialized = json.dumps(updated, ensure_ascii=False, indent=2)
        if raw.endswith("\n"):
            serialized += "\n"
        if serialized != raw:
            path.write_text(serialized, encoding="utf-8")

    total = Counter()
    for counter in stats.values():
        total.update(counter)
    return {"by_category": {key: dict(value) for key, value in sorted(stats.items())}, "total": dict(total)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--seed-dir", type=Path, required=True)
    parser.add_argument("--essential", type=Path, help="저장된 essential 응답 JSON")
    parser.add_argument("--fetch", action="store_true", help="없는 상품은 무신사 API를 직접 조회")
    parser.add_argument("--save-essential", type=Path, help="조회한 응답을 저장할 경로")
    parser.add_argument("--request-interval", type=float, default=1.0)
    args = parser.parse_args()

    essentials: dict[str, Any] = {}
    if args.essential:
        essentials = json.loads(args.essential.read_text(encoding="utf-8"))
    if not essentials and not args.fetch:
        parser.error("--essential 또는 --fetch 중 하나가 필요합니다.")

    report = run(
        args.seed_dir,
        essentials,
        fetch=fetch_essential if args.fetch else None,
        interval=args.request_interval,
    )
    if args.save_essential:
        args.save_essential.write_text(json.dumps(essentials, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
