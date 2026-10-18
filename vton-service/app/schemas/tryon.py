"""vton-service 요청 및 응답 Pydantic 스키마."""

from __future__ import annotations

from typing import Annotated, Any, Literal
from pydantic import BaseModel, Field


class FittingCreateRequest(BaseModel):
    photoId: int | None = Field(
        default=None,
        description="사용자 전신 사진 ID (현재 미구현 시 기본 아바타로 피팅 진행)",
        json_schema_extra={"example": 1},
    )
    productIds: list[int] = Field(
        ...,
        min_length=1,
        max_length=5,
        description="피팅할 상품 ID 목록 (최소 1개, 최대 5개)",
        json_schema_extra={"example": [101, 201]},
    )


class FittingCreateResponse(BaseModel):
    fittingId: int = Field(
        description="생성된 가상 피팅 작업 ID",
        json_schema_extra={"example": 1},
    )
    status: Literal["PENDING"] = Field(
        default="PENDING",
        description="작업 상태 (비동기 처리 시작)",
        json_schema_extra={"example": "PENDING"},
    )


class FittingProductResponse(BaseModel):
    productId: int = Field(
        description="상품 ID",
        json_schema_extra={"example": 101},
    )
    productName: str = Field(
        description="상품명",
        json_schema_extra={"example": "베이직 오버핏 반팔 티셔츠"},
    )
    price: int = Field(
        description="상품 가격(원)",
        json_schema_extra={"example": 29000},
    )
    imageUrl: str = Field(
        description="상품 썸네일 이미지 URL",
        json_schema_extra={"example": "https://cdn.example.com/item.png"},
    )
    categoryName: str = Field(
        description="의류 카테고리 (상의, 하의, 아우터, 신발, 모자)",
        json_schema_extra={"example": "상의"},
    )


class FittingDetailResponse(BaseModel):
    fittingId: int = Field(
        description="가상 피팅 작업 ID",
        json_schema_extra={"example": 1},
    )
    userId: int = Field(
        description="피팅 요청 사용자 ID",
        json_schema_extra={"example": 7},
    )
    photoId: int | None = Field(
        default=None,
        description="사용자 전신 사진 ID",
    )
    status: Literal["PENDING", "COMPLETED", "FAILED"] = Field(
        description="피팅 진행 상태 (PENDING, COMPLETED, FAILED)",
        json_schema_extra={"example": "COMPLETED"},
    )
    resultImageUrl: str | None = Field(
        default=None,
        description="피팅 완성 이미지 URL (COMPLETED 상태일 때 유효)",
        json_schema_extra={"example": "http://api.fitzza.com/api/vton/1/image"},
    )
    errorMessage: str | None = Field(
        default=None,
        description="피팅 실패 시 상세 에러 메시지",
    )
    products: list[FittingProductResponse] = Field(
        default_factory=list,
        description="피팅에 사용된 상품 목록",
    )
    createdAt: str = Field(
        description="생성 일시 (ISO-8601)",
        json_schema_extra={"example": "2026-10-18T17:30:00+00:00"},
    )
    updatedAt: str = Field(
        description="수정 일시 (ISO-8601)",
        json_schema_extra={"example": "2026-10-18T17:31:00+00:00"},
    )


class InternalResultProduct(BaseModel):
    productId: int = Field(
        description="상품 ID",
        json_schema_extra={"example": 1},
    )
    productName: str = Field(
        description="상품명",
        json_schema_extra={"example": "셔츠"},
    )
    price: int = Field(
        description="가격",
        json_schema_extra={"example": 39000},
    )
    imageUrl: str = Field(
        description="상품 이미지 URL",
        json_schema_extra={"example": "https://cdn.example.com/1.png"},
    )


class InternalFittingResponse(BaseModel):
    owned: bool = Field(
        description="요청한 사용자의 피팅인지 여부",
        json_schema_extra={"example": True},
    )
    status: str = Field(
        description="피팅 상태 (COMPLETED, PENDING, FAILED)",
        json_schema_extra={"example": "COMPLETED"},
    )
    resultImageUrl: str | None = Field(
        default=None,
        description="완성된 이미지 URL (status가 COMPLETED일 때만 유효)",
        json_schema_extra={"example": "http://api.fitzza.com/api/vton/1/image"},
    )
    products: list[InternalResultProduct] = Field(
        default_factory=list,
        description="피팅에 사용된 상품 정보 목록 (community-service 호환용)",
    )

