import json
from datetime import datetime, timezone
import pytest
from fastapi.testclient import TestClient

from app.core.database import RecommendationRepository
from app.main import app
from app.schemas.analytics import AnalyticsResponse


@pytest.fixture
def repo():
    return RecommendationRepository(db_path=":memory:")


def test_analytics_empty_db(repo):
    data = repo.get_analytics_data()
    assert "daily" in data
    assert "weekly" in data
    assert "monthly" in data
    assert "yearly" in data

    # Check daily
    daily = data["daily"]
    assert daily["label"] == "일간"
    assert len(daily["usage"]) == 6  # 00시, 04시, 08시, 12시, 16시, 20시
    for u in daily["usage"]:
        assert u["input"] == 0
        assert u["output"] == 0
    assert daily["keywords"] == []
    assert daily["products"] == []

    # Check weekly
    weekly = data["weekly"]
    assert weekly["label"] == "주간"
    assert len(weekly["usage"]) == 7  # 월, 화, 수, 목, 금, 토, 일
    for u in weekly["usage"]:
        assert u["input"] == 0
        assert u["output"] == 0

    # Check monthly
    monthly = data["monthly"]
    assert monthly["label"] == "월간"
    assert len(monthly["usage"]) == 5  # 1주 ~ 5주

    # Check yearly
    yearly = data["yearly"]
    assert yearly["label"] == "연간"
    assert len(yearly["usage"]) == 12  # 1월 ~ 12월


def test_analytics_data_aggregation(repo):
    # Reference date: 2025-05-15 14:30:00 UTC (Thursday, Week 3 of May, Month 5)
    ref_dt = datetime(2025, 5, 15, 14, 30, 0, tzinfo=timezone.utc)

    # 1. Add request 1: Single request at 14:10 on 2025-05-15 (slot: 12시, weekday: 목, week: 3주, month: 5월)
    req1_id = repo.create_request(user_id=1, query_text="깔끔한 출근룩 미니멀 스타일 추천해줘")
    repo.complete_single_request(
        request_id=req1_id,
        tpo_summary="출근룩, 미니멀",
        items=[
            {"productId": 101, "productName": "릴렉스드 셔츠", "brandName": "무신사 스탠다드", "price": 35000},
            {"productId": 102, "productName": "슬림 슬랙스", "brandName": "슬로우애시드", "price": 49000},
        ],
        message="성공",
        prompt_tokens=1500,
        completion_tokens=500,
    )
    # Manually update created_at to 2025-05-15 14:10:00 UTC
    with repo._lock:
        conn = repo._get_sqlite_connection()
        conn.execute(
            "UPDATE recommendation_requests SET created_at = '2025-05-15T14:10:00+00:00' WHERE request_id = ?",
            (req1_id,),
        )
        conn.commit()

    # 2. Add request 2: Combo request at 08:30 on 2025-05-15 (slot: 08시, weekday: 목, week: 3주, month: 5월)
    req2_id = repo.create_request(user_id=2, query_text="데이트룩 캐주얼 코디")
    repo.complete_combo_request(
        request_id=req2_id,
        user_id=2,
        tpo_summary="데이트룩, 캐주얼",
        combos=[
            {
                "items": [
                    {"productId": 101, "productName": "릴렉스드 셔츠", "brandName": "무신사 스탠다드", "price": 35000},
                    {"productId": 103, "productName": "오버핏 블레이저", "brandName": "드로우핏", "price": 120000},
                ],
                "totalPrice": 155000,
            }
        ],
        message="성공",
        prompt_tokens=2000,
        completion_tokens=800,
    )
    with repo._lock:
        conn = repo._get_sqlite_connection()
        conn.execute(
            "UPDATE recommendation_requests SET created_at = '2025-05-15T08:30:00+00:00' WHERE request_id = ?",
            (req2_id,),
        )
        conn.commit()

    # Query analytics with reference date
    analytics = repo.get_analytics_data(reference_dt=ref_dt)

    daily = analytics["daily"]
    # Check usage buckets
    usage_by_label = {u["label"]: u for u in daily["usage"]}
    assert usage_by_label["12시"]["input"] == 1500
    assert usage_by_label["12시"]["output"] == 500
    assert usage_by_label["08시"]["input"] == 2000
    assert usage_by_label["08시"]["output"] == 800
    assert usage_by_label["00시"]["input"] == 0

    # Check keywords
    kw_names = [k["name"] for k in daily["keywords"]]
    assert "출근룩" in kw_names
    assert "미니멀" in kw_names
    assert "데이트룩" in kw_names
    assert "캐주얼" in kw_names

    # Check products
    products = daily["products"]
    assert len(products) > 0
    # "릴렉스드 셔츠" was in both req1 and req2 -> count should be 2
    shirt = next((p for p in products if p["name"] == "릴렉스드 셔츠"), None)
    assert shirt is not None
    assert shirt["count"] == 2
    assert shirt["brand"] == "무신사 스탠다드"


def test_analytics_api_endpoint():
    client = TestClient(app)
    fake_repo = RecommendationRepository(db_path=":memory:")
    app.state.repository = fake_repo

    # Seed data
    req_id = fake_repo.create_request(user_id=1, query_text="가을 코디 추천")
    fake_repo.complete_single_request(
        request_id=req_id,
        tpo_summary="가을 코디",
        items=[{"productId": 1, "productName": "트렌치 코트", "brandName": "스파오", "price": 89000}],
        message="성공",
        prompt_tokens=1000,
        completion_tokens=400,
    )

    # Test GET /api/recommendations/analytics
    res = client.get("/api/recommendations/analytics")
    assert res.status_code == 200
    body = res.json()
    validated = AnalyticsResponse(**body)
    assert validated.daily.label == "일간"
    assert validated.weekly.label == "주간"
    assert validated.monthly.label == "월간"
    assert validated.yearly.label == "연간"

    # Test with query param ?date=YYYY-MM-DD
    today_str = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    res_with_date = client.get(f"/api/recommendations/analytics?date={today_str}")
    assert res_with_date.status_code == 200
    body_with_date = res_with_date.json()
    assert body_with_date["daily"]["startDate"] == today_str

    # Test invalid date query param
    bad_res = client.get("/api/recommendations/analytics?date=not-a-date")
    assert bad_res.status_code == 400
    assert bad_res.json()["code"] == "INVALID_DATE_FORMAT"

    # Test gateway backward compatibility path /api/v1/recommendations/analytics
    compat_res = client.get("/api/v1/recommendations/analytics")
    assert compat_res.status_code == 200
