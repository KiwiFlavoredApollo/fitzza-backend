from typing import Literal, Optional

from pydantic import BaseModel, Field


class RecommendationCreateRequest(BaseModel):
    query: str = Field(..., description="사용자의 자연어 패션 추천 질의")


class RecommendationAcceptedResponse(BaseModel):
    requestId: int = Field(..., description="생성된 추천 요청 ID")
    status: Literal["PROCESSING", "DONE", "FAILED"] = Field(
        default="PROCESSING",
        description="추천 요청 처리 상태",
    )


class RecommendedProductItem(BaseModel):
    productId: int
    productName: str
    brandName: Optional[str] = None
    category: Optional[str] = None
    subcategory: Optional[str] = None
    price: int
    imageUrl: Optional[str] = None
    color: Optional[str] = None
    size: Optional[str] = None
    fitPercent: Optional[float] = None
    evidence: Optional[str] = None


class RecommendedComboItem(BaseModel):
    comboId: int
    items: list[RecommendedProductItem] = Field(default_factory=list)
    totalPrice: int
    passStatus: str
    harmonyScore: float
    feedbackSummary: str
    budgetExceeded: bool = False


class RecommendationDetailResponse(BaseModel):
    requestId: int
    status: Literal["PROCESSING", "DONE", "FAILED"]
    searchType: Optional[str] = None
    tpoSummary: Optional[str] = None
    items: list[RecommendedProductItem] = Field(default_factory=list)
    combos: list[RecommendedComboItem] = Field(default_factory=list)
    message: Optional[str] = None


class RecommendationHistoryItem(BaseModel):
    requestId: int
    queryText: str
    tpoSummary: Optional[str] = None
    createdAt: str


class RecommendationHistoryPageResponse(BaseModel):
    content: list[RecommendationHistoryItem] = Field(default_factory=list)
    page: int
    totalPages: int


class InternalComboProductItem(BaseModel):
    productId: int
    productName: str
    price: int
    imageUrl: Optional[str] = None


class InternalComboResponse(BaseModel):
    owned: bool
    items: list[InternalComboProductItem] = Field(default_factory=list)
    totalPrice: Optional[int] = None
    feedbackSummary: Optional[str] = None


class SizeFeedbackRequest(BaseModel):
    userId: int = Field(..., gt=0)
    productId: int = Field(..., gt=0)
    optionId: int = Field(..., gt=0)
    sizeFeedback: Literal["SMALL", "FIT", "BIG"]


class VisualSearchResultItem(BaseModel):
    productId: int = Field(..., description="상품 고유 식별자")
    productName: str = Field(..., description="상품명")
    brandName: Optional[str] = Field(None, description="브랜드명")
    category: Optional[str] = Field(None, description="대분류 카테고리 (상의, 아우터, 하의 등)")
    subcategory: Optional[str] = Field(None, description="중분류/소분류 카테고리")
    price: int = Field(..., description="상품 가격 (원)")
    imageUrl: Optional[str] = Field(None, description="상품 대표 이미지 URL")
    color: Optional[str] = Field(None, description="대표 색상")
    similarityScore: float = Field(..., description="Fashion-CLIP 시각적 유사도 점수 (0.0 ~ 1.0)")
    productUrl: Optional[str] = Field(None, description="상품 상세 페이지 URL")


class VisualSearchResponse(BaseModel):
    detectedCategory: Optional[str] = Field(None, description="AI가 이미지에서 자동 감지한 상위 카테고리 (상의, 아우터, 하의, 신발, 모자)")
    detectedColor: Optional[str] = Field(None, description="AI가 이미지에서 자동 감지한 대표 색상 (black, white, navy 등)")
    items: list[VisualSearchResultItem] = Field(default_factory=list, description="유사도 순 정렬된 상품 목록")
    totalCount: int = Field(..., description="검색된 유사 상품 총 개수")
