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
    assert resp.status_code == 401  # Header 누락 시 401 Unauthorized


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
            json={"photoId": None, "productIds": [101, 102]},
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

    fitting_id = repo.create_fitting(user_id=7, photo_id=None, product_ids=[101, 201])
    result_url = f"http://api.fitzza.com/api/vton/{fitting_id}/image"

    with patch("app.api.vton.generate_tryon_image", return_value=_create_sample_png()):
        _run_background_vton(
            fitting_id=fitting_id,
            user_id=7,
            photo_id=None,
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
    assert "가상 피팅 생성 중 오류가 발생했습니다." in detail["errorMessage"]

    mock_notif_client.send_fitting_failed.assert_called_once()


def test_background_vton_duplicate_category_flow() -> None:
    repo = VtonRepository(db_path=":memory:")
    mock_prod_client = MagicMock(spec=ProductServiceClient)
    mock_notif_client = MagicMock(spec=NotificationServiceClient)

    mock_prod_client.resolve_product_item.side_effect = [
        {
            "productId": 101,
            "productName": "상의 1",
            "price": 20000,
            "imageUrl": "https://cdn.example.com/top1.png",
            "categoryName": "상의",
            "imageBytes": _create_sample_png(),
        },
        {
            "productId": 102,
            "productName": "상의 2",
            "price": 25000,
            "imageUrl": "https://cdn.example.com/top2.png",
            "categoryName": "상의",
            "imageBytes": _create_sample_png(),
        },
    ]

    set_dependencies_for_tests(
        repo=repo,
        product_client=mock_prod_client,
        notification_client=mock_notif_client,
    )

    fitting_id = repo.create_fitting(user_id=7, photo_id=None, product_ids=[101, 102])

    _run_background_vton(
        fitting_id=fitting_id,
        user_id=7,
        photo_id=None,
        product_ids=[101, 102],
        result_image_url="/api/vton/1/image",
    )

    detail = repo.get_fitting_detail(fitting_id)
    assert detail is not None
    assert detail["status"] == "FAILED"
    assert "같은 카테고리(상의)의 상품은 하나만 피팅할 수 있습니다." in detail["errorMessage"]
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


def test_delete_fitting_and_defend_access(tmp_path: Path) -> None:
    test_storage = tmp_path / "fittings"
    test_storage.mkdir(parents=True, exist_ok=True)
    settings = Settings(
        db_path=":memory:",
        storage_dir=str(test_storage),
    )
    reset_settings_for_tests(settings)
    repo = VtonRepository(db_path=":memory:")
    set_dependencies_for_tests(repo=repo)
    client = TestClient(app)

    fitting_id = repo.create_fitting(user_id=7, photo_id=1, product_ids=[101])
    repo.mark_completed(fitting_id, f"/api/vton/{fitting_id}/image")
    img_bytes = _create_sample_png()
    (test_storage / f"{fitting_id}.png").write_bytes(img_bytes)

    # 1. 삭제 전 정상 조회 가능
    resp = client.get(f"/api/vton/{fitting_id}", headers={"X-User-Id": "7"})
    assert resp.status_code == 200
    resp_img = client.get(f"/api/vton/{fitting_id}/image")
    assert resp_img.status_code == 200

    # 2. 타인이 삭제 요청 시 403 Forbidden
    resp_forbid = client.delete(f"/api/vton/{fitting_id}", headers={"X-User-Id": "999"})
    assert resp_forbid.status_code == 403

    # 2-1. X-User-Id 누락 시 401 Unauthorized
    resp_no_auth = client.delete(f"/api/vton/{fitting_id}")
    assert resp_no_auth.status_code == 401

    # 3. 본인이 정상 삭제 (204 No Content)
    resp_del = client.delete(f"/api/vton/{fitting_id}", headers={"X-User-Id": "7"})
    assert resp_del.status_code == 204

    # 4. 삭제 후 상세 조회 차단 (404 Not Found)
    resp_get_after = client.get(f"/api/vton/{fitting_id}", headers={"X-User-Id": "7"})
    assert resp_get_after.status_code == 404

    # 5. 삭제 후 이미지 서빙 차단 (404 Not Found)
    resp_img_after = client.get(f"/api/vton/{fitting_id}/image")
    assert resp_img_after.status_code == 404

    # 6. 이미 삭제된 피팅 재삭제 시도 시 404 Not Found
    resp_del_again = client.delete(f"/api/vton/{fitting_id}", headers={"X-User-Id": "7"})
    assert resp_del_again.status_code == 404


def test_user_photo_upload_and_get_flow() -> None:
    client = TestClient(app)
    img_bytes = _create_sample_png()

    # 1. 정상 업로드 (201 Created)
    files = {"file": ("my_body.png", img_bytes, "image/png")}
    resp = client.post("/api/vton/photos", files=files, headers={"X-User-Id": "7"})
    assert resp.status_code == 201
    data = resp.json()
    assert data["userId"] == 7
    assert data["photoId"] > 0
    assert data["photoUrl"] == f"http://api.fitzza.com/api/vton/photos/{data['photoId']}"
    assert "createdAt" in data

    # 2. 본인 사진 조회 (200 OK)
    photo_id = data["photoId"]
    resp_get = client.get(f"/api/vton/photos/{photo_id}", headers={"X-User-Id": "7"})
    assert resp_get.status_code == 200
    assert resp_get.headers["content-type"] == "image/png"
    assert len(resp_get.content) > 0

    # 3. 인증 헤더 누락 시 401 Unauthorized
    resp_no_auth = client.get(f"/api/vton/photos/{photo_id}")
    assert resp_no_auth.status_code == 401

    # 4. 타인(사용자 99)이 조회 시 404 Not Found (IDOR 방어)
    resp_other = client.get(f"/api/vton/photos/{photo_id}", headers={"X-User-Id": "99"})
    assert resp_other.status_code == 404


def test_user_photo_upload_validations() -> None:
    client = TestClient(app)
    img_bytes = _create_sample_png()

    # 1. X-User-Id 누락 시 401 Unauthorized
    resp_no_auth = client.post(
        "/api/vton/photos",
        files={"file": ("photo.png", img_bytes, "image/png")},
    )
    assert resp_no_auth.status_code == 401

    # 2. 지원하지 않는 파일 형식 (400 Bad Request)
    resp_bad_ext = client.post(
        "/api/vton/photos",
        files={"file": ("doc.txt", b"plain text", "text/plain")},
        headers={"X-User-Id": "7"},
    )
    assert resp_bad_ext.status_code == 400
    assert "지원하지 않는 이미지 형식" in resp_bad_ext.json()["detail"]

    # 3. 빈 파일 업로드 시 400 Bad Request
    resp_empty = client.post(
        "/api/vton/photos",
        files={"file": ("empty.png", b"", "image/png")},
        headers={"X-User-Id": "7"},
    )
    assert resp_empty.status_code == 400
    assert "비어 있습니다" in resp_empty.json()["detail"]

    # 4. 손상된/유효하지 않은 이미지 데이터 시 400 Bad Request
    resp_corrupt = client.post(
        "/api/vton/photos",
        files={"file": ("corrupt.png", b"not-a-real-png", "image/png")},
        headers={"X-User-Id": "7"},
    )
    assert resp_corrupt.status_code == 400
    assert "유효하지 않은 이미지" in resp_corrupt.json()["detail"]

    # 5. 최대 용량(15MB) 초과 시 400 Bad Request
    with patch("app.api.vton.MAX_PHOTO_SIZE_BYTES", 100):
        resp_too_large = client.post(
            "/api/vton/photos",
            files={"file": ("large.png", img_bytes, "image/png")},
            headers={"X-User-Id": "7"},
        )
        assert resp_too_large.status_code == 400
        assert "15MB" in resp_too_large.json()["detail"]


def test_get_user_photo_not_found() -> None:
    client = TestClient(app)
    resp = client.get("/api/vton/photos/999999", headers={"X-User-Id": "7"})
    assert resp.status_code == 404
    assert "찾을 수 없습니다" in resp.json()["detail"]


def test_post_vton_with_photo_id_ownership_validation() -> None:
    client = TestClient(app)
    img_bytes = _create_sample_png()

    # 사용자 7의 사진 업로드
    files = {"file": ("my_body.png", img_bytes, "image/png")}
    resp_up = client.post("/api/vton/photos", files=files, headers={"X-User-Id": "7"})
    assert resp_up.status_code == 201
    photo_id = resp_up.json()["photoId"]

    # 1. 타인(사용자 99)이 해당 photoId로 피팅 요청 시 404 차단
    resp_other = client.post(
        "/api/vton",
        json={"photoId": photo_id, "productIds": [101]},
        headers={"X-User-Id": "99"},
    )
    assert resp_other.status_code == 404

    # 2. 존재하지 않는 photoId 요청 시 404 차단
    resp_none = client.post(
        "/api/vton",
        json={"photoId": 88888, "productIds": [101]},
        headers={"X-User-Id": "7"},
    )
    assert resp_none.status_code == 404

    # 3. 본인(사용자 7)의 정상 photoId 요청 시 202 Accepted
    with patch("app.api.vton._run_background_vton"):
        resp_ok = client.post(
            "/api/vton",
            json={"photoId": photo_id, "productIds": [101]},
            headers={"X-User-Id": "7"},
        )
        assert resp_ok.status_code == 202


def test_background_vton_uses_custom_user_photo_bytes(tmp_path: Path) -> None:
    test_storage = tmp_path / "fittings"
    test_storage.mkdir(parents=True, exist_ok=True)
    test_photos = tmp_path / "photos"
    test_photos.mkdir(parents=True, exist_ok=True)

    settings = Settings(
        db_path=":memory:",
        storage_dir=str(test_storage),
        photos_dir=str(test_photos),
    )
    reset_settings_for_tests(settings)
    repo = VtonRepository(db_path=":memory:")
    mock_prod_client = MagicMock(spec=ProductServiceClient)
    mock_notif_client = MagicMock(spec=NotificationServiceClient)

    user_photo_file = test_photos / "user_7_body.png"
    user_photo_bytes = _create_sample_png()
    user_photo_file.write_bytes(user_photo_bytes)
    photo_record = repo.save_user_photo(user_id=7, file_path=str(user_photo_file))
    photo_id = photo_record["photoId"]

    mock_prod_client.resolve_product_item.return_value = {
        "productId": 101,
        "productName": "상의",
        "price": 20000,
        "imageUrl": "https://cdn.example.com/top.png",
        "categoryName": "상의",
        "imageBytes": _create_sample_png(),
    }

    set_dependencies_for_tests(
        repo=repo,
        product_client=mock_prod_client,
        notification_client=mock_notif_client,
    )

    fitting_id = repo.create_fitting(user_id=7, photo_id=photo_id, product_ids=[101])

    with patch("app.api.vton.generate_tryon_image", return_value=_create_sample_png()) as mock_generate:
        _run_background_vton(
            fitting_id=fitting_id,
            user_id=7,
            photo_id=photo_id,
            product_ids=[101],
            result_image_url="/api/vton/1/image",
        )

        assert mock_generate.called
        kwargs = mock_generate.call_args.kwargs
        assert kwargs["avatar_bytes"] == user_photo_bytes
        mock_notif_client.send_fitting_done.assert_called_once()


def test_background_vton_fails_when_user_photo_file_missing() -> None:
    repo = VtonRepository(db_path=":memory:")
    mock_prod_client = MagicMock(spec=ProductServiceClient)
    mock_notif_client = MagicMock(spec=NotificationServiceClient)

    # 존재하지 않는 경로로 사진 메타데이터 저장
    photo_record = repo.save_user_photo(user_id=7, file_path="/non/existent/path/photo.png")
    photo_id = photo_record["photoId"]

    mock_prod_client.resolve_product_item.return_value = {
        "productId": 101,
        "productName": "상의",
        "price": 20000,
        "imageUrl": "https://cdn.example.com/top.png",
        "categoryName": "상의",
        "imageBytes": _create_sample_png(),
    }

    set_dependencies_for_tests(
        repo=repo,
        product_client=mock_prod_client,
        notification_client=mock_notif_client,
    )

    fitting_id = repo.create_fitting(user_id=7, photo_id=photo_id, product_ids=[101])

    with patch("app.api.vton.generate_tryon_image") as mock_generate:
        _run_background_vton(
            fitting_id=fitting_id,
            user_id=7,
            photo_id=photo_id,
            product_ids=[101],
            result_image_url="/api/vton/1/image",
        )

        # 사진 파일 로드 실패 시 마네킹으로 대체하지 않고 피팅 실패 처리되어야 함
        mock_generate.assert_not_called()
        detail = repo.get_fitting_detail(fitting_id)
        assert detail is not None
        assert detail["status"] == "FAILED"
        mock_notif_client.send_fitting_failed.assert_called_once()


