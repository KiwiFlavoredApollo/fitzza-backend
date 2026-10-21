"""vton-service API 라우터."""

from __future__ import annotations

import logging
import uuid
from pathlib import Path
from typing import Annotated, Any

import json
from fastapi import (
    APIRouter,
    BackgroundTasks,
    File,
    Form,
    Header,
    HTTPException,
    Query,
    Request,
    Response,
    UploadFile,
    status,
)
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse
from pydantic import ValidationError
from starlette.concurrency import run_in_threadpool

from app.clients.notification_client import NotificationServiceClient
from app.clients.product_client import ProductServiceClient
from app.core.config import Settings, get_settings
from app.core.database import VtonRepository
from app.schemas.tryon import (
    FittingCreateRequest,
    FittingCreateResponse,
    FittingDetailResponse,
    FittingListResponse,
    FittingSummaryResponse,
    InternalFittingResponse,
)
from app.services.vton_generator import (
    SUPPORTED_CATEGORIES,
    VtonConfigurationError,
    VtonError,
    VtonUpstreamError,
    VtonValidationError,
    generate_tryon_image,
    load_sample_clothing_png,
    to_png_bytes,
)

logger = logging.getLogger(__name__)

router = APIRouter()

# 전역 의존성 인스턴스 (테스트 시 주입/교체 가능)
_REPO: VtonRepository | None = None
_PRODUCT_CLIENT: ProductServiceClient | None = None
_NOTIFICATION_CLIENT: NotificationServiceClient | None = None


def get_repo() -> VtonRepository:
    global _REPO
    if _REPO is None:
        settings = get_settings()
        _REPO = VtonRepository(
            db_path=settings.db_path,
            postgres_dsn=settings.get_postgres_dsn(),
            postgres_schema=settings.postgres_schema,
        )
    return _REPO


def get_product_client() -> ProductServiceClient:
    global _PRODUCT_CLIENT
    if _PRODUCT_CLIENT is None:
        _PRODUCT_CLIENT = ProductServiceClient()
    return _PRODUCT_CLIENT


def get_notification_client() -> NotificationServiceClient:
    global _NOTIFICATION_CLIENT
    if _NOTIFICATION_CLIENT is None:
        _NOTIFICATION_CLIENT = NotificationServiceClient()
    return _NOTIFICATION_CLIENT


def set_dependencies_for_tests(
    repo: VtonRepository | None = None,
    product_client: ProductServiceClient | None = None,
    notification_client: NotificationServiceClient | None = None,
) -> None:
    global _REPO, _PRODUCT_CLIENT, _NOTIFICATION_CLIENT
    _REPO = repo
    _PRODUCT_CLIENT = product_client
    _NOTIFICATION_CLIENT = notification_client


def _resolve_result_image_url(fitting_id: int, request: Request | None) -> str:
    settings = get_settings()
    if settings.public_base_url.strip():
        base = settings.public_base_url.rstrip("/")
        return f"{base}/api/vton/{fitting_id}/image"
    return f"/api/vton/{fitting_id}/image"


def _resolve_user_image_url(fitting_id: int, request: Request | None) -> str:
    settings = get_settings()
    if settings.public_base_url.strip():
        base = settings.public_base_url.rstrip("/")
        return f"{base}/api/vton/{fitting_id}/user-image"
    return f"/api/vton/{fitting_id}/user-image"


