import io
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app.core.config import Settings
from app.main import app
from app.schemas.recommendation import VisualSearchResponse


def create_sample_png_bytes(color: tuple[int, int, int] = (255, 0, 0), size: tuple[int, int] = (64, 64)) -> bytes:
    """테스트용 유효한 인메모리 PNG 바이트 생성."""
    buf = io.BytesIO()
    img = Image.new("RGB", size, color=color)
    img.save(buf, format="PNG")
    return buf.getvalue()


@pytest.fixture
def client(tmp_path):
    settings = Settings(
        db_path=str(tmp_path / "test.db"),
        postgres_host="",
        redis_host="",
    )
    app.state.settings = settings
    return TestClient(app)


def test_visual_search_success_both_endpoints(client):
    """표준 경로(/api/recommendations/search/image) 및 하위호환(/api/v1/recommendations/search/image) 모두 정상 동작 검증."""
    mock_results = [
        {
            "product_id": 101,
            "product_name": "블랙 오버핏 티셔츠",
            "brand_name": "무신사 스탠다드",
            "category": "상의",
            "subcategory": "반소매 티셔츠",
            "price": 29000,
            "image_url": "https://example.com/p101.jpg",
            "color_raw": "BLACK",
            "color_normalized": ["black"],
            "similarity_score": 0.885,
            "product_url": "https://example.com/products/101",
        },
        {
            "product_id": 102,
            "product_name": "베이직 와이드 슬랙스",
            "brand_name": "무신사 스탠다드",
            "category": "하의",
            "subcategory": "슬랙스",
            "price": 39000,
            "image_url": "https://example.com/p102.jpg",
            "color_raw": "CHARCOAL",
            "color_normalized": ["gray"],
            "similarity_score": 0.742,
            "product_url": "https://example.com/products/102",
        },
    ]

    mock_engine = MagicMock()
    mock_engine.search_by_image.return_value = ("상의", "black", mock_results)
    app.state.search_engine = mock_engine

    sample_png = create_sample_png_bytes()

    # 1. 신규 표준 경로 테스트 (순수 이미지만 전송)
    response = client.post(
        "/api/recommendations/search/image",
        files={"file": ("test_shirt.png", sample_png, "image/png")},
    )
    assert response.status_code == 200
    data = response.json()
    parsed = VisualSearchResponse(**data)
    assert parsed.detectedCategory == "상의"
    assert parsed.detectedColor == "black"
    assert parsed.totalCount == 2
    assert len(parsed.items) == 2
    assert parsed.items[0].productId == 101
    assert parsed.items[0].productName == "블랙 오버핏 티셔츠"
    assert parsed.items[0].similarityScore == 0.885
    assert parsed.items[0].color == "BLACK"

    # 2. 하위 호환 경로 테스트 (/api/v1/...)
    v1_response = client.post(
        "/api/v1/recommendations/search/image",
        files={"file": ("test_shirt.png", sample_png, "image/png")},
    )
    assert v1_response.status_code == 200
    v1_data = v1_response.json()
    assert v1_data["detectedCategory"] == "상의"
    assert v1_data["detectedColor"] == "black"
    assert v1_data["totalCount"] == 2


def test_visual_search_unsupported_mime_type(client):
    """지원하지 않는 파일 형식인 경우 400 INVALID_IMAGE_TYPE 반환."""
    response = client.post(
        "/api/recommendations/search/image",
        files={"file": ("notes.txt", b"Hello text file", "text/plain")},
    )
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_IMAGE_TYPE"


def test_visual_search_empty_file(client):
    """비어 있는 파일인 경우 400 EMPTY_IMAGE 반환."""
    response = client.post(
        "/api/recommendations/search/image",
        files={"file": ("empty.png", b"", "image/png")},
    )
    assert response.status_code == 400
    assert response.json()["code"] == "EMPTY_IMAGE"


