import logging
import os
import json
from typing import Any, Optional

from app.services.size_spec_normalizer import (
    MUSINSA_CATEGORY_SPEC_FIELDS,
    get_musinsa_spec_fields,
    normalize_measurement_spec,
    MusinsaSizeSpecResolver,
)

logger = logging.getLogger(__name__)


class SizeProfileResolver:
    def __init__(self, data_path: Optional[str] = None):
        if data_path is None:
            base_dir = os.path.dirname(os.path.abspath(__file__))
            data_path = os.path.abspath(os.path.join(base_dir, "../../data/mock_size_data.json"))

        self.data_path = data_path
        self._load_mock_data()

    def _load_mock_data(self):
        """테스트용 Mock JSON 데이터를 메모리에 로드한다."""
        if os.path.exists(self.data_path):
            with open(self.data_path, "r", encoding="utf-8") as f:
                self.mock_db = json.load(f)
            logger.info("[SizeProfileResolver] Mock 데이터 로드 성공: %s", self.data_path)
        else:
            logger.warning("[SizeProfileResolver 경고] Mock 데이터 파일을 찾을 수 없습니다: %s", self.data_path)
            self.mock_db = {
                "users": [],
                "user_preferences": [],
                "orders_and_surveys": [],
                "other_users_fits": []
            }

    def resolve_and_calculate_size(
            self,
            user_id: str,
            subcategory: str,
            fit_type: str,
            candidate_products: list[dict[str, Any]],
            category_type: str
    ) -> dict[str, Any]:
        """
        [1. 추천 진입점]
        우선순위 체인을 타서 가장 신뢰도 높은 베이스라인(기준 치수)을 확보한 뒤,
        SizeCalculator를 통해 최적의 사이즈를 도출한다.
        """
        baseline_spec = self._get_baseline_spec(user_id, subcategory, fit_type, category_type=category_type)

        if not baseline_spec or baseline_spec["data"] is None:
            return {
                "source_type": "none",
                "baseline_spec": None,
                "recommendation": None
            }

        recommended_result = SizeCalculator.evaluate(
            candidate_products=candidate_products,
            baseline_spec=baseline_spec["data"],
            category_type=category_type
        )

        return {
            "source_type": baseline_spec["source"],
            "baseline_spec": baseline_spec["data"],
            "recommendation": recommended_result
        }

    def _get_baseline_spec(
            self,
            user_id: str,
            subcategory: str,
            fit_type: str,
            category_type: Optional[str] = None
    ) -> dict[str, Any]:
        """
        [2. 우선순위 체인 (Fallback Chain) 라우팅]
        1순위: 선호 실측 (learned_preference)
        2순위: 최근 구매 이력 실측 (recent_purchase)
        3순위 (신발): 사용자 등록 신발 사이즈 직접 반영 (body_profile)
        3순위 (의류): 키/몸무게 기반 비슷한 체형 타 사용자 인증 데이터 (similar_body_profile)
        """
        # 1순위: 선호 실측 탐색 (subcategory 매칭)
        for pref in self.mock_db.get("user_preferences", []):
            if str(pref.get("user_id")) == str(user_id) and pref.get("subcategory") == subcategory:
                return {
                    "source": "learned_preference",
                    "data": pref.get("preference_spec")
                }

        # 2순위: 최근 구매 이력 상품 실측 탐색
        for order in self.mock_db.get("orders_and_surveys", []):
            if str(order.get("user_id")) == str(user_id) and order.get("subcategory") == subcategory:
                return {
                    "source": "recent_purchase",
                    "data": order.get("measurement_spec")
                }

        target_user = next((u for u in self.mock_db.get("users", []) if str(u.get("user_id")) == str(user_id)), None)

        # 3순위 (신발 특화): 유저 등록 shoeSize를 발길이 기준 치수로 반영
        if category_type == "shoes" and target_user and target_user.get("shoe_size"):
            try:
                shoe_val = float(target_user["shoe_size"])
                if shoe_val < 50.0:
                    shoe_val *= 10.0
                return {
                    "source": "body_profile",
                    "data": {
                        "발길이": shoe_val,
                        "shoe_size": shoe_val,
                    }
                }
            except (ValueError, TypeError):
                pass

        # 3순위 (의류): 비슷한 체형 타 사용자의 '맞다' 인증 데이터 탐색
        if target_user:
            t_height = target_user.get("height", 175.0)
            t_weight = target_user.get("weight", 70.0)

            for other in self.mock_db.get("other_users_fits", []):
                if other.get("subcategory") == subcategory:
                    o_height = other.get("height", 175.0)
                    o_weight = other.get("weight", 70.0)

                    if abs(t_height - o_height) <= 3.0 and abs(t_weight - o_weight) <= 4.0:
                        return {
                            "source": "similar_body_profile",
                            "data": other.get("measurement_spec")
                        }

        return {
            "source": "none",
            "data": None
        }

    def update_fit_preferences(self, user_id: str, subcategory: str, fit_type: str, purchased_size_spec: dict):
        """
        [3. Closed-Loop 학습 루프]
        설문에서 "FIT(잘 맞아요)" 응답 시 누적 이동 평균으로 선호 실측 갱신.
        """
        prefs = self.mock_db.get("user_preferences", [])
        existing = next((p for p in prefs if p.get("user_id") == user_id and p.get("subcategory") == subcategory), None)

        if not existing:
            new_pref = {
                "preference_id": len(prefs) + 1,
                "user_id": user_id,
                "subcategory": subcategory,
                "fit_type": fit_type,
                "preference_spec": purchased_size_spec,
                "sample_count": 1
            }
            prefs.append(new_pref)
        else:
            n = existing.get("sample_count", 1)
            spec = existing.get("preference_spec", {})
            updated_spec = {}

            for key, val in purchased_size_spec.items():
                if key in spec and spec[key] is not None and val is not None:
                    updated_spec[key] = round((spec[key] * n + val) / (n + 1), 1)
                else:
                    updated_spec[key] = val if val is not None else spec.get(key)

            existing["preference_spec"] = updated_spec
            existing["sample_count"] = n + 1


