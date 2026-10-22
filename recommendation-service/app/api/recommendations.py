import logging
from typing import Any, Optional

from fastapi import (
    APIRouter,
    BackgroundTasks,
    File,
    Form,
    Header,
    Query,
    Request,
    Response,
    UploadFile,
    status,
)
from starlette.concurrency import run_in_threadpool

logger = logging.getLogger(__name__)

from app.clients.notification_client import NotificationServiceClient
from app.clients.product_client import ProductServiceClient
from app.clients.redis_client import RedisClient
from app.clients.user_client import UserServiceClient
from app.core.config import Settings, get_settings
from app.core.database import RecommendationRepository
from app.services.cache_service import CacheService

from app.schemas.recommendation import (
    InternalComboProductItem,
    InternalComboResponse,
    RecommendationAcceptedResponse,
    RecommendationCreateRequest,
    RecommendationDetailResponse,
    RecommendationHistoryItem,
    RecommendationHistoryPageResponse,
    RecommendedComboItem,
    RecommendedProductItem,
    SizeFeedbackRequest,
    VisualSearchResultItem,
    VisualSearchResponse,
)

FIT_SOURCE_EVIDENCE_MAP: dict[str, str] = {
    "learned_preference": "기존 구매·설문 기반 선호 실측과 가장 유사한 사이즈입니다.",
    "recent_purchase": "최근 구매하신 상품 실측을 기준으로 추천된 사이즈입니다.",
    "similar_body_profile": "유사 체형 사용자의 실측 데이터를 기준으로 추천된 사이즈입니다.",
    "body_profile": "등록하신 사용자 신체 프로필 정보를 기준으로 추천된 사이즈입니다.",
}