def _run_background_vton(
    fitting_id: int,
    user_id: int,
    photo_id: int | None,
    product_ids: list[int],
    result_image_url: str,
    request_id_header: str | None = None,
) -> None:
    """백그라운드에서 상품 조회, AI 피팅 합성, 결과 이미지 저장, DB 업데이트 및 FCM 알림을 수행합니다."""
    settings = get_settings()
    repo = get_repo()
    prod_client = get_product_client()
    notif_client = get_notification_client()

    logger.info("비동기 가상 피팅 시작 (fittingId=%s, userId=%s)", fitting_id, user_id)

    try:
        # 1. 상품 정보 및 이미지 수집 (기본 5대 카테고리 매핑)
        used_categories: set[str] = set()
        resolved_products: list[dict[str, Any]] = []

        for pid in product_ids:
            # 사용되지 않은 후보 카테고리 선정
            fallback_category = next(
                (c for c in SUPPORTED_CATEGORIES if c not in used_categories),
                "상의",
            )
            item = prod_client.resolve_product_item(
                pid,
                fallback_category=fallback_category,
                request_id_header=request_id_header,
            )
            cat = item.get("categoryName") or "상의"
            if cat in used_categories:
                raise VtonValidationError(
                    f"같은 카테고리({cat})의 상품은 하나만 피팅할 수 있습니다."
                )
            used_categories.add(cat)
            resolved_products.append(item)

        # 1-1. 필수 카테고리(하의) 누락 시 기본 베이직 하의 샘플 이미지 자동 보완
        if "하의" not in used_categories:
            try:
                sample_bottom_bytes = load_sample_clothing_png("하의")
                fallback_bottom_item = {
                    "productId": 0,
                    "productName": "기본 베이직 팬츠",
                    "price": 0,
                    "imageUrl": "",
                    "categoryName": "하의",
                    "imageBytes": sample_bottom_bytes,
                }
                resolved_products.append(fallback_bottom_item)
                used_categories.add("하의")
                logger.info("하의 누락 감지 -> 기본 베이직 하의 이미지 자동 폴백 적용 (fittingId=%s)", fitting_id)
            except Exception as e:
                logger.warning("기본 하의 샘플 로드 실패: %s", e)

        # 상품 DB 영속화
        repo.save_fitting_products(fitting_id, resolved_products)

        categories = [p["categoryName"] for p in resolved_products]
        clothing_images = [p["imageBytes"] for p in resolved_products]

        # 1-2. 사용자 얼굴 이미지 로드 (전달된 photo_id가 있는 경우)
        user_face_bytes: bytes | None = None
        if photo_id is not None:
            photo_info = repo.get_user_photo(photo_id)
            if not photo_info or not photo_info.get("filePath"):
                raise VtonValidationError(f"지정된 사용자 이미지(photoId={photo_id}) 정보를 찾을 수 없습니다.")

            photo_file = Path(photo_info["filePath"])
            if not photo_file.is_file():
                raise VtonValidationError(f"지정된 사용자 이미지 파일(photoId={photo_id})을 찾을 수 없습니다.")

            try:
                user_face_bytes = photo_file.read_bytes()
                logger.info(
                    "사용자 얼굴 사진 적용 (photoId=%s, path=%s)",
                    photo_id,
                    photo_file,
                )
            except Exception as exc:
                logger.error(
                    "사용자 얼굴 사진 파일 로드 실패 (photoId=%s): %s",
                    photo_id,
                    exc,
                )
                raise VtonValidationError(f"사용자 이미지(photoId={photo_id}) 파일을 읽는 데 실패했습니다.") from exc

        # 2. OpenAI 이미지 편집 피팅 실행 (아바타 몸 + 사용자 얼굴 + 의류)
        output_png = generate_tryon_image(
            categories=categories,
            clothing_images=clothing_images,
            avatar_bytes=None,  # 기본 아바타 몸(avatar.png) 사용
            user_face_bytes=user_face_bytes,
            model=settings.openai_image_model,
            api_key=settings.openai_api_key or None,
            timeout_seconds=settings.openai_timeout_seconds,
            max_retries=settings.openai_max_retries,
        )

        # 3. 결과 이미지 파일 디스크 저장
        storage_dir = Path(settings.storage_dir)
        storage_dir.mkdir(parents=True, exist_ok=True)
        image_file = storage_dir / f"{fitting_id}.png"
        image_file.write_bytes(output_png)

        # 4. DB 상태 완료(COMPLETED) 갱신
        repo.mark_completed(fitting_id, result_image_url=result_image_url)

        # 5. notification-service 피팅 완료 FCM 알림 전송
        notif_client.send_fitting_done(
            user_id=user_id,
            fitting_id=fitting_id,
            request_id_header=request_id_header,
        )
        logger.info("비동기 가상 피팅 성공 완료 (fittingId=%s)", fitting_id)

    except Exception as exc:
        err_msg = str(exc) or type(exc).__name__
        logger.error(
            "비동기 가상 피팅 실패 (fittingId=%s, userId=%s): %s",
            fitting_id,
            user_id,
            err_msg,
            exc_info=True,
        )
        # 내부 시스템 경로 등 상세 스택트레이스를 숨기고 정제된 안내 메시지 저장
        user_error_message = (
            str(exc)
            if isinstance(exc, VtonValidationError)
            else "가상 피팅 생성 중 오류가 발생했습니다."
        )
        repo.mark_failed(fitting_id, error_message=user_error_message)
        notif_client.send_fitting_failed(
            user_id=user_id,
            fitting_id=fitting_id,
            request_id_header=request_id_header,
        )


