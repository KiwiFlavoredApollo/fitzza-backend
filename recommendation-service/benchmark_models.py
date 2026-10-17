"""Fitzza AI: QueryParser / ResponseGenerator 개별 모델 벤치마크

프로젝트 루트에서 실행:
    python benchmark_models.py

기본 3회 반복. 반복 횟수 변경:
    $env:BENCHMARK_RUNS=5   # Windows PowerShell
    python benchmark_models.py

기존 파이프라인/서비스 코드는 수정하지 않습니다.
"""
from __future__ import annotations

import csv
import json
import os
import statistics
import time
from datetime import datetime
from pathlib import Path
from typing import Any

from dotenv import load_dotenv

from app.services.query_parser import QueryParser
from app.services.response_generator import ResponseGenerator

load_dotenv()

RUNS = max(1, int(os.getenv("BENCHMARK_RUNS", "3")))
OUTPUT_DIR = Path("benchmark_results")

PARSER_MODELS = ["gpt-6-sol", "gpt-6.1-sol"]
GENERATOR_MODELS = ["gpt-6.1-sol", "gpt-6-luna"]

# QueryParser 비교: 정답은 결과 검토를 위한 기준이며 자동으로 결과를 보정하지 않습니다.
PARSER_CASES: list[dict[str, Any]] = [
    {
        "id": "single_black_hoodie",
        "query": "검은색 오버사이즈 후드티 추천해줘",
        "expected": {
            "search_type": "single",
            "total_budget": None,
            "slot_categories": ["top"],
            "slot_subcategories": ["후드 티셔츠"],
            "colors": ["black"],
            "fit_types": ["오버 사이즈"],
        },
    },
    {
        "id": "coordination_knit_denim_budget",
        "query": "15만원 이하로 가을에 입기 좋은 니트랑 데님 팬츠 코디 추천해줘",
        "expected": {
            "search_type": "coordination",
            "total_budget": 150000,
            "slot_categories": ["top", "bottom"],
            "slot_subcategories": ["니트/스웨터", "데님 팬츠"],
            "season_preferences": ["가을"],
        },
    },
    {
        "id": "coordination_guest_blazer_slacks",
        "query": "하객룩으로 입을 깔끔한 블레이저랑 슬랙스 세트 찾아줘",
        "expected": {
            "search_type": "coordination",
            "total_budget": None,
            "slot_categories": ["outer", "bottom"],
            "slot_subcategories": ["슈트/블레이저 재킷", "슈트 팬츠/슬랙스"],
        },
    },
    {
        "id": "single_white_oversized_shirt_budget",
        "query": "10만 원 이하의 흰색 오버핏 셔츠 추천해줘",
        "expected": {
            "search_type": "single",
            "total_budget": 100000,
            "slot_categories": ["top"],
            "slot_subcategories": ["셔츠/블라우스"],
            "colors": ["white"],
            "fit_types": ["오버 사이즈"],
        },
    },
    {
        "id": "single_summer_item_in_winter",
        "query": "12월에 입을 반팔 티셔츠 추천해줘",
        "expected": {
            "search_type": "single",
            "total_budget": None,
            "slot_categories": ["top"],
            "slot_subcategories": ["반소매 티셔츠"],
            "season_preferences": ["여름"],
        },
    },
    {
        "id": "coordination_date_look_budget",
        "query": "가을 데이트룩 코디 추천해줘. 20만원 이하로",
        "expected": {
            "search_type": "coordination",
            "total_budget": 200000,
            "slot_categories": ["top", "bottom", "shoes"],
            "season_preferences": ["가을"],
        },
    },
    {
        "id": "coordination_blazer_slacks_budget",
        "query": "블레이저와 슬랙스만으로 코디해줘. 총 15만원 이하로",
        "expected": {
            "search_type": "coordination",
            "total_budget": 150000,
            "slot_categories": ["outer", "bottom"],
            "slot_subcategories": ["슈트/블레이저 재킷", "슈트 팬츠/슬랙스"],
        },
    },
]

