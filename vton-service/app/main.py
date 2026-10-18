"""Fitzza 가상 피팅(VTON) 서비스 메인 애플리케이션."""

from __future__ import annotations

from typing import Annotated, Literal
from uuid import UUID, uuid4

from fastapi import FastAPI, File, Form, HTTPException, Request, Response, UploadFile, status
from fastapi.openapi.utils import get_openapi
from pydantic import BaseModel, HttpUrl

from app.services.vton_generator import (
    DEFAULT_MODEL,
    VtonConfigurationError,
    VtonError,
    VtonUpstreamError,
    VtonValidationError,
    generate_tryon_image,
)

app = FastAPI(
    title="Fitzza Virtual Try-On Service",
    description="Fitzza AI 가상 피팅 서비스 (OpenAI 기반 다중 의류 가상 착용)",
    version="0.2.0",
)
app.state.ready = True


def custom_openapi():
    """Swagger UI에서 다중 이미지 파일을 업로드할 수 있도록 바이너리 포맷 스키마를 설정합니다."""
    if app.openapi_schema:
        return app.openapi_schema

    schema = get_openapi(
        title=app.title,
        version=app.version,
        description=app.description,
        routes=app.routes,
    )

    components = schema.get("components", {}).get("schemas", {})
    for component in components.values():
        properties = component.get("properties", {})
        for prop in properties.values():
            if prop.get("type") == "array":
                items = prop.get("items", {})
                if items.get("contentMediaType") == "application/octet-stream":
                    items.pop("contentMediaType", None)
                    items["format"] = "binary"
            elif prop.get("contentMediaType") == "application/octet-stream":
                prop.pop("contentMediaType", None)
                prop["format"] = "binary"

    app.openapi_schema = schema
    return app.openapi_schema


app.openapi = custom_openapi


class TryOnRequest(BaseModel):
    user_id: UUID
    person_image_url: HttpUrl
    product_image_url: HttpUrl


class TryOnJob(BaseModel):
    job_id: UUID
    state: Literal["QUEUED"] = "QUEUED"


class HealthResponse(BaseModel):
    status: Literal["UP", "NOT_READY"]
    liveness: Literal["UP"] = "UP"
    readiness: Literal["UP", "DOWN"]


@app.get("/health", response_model=HealthResponse)
def health(request: Request, response: Response) -> HealthResponse:
    ready = getattr(request.app.state, "ready", False)
    if not ready:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return HealthResponse(
        status="UP" if ready else "NOT_READY",
        readiness="UP" if ready else "DOWN",
    )


@app.post("/api/vton/jobs", response_model=TryOnJob, status_code=status.HTTP_202_ACCEPTED)
def create_job(_: TryOnRequest) -> TryOnJob:
    """비동기 가상 피팅 작업 큐잉 (추후 SQS 및 DB 연동용)."""
    return TryOnJob(job_id=uuid4())


@app.post(
    "/api/vton/clothing-transfer",
    response_class=Response,
    summary="[Swagger 테스트용] 실시간 다중 의류 가상 피팅",
    description=(
        "기본 아바타 인물에 사용자가 직접 첨부한 의류 이미지들을 실시간으로 합성하여 "
        "완성된 PNG 이미지를 즉시 반환합니다."
    ),
    responses={
        200: {
            "content": {"image/png": {}},
            "description": "가상 피팅 완료 PNG 이미지",
        },
        400: {"description": "입력 카테고리 또는 이미지 유효성 오류"},
        500: {"description": "기본 아바타 또는 서버 설정 오류"},
        502: {"description": "OpenAI 이미지 생성 API 호출 실패"},
    },
)
async def clothing_transfer(
    categories_json: Annotated[
        str,
        Form(
            description=(
                '착용할 카테고리 목록. JSON 배열(예: ["top", "shoes"] 또는 ["상의", "신발"]) '
                '또는 쉼표 구분 문자열(예: top,shoes)'
            )
        ),
    ],
    clothing_images: Annotated[
        list[UploadFile],
        File(description="카테고리 순서와 동일한 순서로 첨부할 의류 상품 이미지 파일들"),
    ],
) -> Response:
    try:
        raw_images: list[bytes] = []
        for upload in clothing_images:
            content = await upload.read()
            raw_images.append(content)

        output_png = generate_tryon_image(
            categories=categories_json,
            clothing_images=raw_images,
        )

        return Response(
            content=output_png,
            media_type="image/png",
            headers={"Content-Disposition": 'inline; filename="vton-result.png"'},
        )
    except VtonValidationError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    except VtonConfigurationError as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(exc)
        ) from exc
    except VtonUpstreamError as exc:
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"가상 피팅 처리 중 예기치 않은 오류가 발생했습니다: {exc}",
        ) from exc
    finally:
        for upload in clothing_images:
            await upload.close()