class SizeCalculator:
    @staticmethod
    def evaluate(candidate_products: list[dict[str, Any]], baseline_spec: dict[str, float], category_type: str) -> Optional[dict[str, Any]]:
        """
        [4. 수학적 산출 엔진]
        상품 실측과 기준 치수를 비교하여 오차가 가장 적은 최적의 상품과 사이즈를 도출한다.
        """
        if not baseline_spec or not candidate_products:
            return None

        best_match = None
        min_penalty = float('inf')

        fields = get_musinsa_spec_fields(category_type)
        if not fields:
            return None

        normalized_baseline = normalize_measurement_spec(baseline_spec, category=category_type)

        for product in candidate_products:
            sizes = product.get("sizes")
            if not sizes:
                continue

            for size_option in sizes:
                penalty = 0.0
                valid_fields_count = 0

                raw_measurements = size_option.get("measurements", {})
                measurements = normalize_measurement_spec(raw_measurements, category=category_type)

                for field in fields:
                    meas_val = measurements.get(field)
                    base_val = normalized_baseline.get(field)

                    if (
                            meas_val is not None and isinstance(meas_val, (int, float)) and meas_val > 0 and
                            base_val is not None and isinstance(base_val, (int, float)) and base_val > 0
                    ):
                        penalty += abs(meas_val - base_val)
                        valid_fields_count += 1

                if valid_fields_count == 0:
                    # 신발 특화: 상세 실측표가 없고 표기 사이즈(260, 265 등)만 제공되는 상품의 라벨 직접 매칭
                    if category_type == "shoes":
                        user_shoe = (
                            baseline_spec.get("shoe_size")
                            or baseline_spec.get("발길이")
                            or normalized_baseline.get("발길이")
                        )
                        if user_shoe:
                            import re
                            m = re.search(r"\d+", str(size_option.get("size", "")))
                            if m:
                                try:
                                    opt_shoe = float(m.group())
                                    if opt_shoe < 50.0:
                                        opt_shoe *= 10.0
                                    diff = abs(opt_shoe - float(user_shoe))
                                    penalty = diff * 0.8
                                    valid_fields_count = 1
                                    fit_score = max(0.0, 1.0 - (diff / 33.3))
                                except (ValueError, TypeError):
                                    pass
                    if valid_fields_count == 0:
                        fit_score = 0.0
                elif penalty > 25.0:
                    fit_score = 0.0
                else:
                    fit_score = max(0.0, 1.0 - (penalty / 25.0))

                if penalty < min_penalty:
                    min_penalty = penalty
                    best_match = {
                        "product_id": product.get("product_id"),
                        "product_name": product.get("product_name"),
                        "size_label": size_option.get("size", "Unknown"),
                        "predicted_fit_score": round(fit_score, 2)
                    }

        return best_match