def test_visual_search_corrupted_image(client):
    """깨지거나 유효하지 않은 이미지 바이트인 경우 400 CORRUPTED_IMAGE 반환."""
    corrupted_bytes = b"\x89PNG\r\n\x1a\n" + b"random invalid garbage bytes"
    response = client.post(
        "/api/recommendations/search/image",
        files={"file": ("corrupted.png", corrupted_bytes, "image/png")},
    )
    assert response.status_code == 400
    assert response.json()["code"] == "CORRUPTED_IMAGE"


def test_visual_search_engine_caching():
    """FashionSearchEngine.search_by_image 가 CacheService와 정상 연동되는지 검증."""
    from app.services.cache_service import CacheService
    from app.services.search_engine import FashionSearchEngine

    mock_redis = MagicMock()
    mock_redis.get.return_value = None  # 첫 호출 시 캐시 미스
    mock_redis.set.return_value = True

    settings = Settings(db_path=":memory:")
    cache = CacheService(redis_client=mock_redis, settings=settings)

    with patch("app.services.search_engine.QdrantClient"):
        with patch("app.services.search_engine.CLIPModel"):
            with patch("app.services.search_engine.CLIPProcessor"):
                engine = FashionSearchEngine(cache_service=cache)

                # encode_image, classify_category, classify_color 모킹
                engine.encode_image = MagicMock(return_value=[0.1] * 512)
                engine.classify_category = MagicMock(return_value=("상의", 0.95))
                engine.classify_color = MagicMock(return_value=("black", 0.90))
                # client.query_points 모킹
                hit_mock = MagicMock(
                    score=0.92,
                    payload={
                        "product_id": 1,
                        "product_name": "테스트 상품",
                        "price": 20000,
                        "color_raw": "BLACK",
                        "color_normalized": ["black"],
                    },
                )
                engine.client.query_points = MagicMock(return_value=MagicMock(points=[hit_mock]))

                sample_png = create_sample_png_bytes()

                # 첫 번째 검색 -> 캐시 저장 호출 확인
                cat1, col1, results1 = engine.search_by_image(sample_png)
                assert cat1 == "상의"
                assert col1 == "black"
                assert len(results1) == 1
                assert results1[0]["product_id"] == 1
                assert mock_redis.set.called

                # 두 번째 검색: 캐시 히트 시뮬레이션
                mock_redis.get.return_value = results1
                cat2, col2, results2 = engine.search_by_image(sample_png)
                assert cat2 == "상의"
                assert col2 == "black"
                assert results2 == results1


def test_visual_search_threshold_cutoff():
    """기본 유사도 0.75 미만 상품은 걸러지고 0.75 이상 상품은 개수 제한 없이 모두 반환되는지 검증."""
    from app.services.search_engine import FashionSearchEngine

    with patch("app.services.search_engine.QdrantClient"):
        with patch("app.services.search_engine.CLIPModel"):
            with patch("app.services.search_engine.CLIPProcessor"):
                engine = FashionSearchEngine()
                engine.encode_image = MagicMock(return_value=[0.1] * 512)
                engine.classify_category = MagicMock(return_value=("상의", 0.95))
                engine.classify_color = MagicMock(return_value=("black", 0.90))

                hit_pass1 = MagicMock(score=0.82, payload={"product_id": 1, "product_name": "유사 상품 1", "color_normalized": ["white"]})
                hit_pass2 = MagicMock(score=0.76, payload={"product_id": 2, "product_name": "유사 상품 2", "color_normalized": ["blue"]})
                hit_borderline = MagicMock(score=0.75, payload={"product_id": 3, "product_name": "경계 상품 3", "color_normalized": ["blue"]})
                hit_fail = MagicMock(score=0.71, payload={"product_id": 4, "product_name": "탈락 상품", "color_normalized": ["blue"]})

                engine.client.query_points = MagicMock(
                    return_value=MagicMock(points=[hit_pass1, hit_pass2, hit_borderline, hit_fail])
                )

                sample_png = create_sample_png_bytes()
                cat, col, results = engine.search_by_image(sample_png)

                assert cat == "상의"
                assert col == "black"
                assert len(results) == 3
                assert [r["product_id"] for r in results] == [1, 2, 3]
                assert all(r["similarity_score"] >= 0.75 for r in results)