# ResponseGenerator 비교: 두 모델에 정확히 같은 고정 입력을 전달합니다.
GENERATOR_CASES: list[dict[str, Any]] = [
    {
        "id": "single_products_found",
        "method": "single",
        "query": "검은색 오버사이즈 후드티 추천해줘",
        "tpo": "검은색 오버사이즈 후드티 추천",
        "items": [
            {"brand_name": "브랜드A", "product_name": "오버핏 헤비 후드", "subcategory": "후드 티셔츠", "price": 59000},
            {"brand_name": "브랜드B", "product_name": "베이직 블랙 후디", "subcategory": "후드 티셔츠", "price": 42000},
        ],
    },
    {
        "id": "single_no_products",
        "method": "single",
        "query": "검은색 오버사이즈 후드티 추천해줘",
        "tpo": "검은색 오버사이즈 후드티 추천",
        "items": [],
    },
    {
        "id": "coordination_outfits_found",
        "method": "coordination",
        "query": "15만원 이하로 가을에 입기 좋은 니트랑 데님 팬츠 코디 추천해줘",
        "tpo": "가을철 니트와 데님 팬츠 코디",
        "outfits": [
            {
                "total_price": 110800,
                "items": [
                    {"brand_name": "브랜드C", "product_name": "케이블 니트", "category": "top", "subcategory": "니트/스웨터", "price": 59800},
                    {"brand_name": "브랜드D", "product_name": "레귤러 데님 팬츠", "category": "bottom", "subcategory": "데님 팬츠", "price": 51000},
                ],
                "verdict": "니트의 차분한 질감과 데님의 캐주얼한 분위기가 잘 어울리는 조합입니다.",
            },
            {
                "total_price": 128000,
                "items": [
                    {"brand_name": "브랜드E", "product_name": "라운드 니트", "category": "top", "subcategory": "니트/스웨터", "price": 68000},
                    {"brand_name": "브랜드F", "product_name": "워싱 데님 팬츠", "category": "bottom", "subcategory": "데님 팬츠", "price": 60000},
                ],
                "verdict": "톤이 과하지 않은 니트와 워싱 데님을 매치해 편안한 가을 코디를 완성합니다.",
            },
        ],
    },
    {
        "id": "coordination_no_outfits",
        "method": "coordination",
        "query": "하객룩으로 입을 블레이저와 슬랙스 코디 추천해줘",
        "tpo": "깔끔한 하객룩",
        "outfits": [],
    },
]


def dump_model_result(parsed: Any) -> dict[str, Any]:
    return parsed.model_dump(mode="json")


def summarize_parsed(result: dict[str, Any]) -> dict[str, Any]:
    slots = result.get("slots", [])
    return {
        "search_type": result.get("search_type"),
        "total_budget": result.get("total_budget"),
        "season_preferences": result.get("season_preferences"),
        "tpo_summary": result.get("tpo_summary"),
        "slot_categories": [s.get("category") for s in slots],
        "slot_subcategories": [s.get("subcategory") for s in slots],
        "clip_queries": [s.get("clip_query_en") for s in slots],
        "slot_budgets": [s.get("slot_budget") for s in slots],
        "max_prices": [s.get("max_price") for s in slots],
        "colors": [s.get("color") for s in slots],
        "fit_types": [s.get("fit_type") for s in slots],
    }


