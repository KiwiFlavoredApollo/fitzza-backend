from typing import Literal
from uuid import UUID, uuid4

from fastapi import FastAPI, Request, Response, status
from pydantic import BaseModel, HttpUrl

app = FastAPI(title="Fitzza Virtual Try-On Service", version="0.1.0")
app.state.ready = True


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


@app.post("/api/v1/tryon/jobs", response_model=TryOnJob, status_code=status.HTTP_202_ACCEPTED)
def create_job(_: TryOnRequest) -> TryOnJob:
    """Queue contract placeholder; connect SQS and the image worker next."""
    return TryOnJob(job_id=uuid4())