MAX_PHOTO_SIZE_BYTES = 15 * 1024 * 1024  # 15MB
ALLOWED_PHOTO_EXTENSIONS = {"image/png", "image/jpeg", "image/jpg", "image/webp"}


@router.get(
    "/api/vton",
    response_model=FittingListResponse,
    summary="사용자 가상 피팅 결과 목록 조회",
    description="로그인한 사용자의 활성 가상 피팅 결과 목록을 최신순으로 조회합니다.",
)
def list_user_fittings(
    request: Request,
    limit: Annotated[int, Query(ge=1, le=100, description="조회 건수")] = 20,
    offset: Annotated[int, Query(ge=0, description="시작 위치")] = 0,
    x_user_id: Annotated[int | None, Header(alias="X-User-Id", description="게이트웨이 인증 사용자 ID")] = None,
) -> FittingListResponse:
    if not x_user_id or x_user_id <= 0:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="로그인이 필요한 서비스입니다.",
        )

    repo = get_repo()
    raw_items, total_count = repo.get_user_fittings(user_id=x_user_id, limit=limit, offset=offset)

    items: list[FittingSummaryResponse] = []
    for item in raw_items:
        user_img_url = _resolve_user_image_url(item["fittingId"], request) if item.get("photoId") else None
        items.append(
            FittingSummaryResponse(
                fittingId=item["fittingId"],
                userId=item["userId"],
                status=item["status"],
                resultImageUrl=item["resultImageUrl"] if item["status"] == "COMPLETED" else None,
                userImageUrl=user_img_url,
                productCount=item.get("productCount", len(item.get("products", []))),
                products=item.get("products", []),
                createdAt=item["createdAt"],
            )
        )

    return FittingListResponse(items=items, totalCount=total_count)


