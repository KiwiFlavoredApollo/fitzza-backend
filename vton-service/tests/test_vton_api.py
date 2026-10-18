"""vton-service 비동기 API 엔드포인트 및 클라이언트 테스트."""

from __future__ import annotations

import io
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app.api.vton import (
    _run_background_vton,
    set_dependencies_for_tests,
)
from app.clients.notification_client import NotificationServiceClient
from app.clients.product_client import ProductServiceClient
from app.core.config import Settings, reset_settings_for_tests
from app.core.database import VtonRepository
from app.main import app


def _create_sample_png() -> bytes:
    img = Image.new("RGBA", (100, 100), (255, 0, 0, 255))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


@pytest.fixture(autouse=True)
def setup_test_environment(tmp_path: Path):
    test_storage = tmp_path / "fittings"
    test_storage.mkdir(parents=True, exist_ok=True)
    settings = Settings(
        db_path=":memory:",
        storage_dir=str(test_storage),
        community_service_token="community-secret-token-32bytes-long-padding!",
        vton_service_token="vton-secret-token-32bytes-long-padding-ok!",
        product_service_url="http://localhost:8082",
        notification_service_url="http://localhost:8086",
        public_base_url="http://api.fitzza.com",
    )
    reset_settings_for_tests(settings)
    repo = VtonRepository(db_path=":memory:")
    prod_client = ProductServiceClient(settings)
    notif_client = NotificationServiceClient(settings)
    set_dependencies_for_tests(
        repo=repo,
        product_client=prod_client,
        notification_client=notif_client,
    )
    yield
    set_dependencies_for_tests(None, None, None)
    reset_settings_for_tests(None)


def test_post_vton_requires_x_user_id() -> None:
    client = TestClient(app)
    resp = client.post("/api/vton", json={"productIds": [101]})
    assert resp.status_code == 422  # Header 누락


def test_post_vton_enforces_product_limit() -> None:
    client = TestClient(app)
    resp = client.post(
        "/api/vton",
        json={"productIds": [1, 2, 3, 4, 5, 6]},
        headers={"X-User-Id": "1"},
    )
    assert resp.status_code == 422  # max_length=5 초과


def test_post_vton_returns_202_accepted() -> None:
    client = TestClient(app)
    with patch("app.api.vton._run_background_vton") as mock_bg:
        resp = client.post(
            "/api/vton",
            json={"photoId": 1, "productIds": [101, 102]},
            headers={"X-User-Id": "7"},
        )
        assert resp.status_code == 202
        body = resp.json()
        assert "fittingId" in body
        assert body["status"] == "PENDING"
        assert mock_bg.called


def test_background_vton_success_flow(tmp_path: Path) -> None:
    test_storage = tmp_path / "fittings"
    test_storage.mkdir(parents=True, exist_ok=True)
    repo = VtonRepository(db_path=":memory:")
    mock_prod_client = MagicMock(spec=ProductServiceClient)
    mock_notif_client = MagicMock(spec=NotificationServiceClient)

    mock_prod_client.resolve_product_item.side_effect = [
        {
            "productId": 101,
            "productName": "티셔츠",
            "price": 20000,
            "imageUrl": "https://cdn.example.com/top.png",
            "categoryName": "상의",
            "imageBytes": _create_sample_png(),
        },
        {
            "productId": 201,
            "productName": "바지",
            "price": 30000,
            "imageUrl": "https://cdn.example.com/bottom.png",
            "categoryName": "하의",
            "imageBytes": _create_sample_png(),
        },
    ]

    set_dependencies_for_tests(
        repo=repo,
        product_client=mock_prod_client,
        notification_client=mock_notif_client,
    )

    fitting_id = repo.create_fitting(user_id=7, photo_id=1, product_ids=[101, 201])
    result_url = f"http://api.fitzza.com/api/vton/{fitting_id}/image"

    with patch("app.api.vton.generate_tryon_image", return_value=_create_sample_png()):
        _run_background_vton(
            fitting_id=fitting_id,
            user_id=7,
            photo_id=1,
            product_ids=[101, 201],
            result_image_url=result_url,
        )

    detail = repo.get_fitting_detail(fitting_id)
    assert detail is not None
    assert detail["status"] == "COMPLETED"
    assert detail["resultImageUrl"] == result_url
    assert len(detail["products"]) == 2
    assert detail["products"][0]["productName"] == "티셔츠"

    mock_notif_client.send_fitting_done.assert_called_once()


