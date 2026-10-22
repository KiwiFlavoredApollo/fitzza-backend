"""product-service HTTP 클라이언트."""

from __future__ import annotations

import logging
from typing import Any

import httpx

from app.core.config import Settings, get_settings
from app.services.vton_generator import (
    SUPPORTED_CATEGORIES,
    load_sample_clothing_png,
    to_png_bytes,
)

logger = logging.getLogger(__name__)

SERVICE_TOKEN_HEADER = "X-Service-Token"
REQUEST_ID_HEADER = "X-Request-Id"


class ProductServiceClient:
    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()

    @property
    def is_configured(self) -> bool:
        return bool(self.settings.product_service_url.strip())

    def get_product(
        self,
        product_id: int,
        request_id_header: str | None = None,
    ) -> dict[str, Any] | None:
        """product-service에서 단일 상품 정보(GET /api/products/{productId})를 조회합니다."""
        if not self.is_configured:
            return None

        base_url = self.settings.product_service_url.rstrip("/")
        url = f"{base_url}/api/products/{product_id}"
        headers: dict[str, str] = {}
        if self.settings.vton_service_token.strip():
            headers[SERVICE_TOKEN_HEADER] = self.settings.vton_service_token
        if request_id_header:
            headers[REQUEST_ID_HEADER] = request_id_header

        try:
            with httpx.Client(timeout=self.settings.http_timeout_seconds) as client:
                response = client.get(url, headers=headers)
                if response.status_code == 404:
                    return None
                response.raise_for_status()
                payload = response.json()
                if isinstance(payload, dict):
                    return payload
                return None
        except Exception as exc:
            logger.warning(
                "product-service 상품 조회 실패 (productId=%s): %s: %s",
                product_id,
                type(exc).__name__,
                exc,
            )
            return None

    def fetch_product_image_bytes(
        self,
        image_url: str,
    ) -> bytes | None:
        """원격 URL에서 상품 이미지를 다운로드하여 PNG 바이트로 변환합니다."""
        if not image_url or not image_url.strip():
            return None

        clean_url = image_url.strip()
        if not (clean_url.startswith("http://") or clean_url.startswith("https://")):
            return None

        try:
            with httpx.Client(timeout=self.settings.http_timeout_seconds) as client:
                resp = client.get(clean_url)
                resp.raise_for_status()
                return to_png_bytes(resp.content)
        except Exception as exc:
            logger.warning(
                "상품 이미지 다운로드/변환 실패 (url=%s): %s: %s",
                clean_url,
                type(exc).__name__,
                exc,
            )
            return None

    def resolve_product_item(
        self,
        product_id: int,
        fallback_category: str | None = None,
        request_id_header: str | None = None,
    ) -> dict[str, Any]:
        """
        product-service에서 상품 정보 및 이미지를 조회하고,
        조회 실패 또는 이미지 다운로드 실패 시 로컬 샘플 에셋으로 자연스럽게 폴백합니다.

        반환 dict:
        {
            "productId": int,
            "productName": str,
            "price": int,
            "imageUrl": str,
            "categoryName": str,
            "imageBytes": bytes,
        }
        """
        product_info = self.get_product(product_id, request_id_header=request_id_header)

        product_name = ""
        price = 0
        image_url = ""
        category_name = fallback_category or "상의"
        image_bytes: bytes | None = None

        if product_info:
            product_name = str(product_info.get("productName") or f"상품 #{product_id}")
            price = int(product_info.get("price") or 0)
            image_url = str(product_info.get("thumbnailUrl") or "")
            cat_l1 = str(product_info.get("categoryL1Name") or "").strip()
            if cat_l1 in SUPPORTED_CATEGORIES:
                category_name = cat_l1

            if image_url:
                image_bytes = self.fetch_product_image_bytes(image_url)
        else:
            product_name = f"상품 #{product_id}"

        # 이미지 다운로드 실패 또는 미제공 시 카테고리별 샘플 의류 PNG로 폴백
        if image_bytes is None:
            safe_category = category_name if category_name in SUPPORTED_CATEGORIES else "상의"
            category_name = safe_category
            image_bytes = load_sample_clothing_png(safe_category)

        return {
            "productId": int(product_id),
            "productName": product_name,
            "price": price,
            "imageUrl": image_url,
            "categoryName": category_name,
            "imageBytes": image_bytes,
        }