@router.post(
    "/api/vton",
    response_model=FittingCreateResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="가상 피팅 생성 요청 (비동기)",
    description=(
        "상품 ID 목록과 선택적인 사용자 얼굴 이미지(userImage)를 받아 가상 피팅 작업을 즉시 큐잉(202 Accepted)하고, "
        "백그라운드에서 AI 이미지 합성을 수행한 후 완료 알림을 발송합니다. "
        "JSON 요청 또는 multipart/form-data(userImage 파일 첨부) 모두 지원합니다."
    ),
    openapi_extra={
        "requestBody": {
            "required": True,
            "description": "JSON 본문(상품 ID 목록) 또는 multipart/form-data(상품 ID 목록 + 사용자 얼굴 이미지)",
            "content": {
                "application/json": {
                    "schema": {
                        "type": "object",
                        "required": ["productIds"],
                        "properties": {
                            "photoId": {
                                "type": "integer",
                                "nullable": True,
                                "description": "사용자 사진 ID (선택)",
                                "example": None,
                            },
                            "productIds": {
                                "type": "array",
                                "items": {"type": "integer"},
                                "minItems": 1,
                                "maxItems": 5,
                                "description": "피팅할 상품 ID 목록 (최소 1개, 최대 5개)",
                                "example": [101, 102],
                            },
                        },
                    }
                },
                "multipart/form-data": {
                    "schema": {
                        "type": "object",
                        "required": ["productIds"],
                        "properties": {
                            "productIds": {
                                "type": "string",
                                "description": "피팅할 상품 ID 목록 (예: '[101, 102]' 또는 '101,102')",
                                "example": "[101, 102]",
                            },
                            "userImage": {
                                "type": "string",
                                "format": "binary",
                                "description": "사용자 얼굴/상반신 이미지 파일 (PNG, JPEG, WebP 지원)",
                            },
                        },
                    }
                },
            },
        }
    },
)
async def request_fitting(
    request: Request,
    background_tasks: BackgroundTasks,
    x_user_id: Annotated[int | None, Header(alias="X-User-Id", description="게이트웨이 인증 사용자 ID")] = None,
    x_request_id: Annotated[str | None, Header(alias="X-Request-Id")] = None,
) -> FittingCreateResponse:
    if not x_user_id or x_user_id <= 0:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="로그인이 필요한 서비스입니다.",
        )

    content_type = request.headers.get("content-type", "").lower()
    product_ids: list[int] = []
    photo_id: int | None = None

    # 1. 멀티파트 폼 데이터 처리 (userImage 파일 업로드와 productIds 동시 수신)
    if "multipart/form-data" in content_type:
        form = await request.form()
        raw_product_ids = form.get("productIds")
        if not raw_product_ids:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="productIds가 누락되었습니다.",
            )
        try:
            if isinstance(raw_product_ids, str):
                cleaned_pids = raw_product_ids.strip()
                if cleaned_pids.startswith("["):
                    product_ids = [int(x) for x in json.loads(cleaned_pids)]
                else:
                    product_ids = [int(x.strip()) for x in cleaned_pids.split(",") if x.strip()]
            else:
                product_ids = [int(x) for x in form.getlist("productIds")]
        except (ValueError, json.JSONDecodeError) as exc:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="productIds는 정수 배열 또는 쉼표 구분 문자열이어야 합니다.",
            ) from exc

        # userImage 파일이 첨부된 경우 저장
        user_image_file = form.get("userImage")
        if user_image_file and hasattr(user_image_file, "read"):
            content = await user_image_file.read(MAX_PHOTO_SIZE_BYTES + 1)
            if content:
                if len(content) > MAX_PHOTO_SIZE_BYTES:
                    raise HTTPException(
                        status_code=status.HTTP_400_BAD_REQUEST,
                        detail="사용자 사진 파일 크기는 최대 15MB까지 가능합니다.",
                    )
                try:
                    png_bytes = await run_in_threadpool(to_png_bytes, content)
                except Exception as exc:
                    raise HTTPException(
                        status_code=status.HTTP_400_BAD_REQUEST,
                        detail="유효하지 않은 이미지 파일입니다.",
                    ) from exc

                settings = get_settings()
                photos_dir = settings.get_photos_dir()
                photo_filename = f"user_{x_user_id}_{uuid.uuid4().hex[:12]}.png"
                photo_path = photos_dir / photo_filename
                await run_in_threadpool(photo_path.write_bytes, png_bytes)

                repo = get_repo()
                photo_record = repo.save_user_photo(
                    user_id=x_user_id,
                    file_path=str(photo_path.resolve()),
                )
                photo_id = photo_record["photoId"]
    else:
        # 2. JSON 요청 처리 (기존 스키마 호환)
        try:
            body = await request.json()
        except Exception as exc:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="유효하지 않은 JSON 본문입니다.",
            ) from exc

        try:
            req = FittingCreateRequest(**body)
            product_ids = req.productIds
            photo_id = req.photoId
        except ValidationError as exc:
            raise RequestValidationError(exc.errors()) from exc

    if not product_ids:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="최소 하나 이상의 상품 ID를 전달해야 합니다.",
        )

    # 중복 상품 제거
    unique_product_ids = list(dict.fromkeys(product_ids))
    if len(unique_product_ids) > len(SUPPORTED_CATEGORIES):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"피팅 가능한 상품은 최대 {len(SUPPORTED_CATEGORIES)}개입니다.",
        )

    repo = get_repo()
    if photo_id is not None:
        photo_info = repo.get_user_photo(photo_id)
        if not photo_info or photo_info.get("userId") != x_user_id:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="등록된 사용자 사진 정보를 찾을 수 없습니다.",
            )

    fitting_id = repo.create_fitting(
        user_id=x_user_id,
        photo_id=photo_id,
        product_ids=unique_product_ids,
    )

    result_image_url = _resolve_result_image_url(fitting_id, request)

    # 백그라운드 태스크 등록
    background_tasks.add_task(
        _run_background_vton,
        fitting_id=fitting_id,
        user_id=x_user_id,
        photo_id=photo_id,
        product_ids=unique_product_ids,
        result_image_url=result_image_url,
        request_id_header=x_request_id,
    )

    return FittingCreateResponse(fittingId=fitting_id, status="PENDING")