def test_background_vton_failure_flow() -> None:
    repo = VtonRepository(db_path=":memory:")
    mock_prod_client = MagicMock(spec=ProductServiceClient)
    mock_notif_client = MagicMock(spec=NotificationServiceClient)

    mock_prod_client.resolve_product_item.side_effect = RuntimeError("OpenAI error")

    set_dependencies_for_tests(
        repo=repo,
        product_client=mock_prod_client,
        notification_client=mock_notif_client,
    )

    fitting_id = repo.create_fitting(user_id=7, photo_id=1, product_ids=[101])

    _run_background_vton(
        fitting_id=fitting_id,
        user_id=7,
        photo_id=1,
        product_ids=[101],
        result_image_url="http://dummy/image",
    )

    detail = repo.get_fitting_detail(fitting_id)
    assert detail is not None
    assert detail["status"] == "FAILED"
    assert "OpenAI error" in detail["errorMessage"]

    mock_notif_client.send_fitting_failed.assert_called_once()


def test_get_fitting_detail_owner_and_forbidden() -> None:
    client = TestClient(app)
    repo = VtonRepository(db_path=":memory:")
    set_dependencies_for_tests(repo=repo)

    fitting_id = repo.create_fitting(user_id=7, photo_id=1, product_ids=[101])
    repo.save_fitting_products(
        fitting_id,
        [
            {
                "productId": 101,
                "productName": "상의 상품",
                "price": 15000,
                "imageUrl": "https://img.png",
                "categoryName": "상의",
            }
        ],
    )
    repo.mark_completed(fitting_id, "http://api.fitzza.com/api/vton/1/image")

    # 본인 요청 (200 OK)
    resp = client.get(f"/api/vton/{fitting_id}", headers={"X-User-Id": "7"})
    assert resp.status_code == 200
    data = resp.json()
    assert data["fittingId"] == fitting_id
    assert data["status"] == "COMPLETED"
    assert len(data["products"]) == 1

    # 타인 요청 (403 Forbidden)
    resp_other = client.get(f"/api/vton/{fitting_id}", headers={"X-User-Id": "999"})
    assert resp_other.status_code == 403

    # 없는 피팅 (404 Not Found)
    resp_404 = client.get("/api/vton/99999", headers={"X-User-Id": "7"})
    assert resp_404.status_code == 404


def test_get_fitting_image_serving(tmp_path: Path) -> None:
    test_storage = tmp_path / "fittings"
    test_storage.mkdir(parents=True, exist_ok=True)
    settings = Settings(
        db_path=":memory:",
        storage_dir=str(test_storage),
    )
    reset_settings_for_tests(settings)
    repo = VtonRepository(db_path=":memory:")
    set_dependencies_for_tests(repo=repo)

    fitting_id = repo.create_fitting(user_id=7, photo_id=1, product_ids=[101])
    repo.mark_completed(fitting_id, f"/api/vton/{fitting_id}/image")

    # 아직 파일이 디스크에 없음 -> 404
    client = TestClient(app)
    resp_missing = client.get(f"/api/vton/{fitting_id}/image")
    assert resp_missing.status_code == 404

    # 이미지 파일 저장 후 요청 -> 200 image/png
    img_bytes = _create_sample_png()
    (test_storage / f"{fitting_id}.png").write_bytes(img_bytes)

    resp_ok = client.get(f"/api/vton/{fitting_id}/image")
    assert resp_ok.status_code == 200
    assert resp_ok.headers["content-type"] == "image/png"
    assert resp_ok.content == img_bytes


def test_internal_fitting_endpoint_for_community() -> None:
    client = TestClient(app)
    repo = VtonRepository(db_path=":memory:")
    set_dependencies_for_tests(repo=repo)

    fitting_id = repo.create_fitting(user_id=7, photo_id=1, product_ids=[101])
    repo.save_fitting_products(
        fitting_id,
        [
            {
                "productId": 101,
                "productName": "테스트 셔츠",
                "price": 39000,
                "imageUrl": "https://cdn.example.com/1.png",
                "categoryName": "상의",
            }
        ],
    )
    repo.mark_completed(fitting_id, "http://api.fitzza.com/api/vton/1/image")

    token = "community-secret-token-32bytes-long-padding!"

    # 1. 정상 호출 - 본인 (owned=True)
    resp = client.get(
        f"/fittings/{fitting_id}?userId=7",
        headers={"X-Service-Token": token},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["owned"] is True
    assert data["status"] == "COMPLETED"
    assert data["resultImageUrl"] == "http://api.fitzza.com/api/vton/1/image"
    assert len(data["products"]) == 1
    assert data["products"][0]["productId"] == 101

    # 2. 타인 피팅 조회 (owned=False)
    resp_other = client.get(
        f"/fittings/{fitting_id}?userId=999",
        headers={"X-Service-Token": token},
    )
    assert resp_other.status_code == 200
    assert resp_other.json()["owned"] is False

    # 3. 없는 피팅 (404)
    resp_404 = client.get(
        "/fittings/99999?userId=7",
        headers={"X-Service-Token": token},
    )
    assert resp_404.status_code == 404

    # 4. 토큰 누락 또는 유효하지 않은 토큰 (401)
    resp_unauth = client.get(
        f"/fittings/{fitting_id}?userId=7",
        headers={"X-Service-Token": "invalid-token"},
    )
    assert resp_unauth.status_code == 401
