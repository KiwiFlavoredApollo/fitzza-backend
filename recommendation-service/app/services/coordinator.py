from typing import Any
from app.services.query_parser import QueryParser, SlotQuery
from app.services.search_engine import FashionSearchEngine
from app.services.harmonizer import OutfitHarmonizer
from app.services.response_generator import ResponseGenerator
from app.services.size_resolver import SizeProfileResolver

# 자연어 의도 분석 -> 단품/코디 분기 -> 조화도 검수 -> 응답 생성하는 전체 흐름 총괄

class FashionPipelineCoordinator:
    def __init__(self):
        print(">> 패션 추천 파이프라인 컴포넌트 초기화 중...")
        self.parser = QueryParser()
        self.engine = FashionSearchEngine()
        self.size_resolver = SizeProfileResolver()
        self.harmonizer = OutfitHarmonizer(self.engine)
        self.responder = ResponseGenerator()

        print(">> 전체 파이프라인 준비 완료")

    def _attach_size_info(
            self,
            items: list[dict[str, Any]],
            slot: SlotQuery,
            user_id: str,
    ) -> list[dict[str, Any]]:
        """
        검색된 상품 후보군에 대해 SizeProfileResolver를 통해 최적 사이즈를 산출하고 부착한다.        """
        evaluated_items = []
        for item in items:
            size_result = self.size_resolver.resolve_and_calculate_size(
                user_id=user_id,
                subcategory=slot.subcategory,
                fit_type=slot.fit_type,
                candidate_products=[item],
                category_type=slot.category,
            )

            item["size_recommendation"] = size_result.get("recommendation")
            item["fit_source_type"] = size_result.get("source_type")
            evaluated_items.append(item)

        return evaluated_items

    def _search_slot_items(
            self,
            slot: SlotQuery,
            season_preferences: list[str],
            top_k: int,
    ) -> list[dict[str, Any]]:
        """
        QueryParser가 추출한 모든 검색 조건과 물성/예산 파라미터를 SearchEngine에 전달한다.
        """
        return self.engine.search(
            clip_query_en=slot.clip_query_en,
            category=slot.category,
            subcategory=slot.subcategory,
            season_preferences=season_preferences,
            slot_budget=slot.slot_budget,
            max_price=slot.max_price,
            color=slot.color,
            fit_type=slot.fit_type,
            touch_type=slot.touch_type,
            stretch_type=slot.stretch_type,
            transparency_type=slot.transparency_type,
            thickness_type=slot.thickness_type,
            top_k=top_k,
        )

    def run(self, user_query: str, user_id: str = "user_001") -> dict[str, Any]:
        print(f"\n=== 파이프라인 실행 시작 ===")

        # [1단계] LLM 질의 파싱
        intent = self.parser.parse(user_query)
        print(f"1. 파서 결과: 의도={intent.search_type}, 총예산={intent.total_budget}, 슬롯 수={len(intent.slots)}개")

        print(f"\n🤖 LLM 파싱 분석 결과:")
        print(f"  - 검색 타입          : {intent.search_type}")
        print(f"  - 총 예산            : {intent.total_budget:,}원" if intent.total_budget else "  - 총 예산            : None")
        print(f"  - 계절 우선순위 배열 : {intent.season_preferences}")
        print(f"  - TPO 및 무드        : {intent.tpo_summary}")
        print(f"  - 추출된 슬롯 개수   : {len(intent.slots)}개")

        for idx, slot in enumerate(intent.slots, 1):
            print(f"    [{idx}] 카테고리: {slot.category} / 서브카테고리: {slot.subcategory or '(지정 없음)'}")
            print(f"        - 영문 검색어   : {slot.clip_query_en}")
            print(f"        - 슬롯 배정 예산: {slot.slot_budget:,}원" if slot.slot_budget else "        - 슬롯 배정 예산: None")
            print(f"        - 검색 최대 가격: {slot.max_price:,}원" if slot.max_price else "        - 검색 최대 가격: None")
            print(f"        - 지정 색상     : {slot.color}")
            print(f"        - 지정 핏       : {slot.fit_type}")
            print(
                f"        - 물성 조건     : 촉감={slot.touch_type}, "
                f"신축성={slot.stretch_type}, 비침={slot.transparency_type}, "
                f"두께={slot.thickness_type}"
            )
        print("-" * 60)

        # [2단계 - 단품 검색] 추출된 슬롯이 1개이거나 search_type이 single인 경우
        if not intent.slots and intent.search_type == "single":
            return {
                "type": "single",
                "query": user_query,
                "tpo": intent.tpo_summary,
                "intent": intent,
                "excluded_categories": [],
                "comment": "요청에서 검색할 상품을 찾지 못했습니다.",
                "results": [],
            }

        if intent.search_type == "single" or len(intent.slots) == 1:
            slot = intent.slots[0]
            items = self._search_slot_items(
                slot=slot,
                season_preferences=intent.season_preferences,
                top_k=9,
            )

            print(f"2. 검색 엔진 결과: 단품 후보 {len(items)}개 추출 완료")

            evaluated_items = self._attach_size_info(
                items=items,
                slot=slot,
                user_id=user_id,
            )

            comment = self.responder.generate_single_response(user_query, intent.tpo_summary, evaluated_items)
            return {
                "type": "single",
                "query": user_query,
                "tpo": intent.tpo_summary,
                "intent": intent,
                "excluded_categories": [] if evaluated_items else [slot.category],
                "comment": comment,
                "results": evaluated_items
            }

        # [2단계 - 코디 검색] 슬롯이 2개 이상 복합으로 들어온 경우
        slot_candidates: dict[str, list[dict[str, Any]]] = {}
        surviving_slots: list[SlotQuery] = []
        excluded_categories: list[str] = []

        for slot in intent.slots:
            items = self._search_slot_items(
                slot=slot,
                season_preferences=intent.season_preferences,
                top_k=5,
            )

            evaluated_slot_items = self._attach_size_info(
                items=items,
                slot=slot,
                user_id=user_id,
            )

            print(f"2. 검색 엔진 결과: 슬롯 '{slot.category}' -> {len(evaluated_slot_items)}개 후보 찾음")

            if evaluated_slot_items:
                slot_candidates[slot.category] = evaluated_slot_items
                surviving_slots.append(slot)
            else:
                excluded_categories.append(slot.category)
                print(f"[INFO] 슬롯 '{slot.category}'의 검색 결과가 0개이므로 코디 조합에서 제외하고 남은 슬롯으로 진행합니다.")

        # 모든 카테고리에서 검색 결과가 0개인 경우
        if not slot_candidates:
            return {
                "type": "coordination",
                "query": user_query,
                "tpo": intent.tpo_summary,
                "intent": intent,
                "excluded_categories": excluded_categories,
                "comment": "요청하신 조건에 맞는 상품을 찾지 못했습니다. 검색 조건을 조금 완화해 다시 시도해주세요.",
                "outfits": []
            }

        # 빈 슬롯들을 제외하고 살아남은 슬롯이 딱 1개뿐인 경우 -> 단품 추천 흐름으로 유연하게 전환
        if len(slot_candidates) == 1:
            only_category = next(iter(slot_candidates))
            only_items = slot_candidates[only_category]
            print(f"[INFO] 빈 슬롯 제외 후 '{only_category}' 1개 슬롯만 남아 단일 슬롯 추천으로 전환합니다.")

            comment = self.responder.generate_single_response(user_query, intent.tpo_summary, only_items)
            return {
                "type": "single",
                "query": user_query,
                "tpo": intent.tpo_summary,
                "intent": intent,
                "excluded_categories": excluded_categories,
                "comment": comment,
                "results": only_items
            }

        # 일부 슬롯이 제외된 경우, 살아남은 슬롯들의 slot_budget 합계를 반영해 유효 예산 산출
        effective_budget = intent.total_budget
        if intent.total_budget is not None and excluded_categories:
            surviving_budget_sum = sum(
                s.slot_budget for s in surviving_slots if s.slot_budget is not None
            )
            if surviving_budget_sum > 0:
                effective_budget = min(intent.total_budget, surviving_budget_sum)
                print(
                    f"[INFO] 제외된 슬롯({excluded_categories})을 반영하여 "
                    f"기준 총예산을 {intent.total_budget:,}원 -> {effective_budget:,}원으로 조정합니다."
                )

        # [3단계] 조화도 채점 및 Vision Re-ranking
        print(f"5. 조화도 검수기(Harmonizer) 실행 중... (내부 점수 및 예산 감점 정렬 -> Vision LLM 심사)")
        best_outfits = self.harmonizer.select_best_outfits(
            slot_candidates=slot_candidates,
            tpo_context=intent.tpo_summary,
            top_k=2,
            total_budget=effective_budget,
        )
        print(f"6. 최종 추천 코디 세트 확정: {len(best_outfits)}개")

        # [4단계] 자연어 응답 생성
        comment = self.responder.generate_coordination_response(user_query, intent.tpo_summary, best_outfits)
        return {
            "type": "coordination",
            "query": user_query,
            "tpo": intent.tpo_summary,
            "intent": intent,
            "excluded_categories": excluded_categories,
            "comment": comment,
            "outfits": best_outfits
        }