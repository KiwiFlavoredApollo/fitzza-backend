import logging
from datetime import datetime, timezone
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
from app.clients.vton_client import VtonServiceClient
from app.core.config import Settings, get_settings
from app.core.database import RecommendationRepository
from app.services.cache_service import CacheService

from app.schemas.recommendation import (
    InternalComboProductItem,
    InternalComboResponse,
    PersonalizedPageResponse,
    PersonalizedProductItem,
    ProductSizeRecommendationResponse,
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
from app.schemas.analytics import AnalyticsResponse

FIT_SOURCE_EVIDENCE_MAP: dict[str, str] = {
    "learned_preference": "기존 구매·설문 기반 선호 실측과 가장 유사한 사이즈입니다.",
    "recent_purchase": "최근 구매하신 상품 실측을 기준으로 추천된 사이즈입니다.",
    "similar_body_profile": "유사 체형 사용자의 실측 데이터를 기준으로 추천된 사이즈입니다.",
    "body_profile": "등록하신 사용자 신체 프로필 정보를 기준으로 추천된 사이즈입니다.",
}

CATEGORY_KO_TO_EN: dict[str, str] = {
    "상의": "top",
    "아우터": "outer",
    "하의": "bottom",
    "신발": "shoes",
    "모자": "hat",
    "top": "top",
    "outer": "outer",
    "bottom": "bottom",
    "shoes": "shoes",
    "hat": "hat",
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


def _require_admin(
    settings: Settings,
    x_user_id: str | None,
    x_admin_token: str | None = None,
) -> None:
    if settings.verify_admin_token(x_admin_token):
        return

    user_id = _require_user_id(x_user_id)
    if user_id not in settings.get_admin_user_ids():
        raise RecommendationApiError(
            status_code=status.HTTP_403_FORBIDDEN,
            code="FORBIDDEN",
            message="관리자 권한이 필요합니다.",
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


def _get_vton_client(request: Request) -> VtonServiceClient:
    vton_client = getattr(request.app.state, "vton_client", None)
    if vton_client is None:
        settings = _get_settings(request)
        vton_client = VtonServiceClient(settings)
        request.app.state.vton_client = vton_client
    return vton_client


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


def _get_personalized_feed_service(app: Any) -> Any:
    feed_service = getattr(app.state, "personalized_feed_service", None)
    if feed_service is None:
        from app.services.personalized_feed_service import PersonalizedFeedService
        from app.services.personalized_vector_engine import PersonalizedVectorEngine

        settings = _get_settings_from_app(app)
        search_engine = getattr(app.state, "search_engine", None)
        qdrant_client = getattr(search_engine, "client", None) if search_engine else None

        vector_engine = PersonalizedVectorEngine(
            qdrant_client=qdrant_client,
            collection_name=settings.qdrant_collection_name,
            search_engine=search_engine,
            qdrant_url=settings.qdrant_url,
        )
        feed_service = PersonalizedFeedService(
            qdrant_client=qdrant_client,
            collection_name=settings.qdrant_collection_name,
            vector_engine=vector_engine,
            qdrant_url=settings.qdrant_url,
        )
        app.state.personalized_feed_service = feed_service
    return feed_service



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


def _get_size_resolver(app: Any) -> Any:
    size_resolver = getattr(app.state, "size_resolver", None)
    if size_resolver is not None and hasattr(size_resolver, "resolve_and_calculate_size"):
        return size_resolver

    coordinator = getattr(app.state, "coordinator", None)
    if coordinator is not None:
        coord_resolver = getattr(coordinator, "size_resolver", None)
        if coord_resolver is not None and hasattr(coord_resolver, "resolve_and_calculate_size"):
            return coord_resolver

    from app.services.size_resolver import SizeProfileResolver

    size_resolver = SizeProfileResolver()
    app.state.size_resolver = size_resolver
    return size_resolver


def _sync_user_size_preference(
    size_resolver: Any,
    user_id: str,
    subcategory: str,
    fit_type: str,
    pref_spec: dict[str, float] | None,
) -> None:
    if size_resolver is None or not subcategory or not pref_spec:
        return
    mock_db = getattr(size_resolver, "mock_db", None)
    if not isinstance(mock_db, dict):
        return

    prefs = mock_db.setdefault("user_preferences", [])
    existing = next(
        (
            p
            for p in prefs
            if str(p.get("user_id")) == str(user_id)
            and p.get("subcategory") == subcategory
        ),
        None,
    )
    if existing is None:
        prefs.append(
            {
                "preference_id": len(prefs) + 1,
                "user_id": str(user_id),
                "subcategory": subcategory,
                "fit_type": fit_type or "레귤러",
                "preference_spec": dict(pref_spec),
                "sample_count": 1,
            }
        )
    else:
        existing["preference_spec"] = dict(pref_spec)
        if fit_type:
            existing["fit_type"] = fit_type


def _infer_category_from_measurements(sizes: list[dict[str, Any]]) -> str:
    for entry in sizes:
        if not isinstance(entry, dict):
            continue
        measurements = entry.get("measurements") or {}
        if not isinstance(measurements, dict):
            continue
        keys = set(measurements.keys())
        if keys.intersection({"허리단면", "엉덩이단면", "허벅지단면", "밑위"}):
            return "bottom"
        if keys.intersection({"발길이", "발볼", "발목높이", "굽높이"}):
            return "shoes"
        if keys.intersection({"머리둘레", "챙길이", "깊이"}):
            return "hat"
    return "top"


def _get_qdrant_product_payload(app: Any, product_id: int) -> dict[str, Any] | None:
    qdrant_client = getattr(app.state, "qdrant_client", None)
    if qdrant_client is None:
        search_engine = getattr(app.state, "search_engine", None)
        if search_engine is not None:
            qdrant_client = getattr(search_engine, "client", None)
    if qdrant_client is None:
        coordinator = getattr(app.state, "coordinator", None)
        if coordinator is not None:
            engine = getattr(coordinator, "engine", None)
            if engine is not None:
                qdrant_client = getattr(engine, "client", None)
    if qdrant_client is None:
        feed_service = getattr(app.state, "personalized_feed_service", None)
        if feed_service is not None:
            qdrant_client = getattr(feed_service, "client", None)

    if qdrant_client is None:
        return None

    settings = _get_settings_from_app(app)
    try:
        from qdrant_client.http import models

        scroll_filter = models.Filter(
            must=[
                models.FieldCondition(
                    key="product_id",
                    match=models.MatchValue(value=int(product_id)),
                )
            ]
        )
        points, _ = qdrant_client.scroll(
            collection_name=settings.qdrant_collection_name,
            scroll_filter=scroll_filter,
            with_payload=True,
            with_vectors=False,
            limit=1,
        )
        if points and points[0].payload:
            return dict(points[0].payload)
    except Exception as exc:
        logger.warning("Qdrant 상품 페이로드 조회 실패 (productId=%s): %s", product_id, exc)
    return None


def _resolve_product_for_size(
    app: Any,
    product_id: int,
    request_id_header: str | None = None,
) -> dict[str, Any] | None:
    payload = _get_qdrant_product_payload(app, product_id)
    raw_sizes: list[dict[str, Any]] = []
    raw_category = ""
    subcategory = ""
    fit_type = "레귤러"
    product_name = ""

    if payload is not None:
        product_name = str(payload.get("product_name") or "")
        raw_category = str(payload.get("category") or "").strip()
        subcategory = str(payload.get("subcategory") or "").strip()
        features = payload.get("features")
        if isinstance(features, dict):
            fit_val = features.get("핏")
            if isinstance(fit_val, list) and fit_val:
                fit_type = str(fit_val[0]).strip() or "레귤러"
            elif isinstance(fit_val, str) and fit_val.strip():
                fit_type = fit_val.strip()
        elif payload.get("fit_type"):
            fit_type = str(payload.get("fit_type")).strip()

        qdrant_sizes = payload.get("sizes")
        if isinstance(qdrant_sizes, list):
            for s in qdrant_sizes:
                if isinstance(s, dict):
                    raw_sizes.append(s)

    if not raw_sizes:
        settings = _get_settings_from_app(app)
        product_client: ProductServiceClient = getattr(
            app.state, "product_client", None
        ) or ProductServiceClient(settings)
        if hasattr(product_client, "get_product_measurements"):
            measurements_map = product_client.get_product_measurements(
                [product_id],
                request_id_header=request_id_header,
            )
            if product_id in measurements_map:
                seen_size_labels: set[str] = set()
                for opt in measurements_map.get(product_id) or []:
                    if not isinstance(opt, dict):
                        continue
                    size_label = str(opt.get("size") or "FREE").strip()
                    if size_label in seen_size_labels:
                        continue
                    seen_size_labels.add(size_label)
                    raw_sizes.append(
                        {
                            "size": size_label,
                            "measurements": opt.get("measurements") or {},
                        }
                    )

    if payload is None and not raw_sizes:
        return None

    category_type = (
        CATEGORY_KO_TO_EN.get(raw_category)
        or CATEGORY_KO_TO_EN.get(raw_category.lower())
        or _infer_category_from_measurements(raw_sizes)
    )
    resolved_subcategory = subcategory or category_type

    return {
        "product_id": product_id,
        "product_name": product_name,
        "category_type": category_type,
        "subcategory": resolved_subcategory,
        "fit_type": fit_type,
        "sizes": raw_sizes,
    }


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


def _extract_sizes_list(item: dict[str, Any]) -> list[str] | None:
    raw_sizes = item.get("sizes")
    if not isinstance(raw_sizes, list) or not raw_sizes:
        return None

    extracted: list[str] = []
    for entry in raw_sizes:
        if isinstance(entry, dict):
            size_val = entry.get("size")
            if size_val is not None and str(size_val).strip():
                extracted.append(str(size_val).strip())
        elif isinstance(entry, str) and entry.strip():
            extracted.append(entry.strip())

    return extracted if extracted else None


def _map_product_item(item: dict[str, Any]) -> dict[str, Any]:
    size_rec = item.get("size_recommendation") or {}
    source_type = str(item.get("fit_source_type") or "none")

    recommended_size = (
        size_rec.get("size_label")
        or item.get("recommendedSize")
        or item.get("size")
    )
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
        recommendedSize=recommended_size,
        availableSizes=_extract_sizes_list(item),
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
        is_cache_hit = cached_result is not None
        prompt_tokens = 0 if is_cache_hit else int(pipeline_result.get("prompt_tokens") or 0)
        completion_tokens = 0 if is_cache_hit else int(pipeline_result.get("completion_tokens") or 0)

        if result_type == "single":
            raw_items = pipeline_result.get("results") or []
            mapped_items = [_map_product_item(item) for item in raw_items[:9]]
            repository.complete_single_request(
                request_id=request_id,
                tpo_summary=tpo_summary,
                items=mapped_items,
                message=comment,
                prompt_tokens=prompt_tokens,
                completion_tokens=completion_tokens,
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
                prompt_tokens=prompt_tokens,
                completion_tokens=completion_tokens,
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


@public_router.get(
    "/personalized",
    response_model=PersonalizedPageResponse,
    status_code=status.HTTP_200_OK,
    summary="홈 화면 개인화 추천 상품 피드 조회 (무한 스크롤)",
    description=(
        "사용자의 행동 이력(이상형 월드컵 1등, 찜/장바구니, 최근 구매 5건)을 바탕으로 "
        "취향 벡터를 합성하여 5대 카테고리(상의, 하의, 아우터, 신발, 모자) 상품을 라운드 로빈 교차 인터리빙한 "
        "개인화 피드를 제공합니다.\n\n"
        "- 최근 구매 완료한 상품은 피드에서 제외됩니다.\n"
        "- 비로그인(게스트) 또는 이력이 없는 유저는 호불호 없는 베이직 룩 추천으로 자동 폴백(Cold-Start)됩니다.\n"
        "- 5분(300초)간 Redis에 캐싱되며, refresh=true 쿼리로 즉시 강제 갱신할 수 있습니다.\n"
        "- 한 번에 size(기본 20개)씩 최대 4페이지(총 80개)까지 페이징 제공됩니다."
    ),
)
async def get_personalized_recommendation_feed(
    request: Request,
    page: int = Query(default=0, ge=0, description="페이지 번호 (0부터 시작)"),
    size: int = Query(default=20, gt=0, le=50, description="페이지당 상품 개수 (기본 20, 최대 50)"),
    refresh: bool = Query(default=False, description="캐시 무효화 및 강제 새로고침 여부"),
    x_user_id: Optional[str] = Header(default=None, alias="X-User-Id"),
) -> PersonalizedPageResponse:
    parsed_user_id = 0
    if x_user_id is not None and str(x_user_id).strip():
        try:
            uid = int(str(x_user_id).strip())
            if uid > 0:
                parsed_user_id = uid
        except (ValueError, TypeError):
            parsed_user_id = 0

    feed_service = await run_in_threadpool(
        _get_personalized_feed_service,
        request.app,
    )
    cache_service = _get_cache_service(request.app)

    return await feed_service.get_personalized_feed_page(
        user_id=parsed_user_id,
        page=page,
        size=size,
        refresh=refresh,
        cache_service=cache_service,
    )




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
        size=page_data.get("size", size),
        totalElements=page_data.get("totalElements", 0),
        totalPages=page_data["totalPages"],
        hasNext=page_data.get("hasNext", False),
    )


@public_router.get(
    "/analytics",
    response_model=AnalyticsResponse,
    status_code=status.HTTP_200_OK,
    summary="관리자 대시보드 통계 집계 조회",
    description="현재 날짜(KST)를 기준으로 일간/주간/월간/연간 토큰 사용량 추이, 인기 스타일 키워드, 추천 상품 통계를 집계하여 반환합니다.",
)
def get_recommendation_analytics(
    request: Request,
    x_user_id: str | None = Header(default=None, alias="X-User-Id"),
    x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
) -> AnalyticsResponse:
    settings = _get_settings(request)
    _require_admin(settings, x_user_id=x_user_id, x_admin_token=x_admin_token)
    repository = _get_repository(request)
    vton_client = _get_vton_client(request)
    data = repository.get_analytics_data(vton_client=vton_client)
    return AnalyticsResponse(**data)


@public_router.get(
    "/{productId}/size",
    response_model=ProductSizeRecommendationResponse,
    status_code=status.HTTP_200_OK,
    summary="상품 상세페이지 단일 상품 맞춤 사이즈 추천 조회",
    description=(
        "상품 상세페이지의 사이즈 표 상단에 표시할 사용자 맞춤 추천 사이즈와 추천 근거를 반환합니다.\n\n"
        "- 우선순위 체인: 1순위 설문 응답 학습(`learned_preference`) → 2순위 최근 구매 이력(`recent_purchase`) "
        "→ 3순위 신체 프로필/유사 체형(`body_profile` / `similar_body_profile`)\n"
        "- 신체 정보가 없거나 단일 사이즈(FREE) 상품인 경우에도 200 OK와 함께 `recommendedSize: null` 및 안내 문구(`evidence`)를 반환합니다."
    ),
)
def get_product_size_recommendation(
    productId: int,
    request: Request,
    x_user_id: str | None = Header(default=None, alias="X-User-Id"),
    x_request_id: str | None = Header(default=None, alias="X-Request-Id"),
) -> ProductSizeRecommendationResponse:
    user_id = _require_user_id(x_user_id)
    if productId <= 0:
        raise RecommendationApiError(
            status_code=status.HTTP_400_BAD_REQUEST,
            code="INVALID_REQUEST",
            message="유효하지 않은 상품 ID입니다.",
        )

    product_data = _resolve_product_for_size(
        app=request.app,
        product_id=productId,
        request_id_header=x_request_id,
    )
    if product_data is None:
        raise RecommendationApiError(
            status_code=status.HTTP_404_NOT_FOUND,
            code="PRODUCT_NOT_FOUND",
            message="상품을 찾을 수 없습니다.",
        )

    available_sizes = _extract_sizes_list(product_data)
    raw_sizes = product_data.get("sizes") or []

    if not raw_sizes:
        return ProductSizeRecommendationResponse(
            recommendedSize=None,
            fitPercent=None,
            evidence="해당 상품의 상세 실측 정보가 없어 사이즈 추천을 제공할 수 없습니다.",
        )

    if (
        available_sizes
        and len(available_sizes) == 1
        and available_sizes[0].strip().upper() in {"FREE", "ONE SIZE", "ONESIZE"}
    ):
        return ProductSizeRecommendationResponse(
            recommendedSize=None,
            fitPercent=None,
            evidence="단일 사이즈(FREE)로 제공되는 상품입니다.",
        )

    settings = _get_settings(request)
    repository = _get_repository(request)
    user_client: UserServiceClient = getattr(
        request.app.state, "user_client", None
    ) or UserServiceClient(settings)
    size_resolver = _get_size_resolver(request.app)

    user_body = user_client.get_user_body(
        user_id=user_id,
        request_id_header=x_request_id,
    )
    _sync_user_body(
        size_resolver=size_resolver,
        user_id=str(user_id),
        user_body=user_body,
    )

    subcategory = str(product_data.get("subcategory") or "")
    fit_type = str(product_data.get("fit_type") or "레귤러")
    category_type = str(product_data.get("category_type") or "top")

    db_pref = repository.get_user_size_preference(
        user_id=user_id,
        subcategory=subcategory,
    )
    _sync_user_size_preference(
        size_resolver=size_resolver,
        user_id=str(user_id),
        subcategory=subcategory,
        fit_type=fit_type,
        pref_spec=db_pref,
    )

    size_result = size_resolver.resolve_and_calculate_size(
        user_id=str(user_id),
        subcategory=subcategory,
        fit_type=fit_type,
        candidate_products=[product_data],
        category_type=category_type,
    )

    source_type = str(size_result.get("source_type") or "none")
    baseline_spec = size_result.get("baseline_spec")
    recommendation = size_result.get("recommendation")

    if source_type == "none" or baseline_spec is None:
        return ProductSizeRecommendationResponse(
            recommendedSize=None,
            fitPercent=None,
            evidence="체형 정보를 등록하시면 맞춤 사이즈를 추천해 드립니다.",
        )

    if not recommendation or not recommendation.get("size_label"):
        return ProductSizeRecommendationResponse(
            recommendedSize=None,
            fitPercent=None,
            evidence="해당 상품의 상세 실측 정보가 없어 사이즈 추천을 제공할 수 없습니다.",
        )

    raw_fit_score = float(recommendation.get("predicted_fit_score") or 0.0)
    fit_percent = round(
        raw_fit_score * 100.0 if raw_fit_score <= 1.0 else raw_fit_score,
        1,
    )

    return ProductSizeRecommendationResponse(
        recommendedSize=str(recommendation["size_label"]),
        fitPercent=fit_percent,
        evidence=FIT_SOURCE_EVIDENCE_MAP.get(source_type),
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
        queryText=str(record.get("queryText") or ""),
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

    qdrant_payload = _get_qdrant_product_payload(request.app, payload.productId)
    subcategory: str | None = None
    fit_type: str | None = None
    if qdrant_payload is not None:
        raw_sub = str(qdrant_payload.get("subcategory") or "").strip()
        raw_cat = str(qdrant_payload.get("category") or "").strip()
        subcategory = raw_sub or CATEGORY_KO_TO_EN.get(raw_cat) or None
        features = qdrant_payload.get("features")
        if isinstance(features, dict):
            fit_val = features.get("핏")
            if isinstance(fit_val, list) and fit_val:
                fit_type = str(fit_val[0]).strip()
            elif isinstance(fit_val, str) and fit_val.strip():
                fit_type = fit_val.strip()
    elif purchased_spec:
        subcategory = _infer_category_from_measurements(
            [{"size": "FREE", "measurements": purchased_spec}]
        )

    repository.save_size_feedback(
        user_id=payload.userId,
        product_id=payload.productId,
        option_id=payload.optionId,
        size_feedback=payload.sizeFeedback,
        subcategory=subcategory,
        fit_type=fit_type,
        purchased_spec=purchased_spec,
    )

    if subcategory:
        updated_pref = repository.get_user_size_preference(
            user_id=payload.userId,
            subcategory=subcategory,
        )
        size_resolver = getattr(request.app.state, "size_resolver", None) or getattr(
            getattr(request.app.state, "coordinator", None), "size_resolver", None
        )
        _sync_user_size_preference(
            size_resolver=size_resolver,
            user_id=str(payload.userId),
            subcategory=subcategory,
            fit_type=fit_type or "레귤러",
            pref_spec=updated_pref,
        )

    cache_service = _get_cache_service(request.app)
    cache_service.invalidate_user_pipeline(user_id=str(payload.userId))

    return Response(status_code=status.HTTP_202_ACCEPTED)


