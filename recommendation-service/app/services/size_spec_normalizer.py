import logging
from typing import Any, Optional

logger = logging.getLogger(__name__)

# ==============================================================================
# [무신사 표준 실측 규격 정의 (FITZZA-53)]
# 상의/아우터, 하의, 신발, 모자 카테고리별 정확한 실측 항목
# ==============================================================================
MUSINSA_CATEGORY_SPEC_FIELDS: dict[str, list[str]] = {
    "top": ["총장", "어깨너비", "가슴단면", "소매길이", "밑단단면", "소매부리단면", "암홀"],
    "outer": ["총장", "어깨너비", "가슴단면", "소매길이", "밑단단면", "소매부리단면", "암홀"],
    "bottom": ["총장", "허리단면", "엉덩이단면", "허벅지단면", "밑위", "밑단단면"],
    "shoes": ["발길이", "발볼", "발목높이", "굽높이"],
    "hat": ["머리둘레", "챙길이", "깊이"],
}


def get_musinsa_spec_fields(category: str) -> list[str]:
    """카테고리별 무신사 표준 실측 스펙 필드 목록을 반환한다."""
    cat = category.strip().lower() if category else "top"
    return list(MUSINSA_CATEGORY_SPEC_FIELDS.get(cat, []))


def parse_numeric_measurement(raw_val: Any, is_shoes: bool = False) -> Optional[float]:
    """
    실측 수치를 float로 변환하고, 신발의 경우 cm 입력 시 mm로 자동 변환한다.
    - 일반 의류(상의/하의/모자): cm 단위 기준
    - 신발(shoes): mm 단위 기준 (20~35 사이 수치가 들어오면 cm로 판단하여 * 10)
    """
    if raw_val is None:
        return None

    try:
        if isinstance(raw_val, str):
            cleaned = raw_val.strip().lower().replace("cm", "").replace("mm", "").strip()
            val = float(cleaned)
        else:
            val = float(raw_val)
    except (ValueError, TypeError):
        return None

    if val <= 0:
        return None

    # 신발의 경우 20~35 범위(예: 26, 26.5)는 cm 표기이므로 mm(260, 265)로 자동 환산
    if is_shoes and 20.0 <= val <= 35.0:
        val *= 10.0

    return round(val, 1)


def normalize_measurement_spec(
    raw_spec: Optional[dict[str, Any]],
    category: Optional[str] = None
) -> dict[str, float]:
    """
    무신사 크롤링 데이터 기준 실측 스펙 정규화 함수:
    - 지정된 카테고리의 무신사 표준 필드만 추출
    - 수치 파싱 및 신발(cm/mm) 단위 보정
    """
    if not raw_spec or not isinstance(raw_spec, dict):
        return {}

    cat = category.strip().lower() if category else None
    target_fields = MUSINSA_CATEGORY_SPEC_FIELDS.get(cat) if cat else None
    is_shoes = (cat == "shoes")

    normalized: dict[str, float] = {}

    for field, raw_val in raw_spec.items():
        if not field:
            continue
        clean_field = str(field).strip()

        # 카테고리가 지정된 경우 해당 카테고리의 무신사 표준 필드만 허용
        if target_fields is not None and clean_field not in target_fields:
            continue

        parsed_val = parse_numeric_measurement(raw_val, is_shoes=is_shoes)
        if parsed_val is not None:
            normalized[clean_field] = parsed_val

    return normalized


class MusinsaSizeSpecResolver:
    """무신사 표준 실측 스펙 조회 및 검증 서비스"""

    @classmethod
    def get_supported_categories(cls) -> list[str]:
        """지원하는 카테고리 목록을 반환한다."""
        return list(MUSINSA_CATEGORY_SPEC_FIELDS.keys())

    @classmethod
    def get_spec_fields(cls, category: str) -> list[str]:
        """카테고리에 해당하는 무신사 표준 실측 항목을 반환한다."""
        return get_musinsa_spec_fields(category)

    @classmethod
    def normalize(cls, raw_spec: dict[str, Any], category: Optional[str] = None) -> dict[str, float]:
        """실측 스펙 데이터를 무신사 표준 규격으로 정규화한다."""
        return normalize_measurement_spec(raw_spec, category=category)