class RecommendationApiError(Exception):
    def __init__(self, status_code: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message


def _require_user_id(x_user_id: str | None) -> int:
    if x_user_id is None or not str(x_user_id).strip():
        raise RecommendationApiError(
            status_code=status.HTTP_401_UNAUTHORIZED,
            code="UNAUTHENTICATED",
            message="로그인이 필요합니다.",
        )
    try:
        user_id = int(str(x_user_id).strip())
    except ValueError as exc:
        raise RecommendationApiError(
            status_code=status.HTTP_401_UNAUTHORIZED,
            code="UNAUTHENTICATED",
            message="유효하지 않은 사용자 식별자입니다.",
        ) from exc

    if user_id <= 0:
        raise RecommendationApiError(
            status_code=status.HTTP_401_UNAUTHORIZED,
            code="UNAUTHENTICATED",
            message="유효하지 않은 사용자 식별자입니다.",
        )
    return user_id


def _require_service_token(
    settings: Settings,
    x_service_token: str | None,
    allowed_callers: tuple[str, ...],
) -> None:
    if not settings.verify_service_token(x_service_token, allowed_callers):
        raise RecommendationApiError(
            status_code=status.HTTP_401_UNAUTHORIZED,
            code="UNAUTHENTICATED",
            message="유효한 서비스 토큰이 필요합니다.",
        )


def _get_repository(request: Request) -> RecommendationRepository:
    repo = getattr(request.app.state, "repository", None)
    if repo is None:
        settings = _get_settings(request)
        repo = RecommendationRepository(
            db_path=settings.db_path,
            postgres_dsn=settings.get_postgres_dsn(),
            postgres_schema=settings.postgres_schema,
        )
        request.app.state.repository = repo
    return repo


def _get_settings_from_app(app: Any) -> Settings:
    settings = getattr(app.state, "settings", None)
    if settings is None:
        settings = get_settings()
        app.state.settings = settings
    return settings


def _get_settings(request: Request) -> Settings:
    return _get_settings_from_app(request.app)


def _get_redis_client(app: Any) -> RedisClient:
    redis_client = getattr(app.state, "redis_client", None)
    if redis_client is None:
        settings = _get_settings_from_app(app)
        redis_client = RedisClient(settings)
        app.state.redis_client = redis_client
    return redis_client


def _get_cache_service(app: Any) -> CacheService:
    cache_service = getattr(app.state, "cache_service", None)
    if cache_service is None:
        settings = _get_settings_from_app(app)
        redis_client = _get_redis_client(app)
        cache_service = CacheService(redis_client=redis_client, settings=settings)
        app.state.cache_service = cache_service
    return cache_service


def _get_coordinator(app: Any) -> Any:
    coordinator = getattr(app.state, "coordinator", None)
    if coordinator is None:
        from app.services.coordinator import FashionPipelineCoordinator

        cache_service = _get_cache_service(app)
        coordinator = FashionPipelineCoordinator(cache_service=cache_service)
        app.state.coordinator = coordinator
    return coordinator


def _get_search_engine(app: Any) -> Any:
    search_engine = getattr(app.state, "search_engine", None)
    if search_engine is None:
        coordinator = _get_coordinator(app)
        search_engine = coordinator.engine
        app.state.search_engine = search_engine
    return search_engine



def _sync_user_body(
    size_resolver: Any,
    user_id: str,
    user_body: dict[str, Any],
) -> None:
    if size_resolver is None or not user_body:
        return
    raw_height = user_body.get("height")
    raw_weight = user_body.get("weight")
    raw_shoe = user_body.get("shoeSize")

    try:
        height = float(raw_height) if raw_height is not None else None
    except (TypeError, ValueError):
        height = None

    try:
        weight = float(raw_weight) if raw_weight is not None else None
    except (TypeError, ValueError):
        weight = None

    try:
        shoe_size = float(raw_shoe) if raw_shoe is not None else None
    except (TypeError, ValueError):
        shoe_size = None

    if height is None and weight is None and shoe_size is None:
        return

    mock_db = getattr(size_resolver, "mock_db", None)
    if not isinstance(mock_db, dict):
        return

    users = mock_db.setdefault("users", [])
    existing = next(
        (u for u in users if str(u.get("user_id")) == str(user_id)),
        None,
    )
    if existing is None:
        entry: dict[str, Any] = {"user_id": str(user_id)}
        if height is not None:
            entry["height"] = height
        if weight is not None:
            entry["weight"] = weight
        if shoe_size is not None:
            entry["shoe_size"] = shoe_size
        users.append(entry)
    else:
        if height is not None:
            existing["height"] = height
        if weight is not None:
            existing["weight"] = weight
        if shoe_size is not None:
            existing["shoe_size"] = shoe_size


def _extract_color_text(item: dict[str, Any]) -> str | None:
    raw_color = item.get("color_raw")
    if raw_color and str(raw_color).strip():
        return str(raw_color).strip()

    norm_colors = item.get("color_normalized")
    if isinstance(norm_colors, list) and norm_colors:
        return str(norm_colors[0]).strip()
    if isinstance(norm_colors, str) and norm_colors.strip():
        return norm_colors.strip()
    return None


def _map_product_item(item: dict[str, Any]) -> dict[str, Any]:
    size_rec = item.get("size_recommendation") or {}
    source_type = str(item.get("fit_source_type") or "none")

    recommended_size = size_rec.get("size_label") or item.get("size")
    raw_fit_score = size_rec.get("predicted_fit_score")
    fit_percent: float | None = None
    if isinstance(raw_fit_score, (int, float)):
        score_val = float(raw_fit_score)
        fit_percent = round(score_val * 100.0 if score_val <= 1.0 else score_val, 1)

    evidence = item.get("evidence") or FIT_SOURCE_EVIDENCE_MAP.get(source_type)

    raw_product_id = item.get("product_id", item.get("productId", 0))
    try:
        product_id = int(raw_product_id or 0)
    except (TypeError, ValueError):
        product_id = 0

    raw_price = item.get("price", 0)
    try:
        price = int(raw_price or 0)
    except (TypeError, ValueError):
        price = 0

    return RecommendedProductItem(
        productId=product_id,
        productName=str(item.get("product_name") or item.get("productName") or ""),
        brandName=item.get("brand_name") or item.get("brandName"),
        category=item.get("category"),
        subcategory=item.get("subcategory"),
        price=price,
        imageUrl=item.get("image_url") or item.get("imageUrl"),
        color=item.get("color") or _extract_color_text(item),
        size=recommended_size,
        fitPercent=fit_percent,
        evidence=evidence,
    ).model_dump()


def _map_combo_outfit(outfit: dict[str, Any]) -> dict[str, Any]:
    raw_items = outfit.get("items") or []
    mapped_items = [_map_product_item(item) for item in raw_items]

    total_price = int(
        outfit.get("total_price")
        or outfit.get("totalPrice")
        or sum(i["price"] for i in mapped_items)
    )
    raw_pass = outfit.get("pass_status", outfit.get("passStatus", True))
    if isinstance(raw_pass, bool):
        pass_status = "PASS" if raw_pass else "WARN"
    else:
        pass_status = str(raw_pass)

    harmony_score = float(
        outfit.get("harmony_score")
        or outfit.get("harmonyScore")
        or outfit.get("final_score")
        or 0.0
    )
    feedback_summary = str(
        outfit.get("feedback_summary")
        or outfit.get("feedbackSummary")
        or outfit.get("verdict")
        or outfit.get("styling_tip")
        or ""
    )
    budget_penalty = float(outfit.get("budget_penalty") or 0.0)
    budget_exceeded = bool(
        outfit.get("budgetExceeded")
        if "budgetExceeded" in outfit
        else budget_penalty > 0.0
    )

    return {
        "items": mapped_items,
        "totalPrice": total_price,
        "passStatus": pass_status,
        "harmonyScore": harmony_score,
        "feedbackSummary": feedback_summary,
        "budgetExceeded": budget_exceeded,
    }


def process_recommendation_request(
    app: Any,
    request_id: int,
    user_id: int,
    query_text: str,
    request_id_header: str | None = None,
) -> None:
    settings: Settings = getattr(app.state, "settings", None) or get_settings()
    repository: RecommendationRepository = getattr(
        app.state, "repository", None
    ) or RecommendationRepository(
        db_path=settings.db_path,
        postgres_dsn=settings.get_postgres_dsn(),
        postgres_schema=settings.postgres_schema,
    )
    user_client: UserServiceClient = getattr(
        app.state, "user_client", None
    ) or UserServiceClient(settings)
    notification_client: NotificationServiceClient = getattr(
        app.state, "notification_client", None
    ) or NotificationServiceClient(settings)

    notification_content = "요청하신 AI 패션 추천 결과가 도착했어요."

    try:
        user_body = user_client.get_user_body(
            user_id=user_id,
            request_id_header=request_id_header,
        )
        coordinator = _get_coordinator(app)
        _sync_user_body(
            size_resolver=getattr(coordinator, "size_resolver", None),
            user_id=str(user_id),
            user_body=user_body,
        )

        cache_service = _get_cache_service(app)
        cached_result = cache_service.get_pipeline_result(
            user_query=query_text,
            user_id=str(user_id),
        )
        if cached_result is not None:
            pipeline_result = cached_result
        else:
            pipeline_result = coordinator.run(
                user_query=query_text,
                user_id=str(user_id),
            )
            cache_service.set_pipeline_result(
                user_query=query_text,
                user_id=str(user_id),
                result=pipeline_result,
            )

        result_type = pipeline_result.get("type", "coordination")

        tpo_summary = pipeline_result.get("tpo")
        comment = pipeline_result.get("comment")

        if result_type == "single":
            raw_items = pipeline_result.get("results") or []
            mapped_items = [_map_product_item(item) for item in raw_items[:9]]
            repository.complete_single_request(
                request_id=request_id,
                tpo_summary=tpo_summary,
                items=mapped_items,
                message=comment,
            )
        else:
            raw_outfits = pipeline_result.get("outfits") or []
            mapped_combos = [_map_combo_outfit(outfit) for outfit in raw_outfits[:2]]
            repository.complete_combo_request(
                request_id=request_id,
                user_id=user_id,
                tpo_summary=tpo_summary,
                combos=mapped_combos,
                message=comment,
            )
    except Exception as exc:
        logger.error(
            "추천 파이프라인 처리 실패 (requestId=%s, userId=%s): %s",
            request_id,
            user_id,
            exc,
            exc_info=True,
        )
        failure_msg = "추천을 생성하는 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요."
        repository.fail_request(
            request_id=request_id,
            message=failure_msg,
        )
        notification_content = "추천 요청 처리가 완료되었습니다. 결과를 확인해주세요."

    notification_client.send_recommendation_done(
        user_id=user_id,
        request_id=request_id,
        content=notification_content,
        request_id_header=request_id_header,
    )


public_router = APIRouter(tags=["추천"])
internal_router = APIRouter(tags=["내부 통신"])


@public_router.post(
    "",
    response_model=RecommendationAcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="자연어 추천 요청",
)
def create_recommendation(
    payload: RecommendationCreateRequest,
    background_tasks: BackgroundTasks,
    request: Request,
    x_user_id: str | None = Header(default=None, alias="X-User-Id"),
    x_request_id: str | None = Header(default=None, alias="X-Request-Id"),
) -> RecommendationAcceptedResponse:
    user_id = _require_user_id(x_user_id)
    clean_query = (payload.query or "").strip()
    if not clean_query:
        raise RecommendationApiError(
            status_code=status.HTTP_400_BAD_REQUEST,
            code="INVALID_REQUEST",
            message="추천 질의(query)를 입력해주세요.",
        )

    repository = _get_repository(request)
    request_id = repository.create_request(
        user_id=user_id,
        query_text=clean_query,
    )

    background_tasks.add_task(
        process_recommendation_request,
        request.app,
        request_id,
        user_id,
        clean_query,
        x_request_id,
    )

    return RecommendationAcceptedResponse(
        requestId=request_id,
        status="PROCESSING",
    )


@public_router.get(
    "",
    response_model=RecommendationHistoryPageResponse,
    status_code=status.HTTP_200_OK,
    summary="내 추천 대화 목록",
)
def list_recommendations(
    request: Request,
    page: int = Query(default=0),
    size: int = Query(default=20),
    x_user_id: str | None = Header(default=None, alias="X-User-Id"),
) -> RecommendationHistoryPageResponse:
    user_id = _require_user_id(x_user_id)
    if page < 0 or size < 1 or size > 100:
        raise RecommendationApiError(
            status_code=status.HTTP_400_BAD_REQUEST,
            code="INVALID_REQUEST",
            message="page는 0 이상, size는 1~100이어야 합니다.",
        )

    repository = _get_repository(request)
    page_data = repository.list_user_requests(
        user_id=user_id,
        page=page,
        size=size,
    )
    items = [RecommendationHistoryItem(**item) for item in page_data["content"]]
    return RecommendationHistoryPageResponse(
        content=items,
        page=page_data["page"],
        totalPages=page_data["totalPages"],
    )


@public_router.get(
    "/{requestId}",
    response_model=RecommendationDetailResponse,
    status_code=status.HTTP_200_OK,
    summary="추천 결과 조회",
)
def get_recommendation_detail(
    requestId: int,
    request: Request,
    x_user_id: str | None = Header(default=None, alias="X-User-Id"),
) -> RecommendationDetailResponse:
    user_id = _require_user_id(x_user_id)
    repository = _get_repository(request)
    record = repository.get_request(requestId)
    if record is None:
        raise RecommendationApiError(
            status_code=status.HTTP_404_NOT_FOUND,
            code="RECOMMENDATION_NOT_FOUND",
            message="추천 결과를 찾을 수 없습니다.",
        )

    if record["userId"] != user_id:
        raise RecommendationApiError(
            status_code=status.HTTP_403_FORBIDDEN,
            code="NOT_OWNER",
            message="본인의 추천 결과만 조회할 수 있습니다.",
        )

    return RecommendationDetailResponse(
        requestId=record["requestId"],
        status=record["status"],
        searchType=record["searchType"],
        tpoSummary=record["tpoSummary"],
        items=[RecommendedProductItem(**item) for item in record["items"]],
        combos=[RecommendedComboItem(**combo) for combo in record["combos"]],
        message=record["message"],
    )


MAX_SEARCH_IMAGE_SIZE_BYTES = 15 * 1024 * 1024  # 15MB
ALLOWED_SEARCH_IMAGE_TYPES = {
    "image/png",
    "image/jpeg",
    "image/jpg",
    "image/webp",
}


@public_router.post(
    "/search/image",
    response_model=VisualSearchResponse,
    status_code=status.HTTP_200_OK,
    summary="의류 이미지 기반 유사 상품 검색 (Visual Search)",
    description=(
        "업로드된 의류 사진을 Fashion-CLIP 비전 인코더로 분석하여, "
        "유사도 0.75 이상으로 시각적으로 일치하는 상품 목록을 유사도 순으로 추천 및 반환합니다.\n\n"
        "- 지원 이미지 형식: PNG, JPEG, JPG, WebP (최대 15MB)\n"
        "- 별도 옵션 입력 없이 오로지 의류 사진 파일 하나만 첨부하면 됩니다."
    ),
)
async def search_products_by_image(
    request: Request,
    file: UploadFile = File(..., description="유사 상품을 검색할 의류/스타일 사진 파일"),
) -> VisualSearchResponse:
    content_type = (file.content_type or "").lower().strip()
    if content_type and content_type not in ALLOWED_SEARCH_IMAGE_TYPES:
        raise RecommendationApiError(
            status_code=status.HTTP_400_BAD_REQUEST,
            code="INVALID_IMAGE_TYPE",
            message="지원하지 않는 이미지 형식입니다. PNG, JPEG, WebP 파일만 업로드할 수 있습니다.",
        )

    image_bytes = await file.read(MAX_SEARCH_IMAGE_SIZE_BYTES + 1)
    if not image_bytes:
        raise RecommendationApiError(
            status_code=status.HTTP_400_BAD_REQUEST,
            code="EMPTY_IMAGE",
            message="이미지 파일 내용이 비어 있습니다.",
        )
    if len(image_bytes) > MAX_SEARCH_IMAGE_SIZE_BYTES:
        raise RecommendationApiError(
            status_code=status.HTTP_400_BAD_REQUEST,
            code="IMAGE_TOO_LARGE",
            message="이미지 파일 크기는 최대 15MB까지 가능합니다.",
        )

    # 유효한 이미지인지 및 실제 포맷/애니메이션 검증
    try:
        import io
        from PIL import Image

        with Image.open(io.BytesIO(image_bytes)) as img:
            image_format = (img.format or "").upper()
            is_animated = bool(getattr(img, "is_animated", False))
            img.verify()
    except Exception as exc:
        raise RecommendationApiError(
            status_code=status.HTTP_400_BAD_REQUEST,
            code="CORRUPTED_IMAGE",
            message="손상되었거나 유효하지 않은 이미지 파일입니다.",
        ) from exc

    if image_format not in {"PNG", "JPEG", "WEBP"} or is_animated:
        raise RecommendationApiError(
            status_code=status.HTTP_400_BAD_REQUEST,
            code="INVALID_IMAGE_TYPE",
            message="지원하지 않는 이미지 형식입니다. PNG, JPEG, WebP 정지 이미지만 업로드할 수 있습니다.",
        )

    def _execute_search():
        engine = _get_search_engine(request.app)
        return engine.search_by_image(image_input=image_bytes)

    try:
        detected_category, detected_color, raw_results = await run_in_threadpool(_execute_search)
    except ValueError as val_err:
        raise RecommendationApiError(
            status_code=status.HTTP_400_BAD_REQUEST,
            code="INVALID_SEARCH_PARAMS",
            message=str(val_err),
        ) from val_err
    except Exception as exc:
        logger.error("이미지 유사 상품 검색 중 오류 발생: %s", exc, exc_info=True)
        raise RecommendationApiError(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            code="SEARCH_ERROR",
            message="이미지 유사 상품 검색 처리 중 서버 오류가 발생했습니다.",
        ) from exc

    items = [
        VisualSearchResultItem(
            productId=item.get("product_id") or 0,
            productName=item.get("product_name") or "",
            brandName=item.get("brand_name"),
            category=item.get("category"),
            subcategory=item.get("subcategory"),
            price=item.get("price") or 0,
            imageUrl=item.get("image_url"),
            color=(
                item.get("color_raw")
                or (item.get("color_normalized")[0] if item.get("color_normalized") else None)
            ),
            similarityScore=item.get("similarity_score", 0.0),
            productUrl=item.get("product_url"),
        )
        for item in raw_results
    ]
    return VisualSearchResponse(
        detectedCategory=detected_category,
        detectedColor=detected_color,
        items=items,
        totalCount=len(items),
    )


@internal_router.get(
    "/recommendations/combos/{comboId}",
    response_model=InternalComboResponse,
    status_code=status.HTTP_200_OK,
    summary="코디 조합 조회 (내부 통신)",
)
def get_combo_internal(
    comboId: int,
    request: Request,
    userId: int = Query(..., gt=0),
    x_service_token: str | None = Header(default=None, alias="X-Service-Token"),
) -> InternalComboResponse:
    settings = _get_settings(request)
    _require_service_token(
        settings=settings,
        x_service_token=x_service_token,
        allowed_callers=("community", "product"),
    )

    cache_service = _get_cache_service(request.app)
    combo = cache_service.get_combo(comboId)
    if combo is None:
        repository = _get_repository(request)
        combo = repository.get_combo(comboId)
        if combo is not None:
            cache_service.set_combo(comboId, combo)

    if combo is None:
        raise RecommendationApiError(
            status_code=status.HTTP_404_NOT_FOUND,
            code="COMBO_NOT_FOUND",
            message="코디 조합을 찾을 수 없습니다.",
        )

    if combo["userId"] != userId:
        return InternalComboResponse(
            owned=False,
            items=[],
            totalPrice=None,
            feedbackSummary=None,
        )

    return InternalComboResponse(
        owned=True,
        items=[InternalComboProductItem(**item) for item in combo["items"]],
        totalPrice=combo["totalPrice"],
        feedbackSummary=combo["feedbackSummary"],
    )


@internal_router.post(
    "/recommendations/size-feedback",
    status_code=status.HTTP_202_ACCEPTED,
    summary="사이즈 설문 반영 (내부 통신)",
)
def receive_size_feedback(
    payload: SizeFeedbackRequest,
    request: Request,
    x_service_token: str | None = Header(default=None, alias="X-Service-Token"),
    x_request_id: str | None = Header(default=None, alias="X-Request-Id"),
) -> Response:
    settings = _get_settings(request)
    _require_service_token(
        settings=settings,
        x_service_token=x_service_token,
        allowed_callers=("review",),
    )

    repository = _get_repository(request)
    product_client: ProductServiceClient = getattr(
        request.app.state, "product_client", None
    ) or ProductServiceClient(settings)

    purchased_spec = product_client.find_option_measurement(
        product_id=payload.productId,
        option_id=payload.optionId,
        request_id_header=x_request_id,
    )

    repository.save_size_feedback(
        user_id=payload.userId,
        product_id=payload.productId,
        option_id=payload.optionId,
        size_feedback=payload.sizeFeedback,
        purchased_spec=purchased_spec,
    )

    cache_service = _get_cache_service(request.app)
    cache_service.invalidate_user_pipeline(user_id=str(payload.userId))

    return Response(status_code=status.HTTP_202_ACCEPTED)

