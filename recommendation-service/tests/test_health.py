from fastapi import Response
from starlette.requests import Request

from app.main import app, health


def health_request() -> Request:
    return Request({"type": "http", "app": app})


def test_health_returns_up_when_application_is_ready() -> None:
    app.state.ready = True
    response = Response()

    payload = health(health_request(), response)

    assert response.status_code == 200
    assert payload.model_dump() == {
        "status": "UP",
        "liveness": "UP",
        "readiness": "UP",
    }


def test_health_returns_service_unavailable_when_application_is_not_ready() -> None:
    app.state.ready = False
    response = Response()

    payload = health(health_request(), response)

    assert response.status_code == 503
    assert payload.model_dump() == {
        "status": "NOT_READY",
        "liveness": "UP",
        "readiness": "DOWN",
    }


def test_legacy_status_endpoint_is_not_exposed() -> None:
    assert "/api/v1/recommendations/status" not in {route.path for route in app.routes}

