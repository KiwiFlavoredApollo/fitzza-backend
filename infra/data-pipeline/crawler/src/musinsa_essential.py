"""무신사 상품정보제공고시(essential) API 응답에서 소재를 추출한다.

상품 상세의 ``goodsMaterial``은 핏·두께 같은 선택형 속성표라 소재가 거의 없다.
소재는 공정위 상품정보제공고시 항목(``제품 소재``, 모자는 ``소재``)으로 별도 API에서 내려온다.
판매자가 등록한 원문을 그대로 근거로 남기고, 화면용 값은 안내 문구·HTML만 정리한다.
"상세페이지 참조"처럼 실제 소재가 아닌 값은 추측하지 않고 None으로 둔다.
"""

from __future__ import annotations

import html
import re
from typing import Any

ESSENTIAL_API_URL = "https://goods-detail.musinsa.com/api2/goods/{product_id}/essential"
ESSENTIAL_SOURCE = "goodsEssential"
# product.material 컬럼(varchar 200)과 맞춘다.
MATERIAL_MAX_LENGTH = 200

_MATERIAL_NAME = re.compile(r"^(?:제품\s*)?(?:주요\s*)?(?:소재|재질)")
_BREAK = re.compile(r"<\s*br\s*/?\s*>", re.IGNORECASE)
_TAG = re.compile(r"<[^>]+>")
_TOLERANCE_NOTE = re.compile(r"\(\s*색상\s*또는\s*품번에\s*따라[^)]*\)")
_PLACEHOLDER = re.compile(
    r"^(?:상품\s*)?(?:상세\s*(?:페이지|설명|정보|이미지|내용)?|상품\s*상세|상품\s*tag|tag|택|"
    r"라벨|케어\s*라벨|제품\s*라벨|이미지)\s*(?:참조|참고|확인)(?:\s*바랍니다)?\.?$",
    re.IGNORECASE,
)
_EMPTY_VALUES = {"-", "없음", "해당없음", "해당 없음", "n/a", "na"}


def essential_entries(payload: object) -> list[dict[str, Any]]:
    """API 응답에서 고시 항목 목록을 seq 순서로 반환한다. 형식이 다르면 빈 목록."""
    if not isinstance(payload, dict):
        return []
    meta = payload.get("meta")
    if isinstance(meta, dict) and meta.get("result") not in (None, "SUCCESS"):
        return []
    data = payload.get("data")
    entries = data.get("essentials") if isinstance(data, dict) else None
    if not isinstance(entries, list):
        return []
    valid = [
        entry
        for entry in entries
        if isinstance(entry, dict)
        and isinstance(entry.get("name"), str)
        and isinstance(entry.get("value"), str)
    ]
    return sorted(valid, key=lambda entry: entry.get("seq") if isinstance(entry.get("seq"), int) else 0)


def clean_material(raw: str) -> str | None:
    """판매자 원문을 화면용 한 줄로 정리한다. 실제 소재가 아니면 None."""
    text = html.unescape(raw)
    text = _BREAK.sub(" / ", text)
    text = _TAG.sub(" ", text)
    text = _TOLERANCE_NOTE.sub(" ", text)
    text = re.sub(r"\s*[\r\n]+\s*", " / ", text)
    text = re.sub(r"\s+", " ", text)
    text = re.sub(r"(?:\s*/\s*){2,}", " / ", text)
    text = text.strip(" /,")
    if not text or text.lower() in _EMPTY_VALUES or _PLACEHOLDER.match(text):
        return None
    if len(text) > MATERIAL_MAX_LENGTH:
        text = text[: MATERIAL_MAX_LENGTH - 1].rstrip(" /,") + "…"
    return text


def extract_material(payload: object) -> tuple[str | None, dict[str, Any] | None]:
    """고시 항목에서 소재를 찾는다.

    반환값은 (정리된 소재 또는 None, 근거 또는 None)이다. 소재 항목이 있으나 실제 값이 아니면
    근거에 ``usable: False``와 원문을 남겨, 원본 부재와 판매자 미기재를 구분할 수 있게 한다.
    """
    for entry in essential_entries(payload):
        name = " ".join(entry["name"].split())
        if not _MATERIAL_NAME.match(name):
            continue
        material = clean_material(entry["value"])
        return material, {
            "raw": entry["value"],
            "name": name,
            "source": ESSENTIAL_SOURCE,
            "usable": material is not None,
        }
    return None, None


def apply_essential_material(product: dict[str, Any], payload: object) -> dict[str, Any]:
    """고시 소재를 상품 JSON에 반영한다.

    고시 소재가 실제 값이면 우선 사용한다(공정위 고시 원문). 실제 값이 없으면 기존 구조화 소재를 유지하고,
    기존 값도 없을 때만 고시 근거(미기재 원문)를 남긴다.
    """
    material, evidence = extract_material(payload)
    evidence_map = product.get("attribute_evidence")
    if not isinstance(evidence_map, dict):
        evidence_map = {}
        product["attribute_evidence"] = evidence_map
    if material is not None:
        product["material"] = material
        evidence_map["material"] = evidence
    elif product.get("material") is None:
        product["material"] = None
        evidence_map["material"] = evidence
    return product