@router.get(
    "/api/vton/{fittingId}",
    response_model=FittingDetailResponse,
    summary="가상 피팅 결과 상세 조회",
    description="피팅 ID로 현재 진행 상태(PENDING/COMPLETED/FAILED) 및 결과 이미지 URL, 상품 목록을 조회합니다.",
)
def get_fitting(
    fittingId: int,
    x_user_id: Annotated[int | None, Header(alias="X-User-Id", description="게이트웨이 인증 사용자 ID")] = None,
) -> FittingDetailResponse:
    if not x_user_id or x_user_id <= 0:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="로그인이 필요한 서비스입니다.",
        )

    repo = get_repo()
    detail = repo.get_fitting_detail(fittingId)
    if not detail:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="존재하지 않는 가상 피팅입니다.",
        )

    # 본인 피팅인지 검증
    if detail["userId"] != x_user_id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="본인의 피팅 결과만 조회할 수 있습니다.",
        )

    user_img_url = _resolve_user_image_url(detail["fittingId"], None) if detail.get("photoId") else None
    return FittingDetailResponse(
        fittingId=detail["fittingId"],
        userId=detail["userId"],
        userImageUrl=user_img_url,
        status=detail["status"],
        resultImageUrl=detail["resultImageUrl"] if detail["status"] == "COMPLETED" else None,
        errorMessage=detail["errorMessage"],
        products=detail["products"],
        createdAt=detail["createdAt"],
        updatedAt=detail["updatedAt"],
    )


@router.delete(
    "/api/vton/{fittingId}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="가상 피팅 기록 삭제 (소프트 삭제)",
    description="본인의 가상 피팅 기록을 삭제(소프트 삭제)합니다. 삭제된 피팅은 상세 조회 및 이미지 서빙이 차단됩니다.",
)
def delete_fitting(
    fittingId: int,
    x_user_id: Annotated[int | None, Header(alias="X-User-Id", description="게이트웨이 인증 사용자 ID")] = None,
) -> Response:
    if not x_user_id or x_user_id <= 0:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="로그인이 필요한 서비스입니다.",
        )

    repo = get_repo()
    detail = repo.get_fitting_detail(fittingId, include_deleted=True)
    if not detail or detail.get("isDeleted"):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="존재하지 않거나 이미 삭제된 가상 피팅입니다.",
        )

    if detail["userId"] != x_user_id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="본인의 피팅 결과만 삭제할 수 있습니다.",
        )

    deleted = repo.delete_fitting(fitting_id=fittingId, user_id=x_user_id)
    if not deleted:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="존재하지 않거나 이미 삭제된 가상 피팅입니다.",
        )

    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get(
    "/api/vton/{fittingId}/image",
    summary="가상 피팅 결과 PNG 이미지 서빙",
    description="피팅이 완료된 실제 바이너리 PNG 이미지를 브라우저나 앱에서 바로 렌더링할 수 있도록 반환합니다.",
    responses={
        200: {"content": {"image/png": {}}, "description": "가상 피팅 완성 PNG 이미지"},
        404: {"description": "이미지를 찾을 수 없거나 아직 생성되지 않음/삭제됨"},
    },
)
def get_fitting_image(fittingId: int) -> FileResponse:
    # 1. DB에서 삭제 여부 및 상태 선행 검증 (삭제된 피팅 방어)
    repo = get_repo()
    detail = repo.get_fitting_detail(fittingId)
    if not detail or detail.get("isDeleted"):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="존재하지 않거나 삭제된 가상 피팅입니다.",
        )
    if detail["status"] != "COMPLETED":
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"가상 피팅이 아직 완료되지 않았습니다. 현재 상태: {detail['status']}",
        )

    settings = get_settings()
    storage_dir = Path(settings.storage_dir)
    image_file = storage_dir / f"{fittingId}.png"

    if not image_file.is_file():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="피팅 결과 이미지 파일이 존재하지 않습니다.",
        )

    return FileResponse(
        path=str(image_file),
        media_type="image/png",
        headers={"Content-Disposition": f'inline; filename="fitting-{fittingId}.png"'},
    )


