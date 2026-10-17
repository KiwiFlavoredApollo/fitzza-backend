import os
from typing import Any, Optional

import torch
from dotenv import load_dotenv
from transformers import CLIPModel, CLIPProcessor
from qdrant_client import QdrantClient
from qdrant_client.http import models


load_dotenv()


class FashionSearchEngine:
    # 초기 실험용 가중치. 검색 평가 결과에 따라 조정할 것.
    VECTOR_WEIGHT = 0.7
    ATTRIBUTE_WEIGHT = 0.3

    # 기존 기준 유지. 실제 검색 결과로 적절성을 검증할 것.
    MIN_SIMILARITY = 0.2

    def __init__(
            self,
            qdrant_url: Optional[str] = None,
            collection_name: Optional[str] = None,
            model_name: str = "patrickjohncyh/fashion-clip",
    ):
        self.qdrant_url = (
                qdrant_url
                or os.getenv("QDRANT_URL", "http://localhost:6333")
        )
        self.collection_name = (
                collection_name
                or os.getenv("QDRANT_COLLECTION_NAME", "musinsa_products")
        )

        self.client = QdrantClient(url=self.qdrant_url)

        self.device = "cuda" if torch.cuda.is_available() else "cpu"

        self.model = CLIPModel.from_pretrained(model_name).to(self.device)
        self.processor = CLIPProcessor.from_pretrained(model_name)
        self.model.eval()

    def _normalize(self, tensor: torch.Tensor) -> torch.Tensor:
        """임베딩 벡터 L2 정규화."""
        return tensor / tensor.norm(
            p=2, dim=-1, keepdim=True
        ).clamp_min(1e-12)

    def encode_text(self, clip_query_en: str) -> list[float]:
        """검색 텍스트를 Fashion-CLIP 벡터로 변환."""
        inputs = self.processor(
            text=[clip_query_en],
            return_tensors="pt",
            padding=True,
        ).to(self.device)

        with torch.no_grad():
            feats = self.model.get_text_features(**inputs)

            if (
                    hasattr(feats, "pooler_output")
                    and feats.pooler_output is not None
            ):
                feats = feats.pooler_output
            elif hasattr(feats, "last_hidden_state"):
                feats = feats.last_hidden_state[:, 0, :]

            feats = self._normalize(feats)

        return feats.cpu().numpy()[0].tolist()

    @staticmethod
    def _as_list(value: Any) -> list[str]:
        """payload의 단일 값 또는 배열을 문자열 배열로 변환."""
        if value is None:
            return []

        if isinstance(value, list):
            return [
                str(item).strip()
                for item in value
                if item is not None and str(item).strip()
            ]

        if isinstance(value, str) and value.strip():
            return [value.strip()]

        return []

    @staticmethod
    def _first_feature_value(
            features: dict[str, Any],
            key: str,
    ) -> Optional[str]:
        """features에서 첫 번째 유효한 특성값을 가져온다."""
        values = FashionSearchEngine._as_list(features.get(key))
        return values[0] if values else None

    def _calculate_attribute_score(
            self,
            payload: dict[str, Any],
            season_preferences: list[str] | None,
            fit_type: str | None,
            touch_type: str | None,
            stretch_type: str | None,
            transparency_type: str | None,
            thickness_type: str | None,
    ) -> float:
        """
        사용자가 요청한 상품 특성의 일치 비율을 계산한다.
        특성 데이터가 없으면 해당 특성은 불일치로 계산한다.
        계절은 season_candidates만 사용한다.
        """
        features = payload.get("features") or {}

        if not isinstance(features, dict):
            features = {}

        total = 0
        matched = 0

        feature_preferences = {
            "핏": fit_type,
            "촉감": touch_type,
            "신축성": stretch_type,
            "비침": transparency_type,
            "두께": thickness_type,
        }

        for feature_key, requested_value in feature_preferences.items():
            if requested_value is None:
                continue

            total += 1
            product_value = self._first_feature_value(
                features, feature_key
            )

            if product_value == requested_value:
                matched += 1

        if season_preferences:
            total += 1

            product_seasons = set(
                self._as_list(payload.get("season_candidates"))
            )

            if product_seasons.intersection(season_preferences):
                matched += 1

        # 특성 조건이 없으면 벡터 유사도만 사용한다.
        if total == 0:
            return 0.0

        return matched / total

    def search(
            self,
            clip_query_en: str,
            category: str | None = None,
            subcategory: str | None = None,
            season_preferences: list[str] | None = None,
            slot_budget: int | None = None,
            max_price: int | None = None,
            color: str | None = None,
            fit_type: str | None = None,
            touch_type: str | None = None,
            stretch_type: str | None = None,
            transparency_type: str | None = None,
            thickness_type: str | None = None,
            top_k: int = 3,
    ) -> list[dict[str, Any]]:
        """Qdrant 검색 후 필수 조건 검사 및 특성 기반 재정렬."""
        if not clip_query_en or not clip_query_en.strip():
            raise ValueError("clip_query_en은 비어 있을 수 없습니다.")

        if top_k < 1:
            raise ValueError("top_k는 1 이상이어야 합니다.")

        if max_price is not None and max_price <= 0:
            raise ValueError("max_price는 0보다 커야 합니다.")

        if slot_budget is not None and slot_budget <= 0:
            raise ValueError("slot_budget은 0보다 커야 합니다.")

        query_vector = self.encode_text(clip_query_en)

        category_map = {
            "top": "상의",
            "outer": "아우터",
            "bottom": "하의",
            "shoes": "신발",
            "hat": "모자",
        }
        db_category = (
            category_map.get(category, category)
            if category
            else None
        )

        # 1. 반드시 지켜야 하는 검색 조건 (Hard Filtering)
        must_conditions = []

        if db_category is not None:
            must_conditions.append(
                models.FieldCondition(
                    key="category",
                    match=models.MatchValue(value=db_category),
                )
            )

        if subcategory:
            must_conditions.append(
                models.FieldCondition(
                    key="subcategory",
                    match=models.MatchValue(value=subcategory),
                )
            )

        # color는 표준화된 값이어야 한다. 예: black, gray
        if color:
            must_conditions.append(
                models.FieldCondition(
                    key="color_normalized",
                    match=models.MatchValue(value=color),
                )
            )

        # max_price는 검색에서 허용할 최대 가격이다.
        if max_price is not None:
            must_conditions.append(
                models.FieldCondition(
                    key="price",
                    range=models.Range(lte=max_price),
                )
            )

        query_filter = models.Filter(
            must=must_conditions or None,
        )

        # 2. 후보 조회: 우선 top_k * 5개 유지
        response = self.client.query_points(
            collection_name=self.collection_name,
            query=query_vector,
            query_filter=query_filter,
            limit=top_k * 5,
            with_payload=True,
            with_vectors=True,
        )

        results: list[dict[str, Any]] = []

        for hit in response.points:
            vector_score = float(hit.score)

            if vector_score < self.MIN_SIMILARITY:
                continue

            payload = hit.payload or {}

            product_colors = self._as_list(
                payload.get("color_normalized")
            )

            raw_color = payload.get("color_raw")
            product_subcategory = payload.get("subcategory")

            # 방어적 검사 1: 지정 색상과 다르면 제외
            if color and color not in product_colors:
                continue

            # 방어적 검사 2: 서브카테고리가 명시되었는데 페이로드와 다르면 제외
            if subcategory and product_subcategory != subcategory:
                continue

            # 3. 상품 특성 일치도
            attribute_score = self._calculate_attribute_score(
                payload=payload,
                season_preferences=season_preferences,
                fit_type=fit_type,
                touch_type=touch_type,
                stretch_type=stretch_type,
                transparency_type=transparency_type,
                thickness_type=thickness_type,
            )

            # 4. 벡터 유사도와 상품 특성 점수를 결합
            if any([
                season_preferences,
                fit_type,
                touch_type,
                stretch_type,
                transparency_type,
                thickness_type,
            ]):
                composite_score = (
                        self.VECTOR_WEIGHT * vector_score
                        + self.ATTRIBUTE_WEIGHT * attribute_score
                )
            else:
                composite_score = vector_score

            # 5. slot_budget 초과 상품은 점수 감점
            actual_price = payload.get("price")

            if (
                    slot_budget is not None
                    and actual_price is not None
                    and slot_budget > 0
                    and actual_price > slot_budget
            ):
                over_ratio = (
                                     actual_price - slot_budget
                             ) / slot_budget

                composite_score -= over_ratio * 0.1

            results.append(
                {
                    "final_score": composite_score,
                    "composite_score": composite_score,
                    "similarity_score": round(vector_score, 4),
                    "attribute_score": round(attribute_score, 4),
                    "product_id": payload.get("product_id"),
                    "product_name": payload.get("product_name"),
                    "brand_name": payload.get("brand_name"),
                    "category": payload.get("category"),
                    "subcategory": product_subcategory,
                    "price": actual_price,
                    "color_raw": raw_color,
                    "color_normalized": product_colors,
                    "season_candidates": payload.get(
                        "season_candidates"
                    ) or [],
                    "features": payload.get("features") or {},
                    "sizes": payload.get("sizes") or [],
                    "image_url": payload.get("image_url"),
                    "product_url": payload.get("product_url"),
                    "vector": hit.vector,
                }
            )

        results.sort(
            key=lambda item: item["composite_score"],
            reverse=True,
        )

        return results[:top_k]