def test_classify_category_top_selection():
    """FashionSearchEngine.classify_category 가 코사인 유사도 1위 카테고리를 정확히 선택하는지 검증."""
    from app.services.search_engine import FashionSearchEngine

    with patch("app.services.search_engine.QdrantClient"):
        with patch("app.services.search_engine.CLIPModel"):
            with patch("app.services.search_engine.CLIPProcessor"):
                engine = FashionSearchEngine()
                engine._category_vectors = {
                    "상의": [1.0, 0.0, 0.0],
                    "아우터": [0.0, 1.0, 0.0],
                    "하의": [0.0, 0.0, 1.0],
                    "신발": [0.5, 0.5, 0.0],
                    "모자": [0.0, 0.5, 0.5],
                }

                best_cat, score = engine.classify_category([0.9, 0.1, 0.0])
                assert best_cat == "상의"
                assert score > 0.8

                best_cat, score = engine.classify_category([0.1, 0.0, 0.95])
                assert best_cat == "하의"
                assert score > 0.9


def test_classify_color_top_selection():
    """FashionSearchEngine.classify_color 가 16대 표준 색상 중 1위를 정확히 선택하는지 검증."""
    from app.services.search_engine import FashionSearchEngine

    with patch("app.services.search_engine.QdrantClient"):
        with patch("app.services.search_engine.CLIPModel"):
            with patch("app.services.search_engine.CLIPProcessor"):
                engine = FashionSearchEngine()
                engine._color_vectors = {
                    "black": [1.0, 0.0, 0.0],
                    "white": [0.0, 1.0, 0.0],
                    "navy": [0.0, 0.0, 1.0],
                }

                best_color, score = engine.classify_color([0.95, 0.05, 0.0])
                assert best_color == "black"
                assert score > 0.9

                best_color, score = engine.classify_color([0.05, 0.92, 0.0])
                assert best_color == "white"
                assert score > 0.9


def test_visual_search_color_boost_ranking():
    """동일 색상(+0.15 보너스) 상품이 기본 유사도가 더 높은 다른 색상 상품을 역전하여 1위로 랭킹되는지 검증."""
    from app.services.search_engine import FashionSearchEngine

    with patch("app.services.search_engine.QdrantClient"):
        with patch("app.services.search_engine.CLIPModel"):
            with patch("app.services.search_engine.CLIPProcessor"):
                engine = FashionSearchEngine()
                engine.encode_image = MagicMock(return_value=[0.1] * 512)
                engine.classify_category = MagicMock(return_value=("상의", 0.95))
                # 검정색으로 자동 감지됨
                engine.classify_color = MagicMock(return_value=("black", 0.92))

                # 상품 A: 기본 벡터 유사도는 0.78로 높으나 색상이 'white'
                hit_a = MagicMock(
                    score=0.78,
                    payload={"product_id": 10, "product_name": "화이트 티셔츠", "color_normalized": ["white"]},
                )
                # 상품 B: 기본 벡터 유사도는 0.70이나 색상이 감지된 색상과 동일한 'black'
                # -> 0.70 + 0.15 = 0.85 로 1위로 도약해야 함!
                hit_b = MagicMock(
                    score=0.70,
                    payload={"product_id": 20, "product_name": "블랙 티셔츠", "color_normalized": ["black"]},
                )

                engine.client.query_points = MagicMock(
                    return_value=MagicMock(points=[hit_a, hit_b])
                )

                sample_png = create_sample_png_bytes()
                cat, col, results = engine.search_by_image(sample_png)

                assert cat == "상의"
                assert col == "black"
                assert len(results) == 2
                # 블랙 티셔츠(20번)가 화이트(10번)보다 높은 랭킹(1위)을 차지해야 함
                assert results[0]["product_id"] == 20
                assert results[0]["similarity_score"] == 0.85
                assert results[1]["product_id"] == 10
                assert results[1]["similarity_score"] == 0.78