@router.get(
    "/api/vton/{fittingId}/user-image",
    summary="사용자 얼굴/상반신 이미지 파일 서빙 (로컬 개발용)",
    description="가상 피팅 요청 시 첨부했던 사용자 얼굴/상반신 원본 이미지를 브라우저나 앱에서 확인할 수 있도록 스트리밍합니다.",
    responses={
        200: {"content": {"image/png": {}}, "description": "사용자 원본 이미지"},
        404: {"description": "사용자 이미지를 첨부하지 않았거나 이미지를 찾을 수 없음"},
    },
)
def get_fitting_user_image(
    fittingId: int,
    x_user_id: Annotated[int | None, Header(alias="X-User-Id", description="게이트웨이 인증 사용자 ID")] = None,
) -> FileResponse:
    if not x_user_id or x_user_id <= 0:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="로그인이 필요한 서비스입니다.",
        )

    repo = get_repo()
    detail = repo.get_fitting_detail(fittingId)
    if not detail or detail.get("isDeleted"):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="존재하지 않거나 삭제된 가상 피팅입니다.",
        )

    # 본인 피팅인지 검증 (타인 얼굴 사진 열람 차단 / IDOR 방어)
    if detail["userId"] != x_user_id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="본인의 피팅 이미지만 조회할 수 있습니다.",
        )

    photo_id = detail.get("photoId")
    if not photo_id:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="해당 피팅에는 사용자가 등록한 이미지가 없습니다 (기본 아바타 사용).",
        )

    photo_info = repo.get_user_photo(photo_id)
    if not photo_info or not photo_info.get("filePath"):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="사용자 사진 정보를 찾을 수 없습니다.",
        )

    photo_file = Path(photo_info["filePath"])
    if not photo_file.is_file():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="사용자 사진 파일이 서버에 존재하지 않습니다.",
        )

    return FileResponse(
        path=str(photo_file),
        media_type="image/png",
        headers={"Content-Disposition": f'inline; filename="user-{photo_id}.png"'},
    )


@router.get(
    "/fittings/{fittingId}",
    response_model=InternalFittingResponse,
    summary="[내부 서비스용] 커뮤니티 공유 유효성 검증",
    description="community-service 전용 내부 API. X-Service-Token 헤더를 검증하고 해당 사용자의 소유권과 상품 목록을 반환합니다.",
)
def get_internal_fitting(
    fittingId: int,
    userId: int,
    x_service_token: Annotated[str | None, Header(alias="X-Service-Token")] = None,
) -> InternalFittingResponse:
    settings = get_settings()
    if not settings.verify_service_token(x_service_token, allowed_callers=["community"]):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="유효하지 않은 서비스 토큰입니다.",
        )

    repo = get_repo()
    detail = repo.get_fitting_detail(fittingId)
    if not detail:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="피팅 정보를 찾을 수 없습니다.",
        )

    is_owned = (detail["userId"] == userId)
    products_for_community = [
        {
            "productId": p["productId"],
            "productName": p["productName"],
            "price": p["price"],
            "imageUrl": p["imageUrl"],
        }
        for p in detail.get("products", [])
    ]

    return InternalFittingResponse(
        owned=is_owned,
        status=detail["status"],
        resultImageUrl=detail["resultImageUrl"] if detail["status"] == "COMPLETED" else None,
        products=products_for_community,
    )
