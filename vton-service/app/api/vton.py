"""vton-service API 라우터."""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Annotated, Any

from fastapi import APIRouter, BackgroundTasks, Header, HTTPException, Request, Response, status
from fastapi.responses import FileResponse
from starlette.concurrency import run_in_threadpool

from app.clients.notification_client import NotificationServiceClient
from app.clients.product_client import ProductServiceClient
from app.core.config import Settings, get_settings
from app.core.database import VtonRepository
from app.schemas.tryon import (
    FittingCreateRequest,
    FittingCreateResponse,
    FittingDetailResponse,
    InternalFittingResponse,
)
from app.services.vton_generator import (
    SUPPORTED_CATEGORIES,
    VtonConfigurationError,
    VtonError,
    VtonUpstreamError,
    VtonValidationError,
    generate_tryon_image,
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

        # 상품 DB 영속화
        repo.save_fitting_products(fitting_id, resolved_products)

        categories = [p["categoryName"] for p in resolved_products]
        clothing_images = [p["imageBytes"] for p in resolved_products]

        # 2. OpenAI 이미지 편집 피팅 실행
        output_png = generate_tryon_image(
            categories=categories,
            clothing_images=clothing_images,
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



@router.post(
    "/api/vton",
    response_model=FittingCreateResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="가상 피팅 생성 요청 (비동기)",
    description=(
        "상품 ID 목록을 받아 가상 피팅 작업을 즉시 큐잉(202 Accepted)하고, "
        "백그라운드에서 AI 이미지 합성을 수행한 후 완료 알림을 발송합니다."
    ),
)
def request_fitting(
    req: FittingCreateRequest,
    background_tasks: BackgroundTasks,
    request: Request,
    x_user_id: Annotated[int, Header(alias="X-User-Id", description="게이트웨이 인증 사용자 ID")],
    x_request_id: Annotated[str | None, Header(alias="X-Request-Id")] = None,
) -> FittingCreateResponse:
    if not x_user_id or x_user_id <= 0:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="로그인이 필요한 서비스입니다.",
        )

    # 중복 상품 제거
    unique_product_ids = list(dict.fromkeys(req.productIds))
    if len(unique_product_ids) > len(SUPPORTED_CATEGORIES):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"피팅 가능한 상품은 최대 {len(SUPPORTED_CATEGORIES)}개입니다.",
        )

    repo = get_repo()
    fitting_id = repo.create_fitting(
        user_id=x_user_id,
        photo_id=req.photoId,
        product_ids=unique_product_ids,
    )

    result_image_url = _resolve_result_image_url(fitting_id, request)

    # 백그라운드 태스크 등록
    background_tasks.add_task(
        _run_background_vton,
        fitting_id=fitting_id,
        user_id=x_user_id,
        photo_id=req.photoId,
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
    x_user_id: Annotated[int, Header(alias="X-User-Id", description="게이트웨이 인증 사용자 ID")],
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

    return FittingDetailResponse(**detail)


@router.delete(
    "/api/vton/{fittingId}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="가상 피팅 기록 삭제 (소프트 삭제)",
    description="본인의 가상 피팅 기록을 삭제(소프트 삭제)합니다. 삭제된 피팅은 상세 조회 및 이미지 서빙이 차단됩니다.",
)
def delete_fitting(
    fittingId: int,
    x_user_id: Annotated[int, Header(alias="X-User-Id", description="게이트웨이 인증 사용자 ID")],
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