def run_parser_benchmark(details: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for case in PARSER_CASES:
        # 각 모델 인스턴스는 재사용하되, 각 요청은 독립적으로 호출합니다.
        for model_name in PARSER_MODELS:
            parser = QueryParser(model_name=model_name)
            for run_idx in range(1, RUNS + 1):
                started = time.perf_counter()
                try:
                    parsed = parser.parse(case["query"])
                    elapsed = time.perf_counter() - started
                    result = dump_model_result(parsed)
                    summary = summarize_parsed(result)
                    row = {
                        "component": "QueryParser",
                        "case_id": case["id"],
                        "model": model_name,
                        "run": run_idx,
                        "latency_seconds": round(elapsed, 4),
                        "success": True,
                        "error": None,
                    }
                    rows.append(row)
                    details.append({
                        **row,
                        "query": case["query"],
                        "expected": case["expected"],
                        "result": summary,
                        "full_result": result,
                    })
                except Exception as exc:
                    elapsed = time.perf_counter() - started
                    row = {
                        "component": "QueryParser",
                        "case_id": case["id"],
                        "model": model_name,
                        "run": run_idx,
                        "latency_seconds": round(elapsed, 4),
                        "success": False,
                        "error": str(exc),
                    }
                    rows.append(row)
                    details.append({**row, "query": case["query"], "expected": case["expected"]})
    return rows


def run_generator_benchmark(details: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for case in GENERATOR_CASES:
        for model_name in GENERATOR_MODELS:
            generator = ResponseGenerator(model_name=model_name)
            for run_idx in range(1, RUNS + 1):
                started = time.perf_counter()
                try:
                    if case["method"] == "single":
                        result = generator.generate_single_response(
                            query=case["query"], tpo=case["tpo"], items=case["items"]
                        )
                    else:
                        result = generator.generate_coordination_response(
                            query=case["query"], tpo=case["tpo"], outfits=case["outfits"]
                        )
                    elapsed = time.perf_counter() - started
                    row = {
                        "component": "ResponseGenerator",
                        "case_id": case["id"],
                        "model": model_name,
                        "run": run_idx,
                        "latency_seconds": round(elapsed, 4),
                        "success": True,
                        "error": None,
                    }
                    rows.append(row)
                    details.append({
                        **row,
                        "query": case["query"],
                        "method": case["method"],
                        "input": case.get("items", case.get("outfits")),
                        "result": result,
                    })
                except Exception as exc:
                    elapsed = time.perf_counter() - started
                    row = {
                        "component": "ResponseGenerator",
                        "case_id": case["id"],
                        "model": model_name,
                        "run": run_idx,
                        "latency_seconds": round(elapsed, 4),
                        "success": False,
                        "error": str(exc),
                    }
                    rows.append(row)
                    details.append({**row, "query": case["query"], "method": case["method"]})
    return rows


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    fieldnames = ["component", "case_id", "model", "run", "latency_seconds", "success", "error"]
    with path.open("w", newline="", encoding="utf-8-sig") as file:
        writer = csv.DictWriter(file, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def write_summary(path: Path, rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[tuple[str, str], list[float]] = {}
    failures: dict[tuple[str, str], int] = {}
    for row in rows:
        key = (row["component"], row["model"])
        if row["success"]:
            grouped.setdefault(key, []).append(float(row["latency_seconds"]))
        else:
            failures[key] = failures.get(key, 0) + 1

    summary: list[dict[str, Any]] = []
    for (component, model), values in sorted(grouped.items()):
        summary.append({
            "component": component,
            "model": model,
            "successful_calls": len(values),
            "failed_calls": failures.get((component, model), 0),
            "mean_seconds": round(statistics.mean(values), 4),
            "median_seconds": round(statistics.median(values), 4),
            "min_seconds": round(min(values), 4),
            "max_seconds": round(max(values), 4),
        })
    path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def main() -> None:
    if not os.getenv("OPENAI_API_KEY"):
        raise SystemExit("오류: 프로젝트 루트의 .env에 OPENAI_API_KEY를 설정하세요.")

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    details: list[dict[str, Any]] = []

    print("=" * 78)
    print("Fitzza AI 모델 개별 비교 시작")
    print(f"반복 횟수: {RUNS}회")
    print(f"결과 폴더: {OUTPUT_DIR.resolve()}")
    print("=" * 78)

    rows = run_parser_benchmark(details)
    rows.extend(run_generator_benchmark(details))

    raw_csv = OUTPUT_DIR / f"benchmark_calls_{timestamp}.csv"
    summary_json = OUTPUT_DIR / f"benchmark_summary_{timestamp}.json"
    details_json = OUTPUT_DIR / f"benchmark_details_{timestamp}.json"

    write_csv(raw_csv, rows)
    summary = write_summary(summary_json, rows)
    details_json.write_text(json.dumps(details, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n[모델별 요약]")
    for item in summary:
        print(
            f"{item['component']:18} | {item['model']:14} | "
            f"평균 {item['mean_seconds']:7.3f}s | 중앙값 {item['median_seconds']:7.3f}s | "
            f"최소 {item['min_seconds']:7.3f}s | 최대 {item['max_seconds']:7.3f}s | "
            f"실패 {item['failed_calls']}회"
        )

    print("\n[저장 파일]")
    print(f"호출별 CSV : {raw_csv}")
    print(f"요약 JSON  : {summary_json}")
    print(f"상세 JSON  : {details_json}")
    print("\n주의: 시간 요약만으로 품질 우열을 판단하지 마세요. JSON의 expected/result를 비교하고, 생성 응답은 사실 정확성과 유용성을 사람이 평가하세요.")


if __name__ == "__main__":
    main()
