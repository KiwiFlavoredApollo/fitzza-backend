import os
import time
from dotenv import load_dotenv
from app.services.coordinator import FashionPipelineCoordinator

load_dotenv()


def main():
    print("=" * 80)
    print("✨ Fitzza AI 패션 추천 파이프라인 대화형 테스트 시작 (성능 측정 포함)")
    print("=" * 80)

    # 1. 환경변수 검증
    if not os.getenv("OPENAI_API_KEY"):
        print("🚨 [에러] OPENAI_API_KEY가 설정되지 않았습니다. .env 파일을 확인해주세요.")
        return

    # 2. 코디네이터 초기화
    try:
        coordinator = FashionPipelineCoordinator()
    except Exception as e:
        print(f"🚨 [에러] 코디네이터 초기화 실패: {e}")
        import traceback
        traceback.print_exc()
        return

    print("\n💡 안내: 추천받고 싶은 패션 스타일을 자유롭게 입력해 주세요.")
    print("   (종료하려면 'exit' 또는 'quit'을 입력하세요)\n")

    # 3. 터미널 실시간 사용자 입력 루프
    idx = 1
    while True:
        try:
            query = input(f"[{idx}] 사용자 입력 질의 ➔ ").strip()

            if not query:
                print("⚠️ 빈 질의입니다. 다시 입력해주세요.\n")
                continue

            if query.lower() in ["exit", "quit"]:
                print("\n👋 테스트 프로그램을 종료합니다.")
                break

            print("\n" + "#" * 80)
            print(f"📌 [테스트 케이스 #{idx}] 사용자 입력 질의: \"{query}\"")
            print("#" * 80)

            # 전체 파이프라인 타이머 시작
            pipeline_start_time = time.perf_counter()

            # 파이프라인 구동 (Coordinator의 run 호출 - user_test_001 유저 기준)
            result = coordinator.run(user_query=query, user_id="user_test_001")

            # 전체 파이프라인 타이머 종료
            pipeline_end_time = time.perf_counter()
            total_pipeline_duration = pipeline_end_time - pipeline_start_time

            # 각 컴포넌트별 측정된 지연 시간 안전하게 가져오기 (없으면 기본값 0.0)
            parser_latency = getattr(coordinator.parser, "last_latency", 0.0)
            harmonizer_latency = getattr(coordinator.harmonizer, "last_latency", 0.0)
            responder_latency = getattr(coordinator.responder, "last_latency", 0.0)

            print("\n" + "=" * 25 + " ⏱️ 성능 및 응답 시간 측정 리포트 " + "=" * 25)
            print(f"  - [QueryParser] 응답 시간              : {parser_latency:.2f}초")
            print(f"  - [OutfitHarmonizer] 응답 시간       : {harmonizer_latency:.2f}초")
            print(f"  - [ResponseGenerator] 응답 시간      : {responder_latency:.2f}초")
            print("-" * 65)
            print(f"  - 🚀 전체 파이프라인 총 응답 시간        : {total_pipeline_duration:.2f}초")
            print("=" * 73)

            intent = result.get("intent")

            # 파싱된 슬롯 정보 출력
            if intent and hasattr(intent, "slots"):
                print("\n🔍 [파서 추출 슬롯 상세]")
                for s_idx, slot in enumerate(intent.slots, 1):
                    print(
                        f"  - 슬롯 [{s_idx}] 대분류: {slot.category} | "
                        f"서브카테고리: {slot.subcategory or '(지정 없음)'} | "
                        f"검색어: {slot.clip_query_en}"
                    )

            print("\n" + "=" * 30 + " [파이프라인 실행 결과 요약] " + "=" * 30)
            print(f"- 추천 타입 (Type)       : {result.get('type')}")
            print(f"- TPO 상황 요약 (TPO)    : {result.get('tpo')}")
            print(f"- 제외된 카테고리        : {result.get('excluded_categories', [])}")

            print("\n💬 [AI 응답 코멘트 (Response Generator)]")
            print("-" * 60)
            print(result.get("comment", "(응답 없음)"))
            print("-" * 60)

            # [단품 검색 결과 출력]
            if result.get("type") == "single":
                results = result.get("results", [])
                print(f"\n📦 단품 검색 결과 상위 {len(results)}개:")
                for r_idx, item in enumerate(results, 1):
                    subcat_display = f"[{item.get('subcategory')}]" if item.get('subcategory') else ""
                    size_rec = item.get("size_recommendation")
                    size_str = f"추천 사이즈: {size_rec.get('size_label')} (적합도: {size_rec.get('predicted_fit_score')}점)" if size_rec else "사이즈 정보 없음"
                    source_type = item.get("fit_source_type", "none")

                    print(
                        f"  {r_idx}. [{item.get('brand_name')}] "
                        f"{item.get('product_name')} {subcat_display} - "
                        f"{item.get('price', 0):,}원"
                    )
                    print(
                        f"     정규화 색상: {item.get('color_normalized')} | "
                        f"벡터 유사도: {item.get('similarity_score')}"
                    )
                    print(f"     📏 [사이즈 추천] {size_str} [소스: {source_type}]")
                    # [추가] 이미지 URL 및 상품 상세 링크 출력
                    print(f"     🖼️ 이미지 URL : {item.get('image_url')}")
                    print(f"     🔗 상품 링크  : {item.get('product_url')}")

            # [코디 세트 추천 결과 출력]
            elif result.get("type") == "coordination":
                outfits = result.get("outfits", [])
                print(f"\n✨ 최종 확정된 코디 세트 ({len(outfits)}개):")
                for o_idx, outfit in enumerate(outfits, 1):
                    print(
                        f"\n  [코디 세트 {o_idx}] "
                        f"(총가격: {outfit.get('total_price', 0):,}원 | "
                        f"최종 정렬 점수: {outfit.get('final_score')}점)"
                    )
                    print(
                        f"    - Vision 조화도 점수: {outfit.get('harmony_score')}점 / "
                        f"예산 초과 감점: -{outfit.get('budget_penalty')}점"
                    )
                    print(f"    - 에디터 심사평: {outfit.get('verdict')}")
                    print("    - 구성 상품 세부 내역:")
                    for item in outfit.get("items", []):
                        size_rec = item.get("size_recommendation")
                        size_str = f"추천 사이즈: {size_rec.get('size_label')} (적합도: {size_rec.get('predicted_fit_score')}점)" if size_rec else "사이즈 정보 없음"
                        print(
                            f"      * [{item.get('category')} / 서브: {item.get('subcategory')}] "
                            f"{item.get('brand_name')} {item.get('product_name')} "
                            f"({item.get('price', 0):,}원) | {size_str}"
                        )
                        # [추가] 코디 구성 상품별 이미지 URL 및 상품 상세 링크 출력
                        print(f"        🖼️ 이미지 URL : {item.get('image_url')}")
                        print(f"        🔗 상품 링크  : {item.get('product_url')}")

            print("\n" + "=" * 80 + "\n")
            idx += 1

        except KeyboardInterrupt:
            print("\n\n👋 사용자에 의해 강제 종료되었습니다.")
            break
        except Exception as error:
            print(f"❌ [테스트 실패] 질의 처리 중 오류 발생: {type(error).__name__}: {error}\n")
            import traceback
            traceback.print_exc()
            print("\n" + "=" * 80 + "\n")


if __name__ == "__main__":
    main()