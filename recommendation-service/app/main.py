from typing import Literal
from uuid import UUID

from fastapi import FastAPI, Request, Response, status
from pydantic import BaseModel, Field

app = FastAPI(title="Fitzza Recommendation Service", version="0.1.0")
app.state.ready = True


class RecommendationRequest(BaseModel):
    user_id: UUID
    limit: int = Field(default=10, ge=1, le=100)


class RecommendationResponse(BaseModel):
    user_id: UUID
    product_ids: list[UUID]


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


@app.post("/api/v1/recommendations", response_model=RecommendationResponse)
def recommend(request: RecommendationRequest) -> RecommendationResponse:
    """Recommendation contract placeholder; connect Qdrant and model inference next."""
    return RecommendationResponse(user_id=request.user_id, product_ids=[])
