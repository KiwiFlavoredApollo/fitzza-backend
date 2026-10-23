from __future__ import annotations

import asyncio
import logging
from typing import Any, Optional

from qdrant_client import QdrantClient
from qdrant_client.http import models

from app.core.config import get_settings
from app.schemas.recommendation import PersonalizedProductItem
from app.services.personalized_vector_engine import PersonalizedVectorEngine
from app.services.user_history_service import (
    UserActionHistory,
    UserHistoryCollector,
)

logger = logging.getLogger(__name__)


class PersonalizedFeedService:
    """
    사용자 행동 이력 기반 취향 벡터를 생성하고,
    Qdrant에서 5대 카테고리(상의, 하의, 아우터, 신발, 모자)별로 상위 후보군을 검색한 뒤,
    구매 상품 제외 및 라운드 로빈 교차 인터리빙을 거쳐 최종 추천 피드 아이템을 조립하는 서비스.
    """

    CATEGORIES = ["상의", "하의", "아우터", "신발", "모자"]
    ITEMS_PER_CATEGORY = 16  # 5대 카테고리 * 16개 = 총 80개
    TOTAL_ITEMS = 80

    def __init__(
        self,
        qdrant_client: Optional[QdrantClient] = None,
        collection_name: Optional[str] = None,
        vector_engine: Optional[PersonalizedVectorEngine] = None,
        history_collector: Optional[UserHistoryCollector] = None,
        qdrant_url: Optional[str] = None,
    ) -> None:
        settings = get_settings()
        self.collection_name = (
            collection_name or settings.qdrant_collection_name or "musinsa_products"
        )

        if qdrant_client is not None:
            self.client = qdrant_client
        else:
            url = qdrant_url or settings.qdrant_url or "http://localhost:6333"
            self.client = QdrantClient(url=url)

        self.vector_engine = vector_engine or PersonalizedVectorEngine(
            qdrant_client=self.client,
            collection_name=self.collection_name,
        )
        self.history_collector = history_collector or UserHistoryCollector()

    def search_category_candidates(
        self,
        taste_vector: list[float],
        category: str,
        limit: int = 16,
        exclude_pids: Optional[list[int]] = None,
        wishlist_set: Optional[set[int]] = None,
    ) -> list[PersonalizedProductItem]:
        """
        단일 카테고리에 대해 취향 벡터 유사도 상위 상품을 검색한다.
        - 최근 구매 상품(exclude_pids)은 must_not 필터로 Qdrant 레벨에서 원천 제외.
        - 동일 상품(다양한 색상 포인트) 중복 방지를 위해 fetch_limit을 넉넉히 설정 후 중복 제거.
        - wishlist_set을 참조하여 wished 플래그를 세팅.
        """
        wished_ids = wishlist_set or set()
        clean_exclude_ids = (
            [int(pid) for pid in exclude_pids if pid is not None and int(pid) > 0]
            if exclude_pids
            else []
        )

        must_conditions: list[models.FieldCondition] = [
            models.FieldCondition(
                key="category",
                match=models.MatchValue(value=category),
            )
        ]

        must_not_conditions: list[models.FieldCondition] = []
        if clean_exclude_ids:
            must_not_conditions.append(
                models.FieldCondition(
                    key="product_id",
                    match=models.MatchAny(any=clean_exclude_ids),
                )
            )

        query_filter = models.Filter(
            must=must_conditions,
            must_not=must_not_conditions if must_not_conditions else None,
        )

        # 상품당 여러 색상 포인트가 있을 수 있으므로 여유있게 조회
        fetch_limit = max(limit * 3, 30)

        try:
            response = self.client.query_points(
                collection_name=self.collection_name,
                query=taste_vector,
                query_filter=query_filter,
                limit=fetch_limit,
                with_payload=True,
                with_vectors=False,
            )
        except Exception as e:
            logger.error(
                "Qdrant 카테고리(%s) 검색 실패: %s", category, e, exc_info=True
            )
            return []

        items: list[PersonalizedProductItem] = []
        seen_pids: set[int] = set()

        for point in response.points:
            payload = point.payload or {}
            raw_pid = payload.get("product_id")
            if raw_pid is None:
                continue

            try:
                pid = int(raw_pid)
            except (ValueError, TypeError):
                continue

            if pid in seen_pids:
                continue
            seen_pids.add(pid)

            item = PersonalizedProductItem(
                productId=pid,
                productName=str(payload.get("product_name") or ""),
                brandName=payload.get("brand_name"),
                price=int(payload.get("price") or 0),
                thumbnailUrl=payload.get("image_url"),
                status="ON_SALE",
                category=payload.get("category"),
                wished=(pid in wished_ids),
            )
            items.append(item)
            if len(items) >= limit:
                break

        return items

    @classmethod
    def interleave_categories(
        cls,
        category_buckets: dict[str, list[PersonalizedProductItem]],
        order: Optional[list[str]] = None,
        limit_per_category: int = 16,
    ) -> list[PersonalizedProductItem]:
        """
        카테고리별 상품 목록을 라운드 로빈(Round-Robin) 방식으로 교차 결합한다.
        결과 순서: [상의0, 하의0, 아우터0, 신발0, 모자0, 상의1, 하의1, ...]
        특정 카테고리가 조기 소진되면 남은 카테고리들로 순회를 계속한다.
        """
        target_order = order or cls.CATEGORIES
        interleaved: list[PersonalizedProductItem] = []

        for round_idx in range(limit_per_category):
            for cat in target_order:
                items = category_buckets.get(cat, [])
                if round_idx < len(items):
                    interleaved.append(items[round_idx])

        return interleaved

    async def generate_personalized_feed_items(
        self,
        user_id: Optional[int],
    ) -> tuple[list[PersonalizedProductItem], UserActionHistory]:
        """
        사용자 ID를 받아 전체 개인화 피드 아이템(최대 80개)을 조립하여 반환한다.
        1. 비동기 행동 이력 수집 (게스트는 빈 이력)
        2. 취향 벡터 계산 (Cold-Start 포함)
        3. 5대 카테고리 병렬 비동기 Qdrant 검색
        4. 인터리빙 결합
        """
        # 1. 사용자 이력 수집
        if user_id is not None and user_id > 0:
            history = await self.history_collector.collect_user_history_async(user_id)
        else:
            history = UserActionHistory(user_id=0)

        # 2. 취향 벡터 계산
        taste_vector = self.vector_engine.compute_user_taste_vector(history)

        # 3. 제외할 상품 ID (최근 구매 상품) 및 찜 집합
        exclude_pids = list(set(history.purchased_product_ids))
        wishlist_set = set(history.wishlist_product_ids)

        # 4. 5대 카테고리 비동기 동시 병렬 검색 (asyncio.gather)
        tasks = [
            asyncio.to_thread(
                self.search_category_candidates,
                taste_vector=taste_vector,
                category=cat,
                limit=self.ITEMS_PER_CATEGORY,
                exclude_pids=exclude_pids,
                wishlist_set=wishlist_set,
            )
            for cat in self.CATEGORIES
        ]
        results = await asyncio.gather(*tasks, return_exceptions=True)

        category_buckets: dict[str, list[PersonalizedProductItem]] = {}
        for cat, res in zip(self.CATEGORIES, results):
            if isinstance(res, list):
                category_buckets[cat] = res
            else:
                logger.error("카테고리 %s 비동기 검색 실패: %s", cat, res)
                category_buckets[cat] = []

        # 5. 인터리빙 결합
        interleaved_feed = self.interleave_categories(
            category_buckets=category_buckets,
            order=self.CATEGORIES,
            limit_per_category=self.ITEMS_PER_CATEGORY,
        )

        return interleaved_feed, history
