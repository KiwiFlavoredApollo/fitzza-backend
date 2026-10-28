"""product-service HTTP 클라이언트."""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

import httpx

from app.core.config import Settings, get_settings
from app.services.vton_generator import (
    SUPPORTED_CATEGORIES,
    VtonValidationError,
    load_sample_clothing_png,
    to_png_bytes,
)

logger = logging.getLogger(__name__)

SERVICE_TOKEN_HEADER = "X-Service-Token"
REQUEST_ID_HEADER = "X-Request-Id"


class ProductServiceClient:
    def __init__(
        self,
        settings: Settings | None = None,
        storage_client: Any | None = None,
        http_client: httpx.Client | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self.storage_client = storage_client
        self._http_client = http_client

    def _get_storage(self) -> Any:
        if self.storage_client is None:
            try:
                from app.clients.storage_client import get_storage_client

                self.storage_client = get_storage_client()
            except Exception:
                self.storage_client = None
        return self.storage_client

    def _get_http_client(self) -> httpx.Client:
        """재사용 가능한 HTTP 커넥션 풀 클라이언트를 반환합니다."""
        if self._http_client is None or self._http_client.is_closed:
            self._http_client = httpx.Client(timeout=self.settings.http_timeout_seconds)
        return self._http_client

    def close(self) -> None:
        """소유한 HTTP 클라이언트 리소스를 안전하게 해제합니다."""
        if self._http_client is not None and not self._http_client.is_closed:
            self._http_client.close()

    @property
    def is_configured(self) -> bool:
        return bool(self.settings.product_service_url.strip())

    def _get_product_from_qdrant(self, product_id: int) -> dict[str, Any] | None:
        """Qdrant 벡터 DB에서 상품 메타데이터를 직접 조회합니다."""
        qdrant_url = (self.settings.qdrant_url or "").rstrip("/")
        collection_name = (self.settings.qdrant_collection_name or "").strip()
        if not qdrant_url or not collection_name:
            return None

        url = f"{qdrant_url}/collections/{collection_name}/points/scroll"
        body = {
            "filter": {
                "must": [
                    {
                        "key": "product_id",
                        "match": {"value": int(product_id)},
                    }
                ]
            },
            "limit": 1,
            "with_payload": True,
            "with_vector": False,
        }

        try:
            client = self._get_http_client()
            resp = client.post(url, json=body)
            if resp.status_code != 200:
                logger.debug("Qdrant 응답 상태 코드 (productId=%s): %d", product_id, resp.status_code)
                return None
            data = resp.json()
            points = (data.get("result") or {}).get("points") or []
            if not points:
                return None
            payload = points[0].get("payload") or {}
            logger.info(
                "Qdrant 메타데이터 조회 성공 (productId=%s, name=%s, category=%s)",
                product_id,
                payload.get("product_name"),
                payload.get("category"),
            )
            return {
                "productId": int(product_id),
                "productName": payload.get("product_name") or f"상품 #{product_id}",
                "normalPrice": int(payload.get("price") or 0),
                "price": int(payload.get("price") or 0),
                "thumbnailUrl": payload.get("image_key") or payload.get("image_url") or "",
                "categoryL1Name": payload.get("category") or "",
                "_source": "qdrant",
            }
        except Exception as exc:
            logger.warning("Qdrant 상품 조회 실패 (productId=%s): %s", product_id, exc)
            return None

    def get_product(
        self,
        product_id: int,
        request_id_header: str | None = None,
    ) -> dict[str, Any] | None:
        """Qdrant 벡터 DB(Single Source of Truth)에서 상품 정보 및 메타데이터를 직접 조회합니다."""
        return self._get_product_from_qdrant(product_id)

    def fetch_product_image_bytes(
        self,
        image_url: str,
    ) -> bytes | None:
        """원격 URL 또는 S3/로컬 스토리지에서 상품 이미지를 다운로드하여 PNG 바이트로 변환합니다."""
        if not image_url or not image_url.strip():
            return None

        clean_url = image_url.strip()

        # 1. S3 Object Key 형태이거나 S3 버킷 URL인 경우 StorageClient를 통해 직접 다운로드 시도
        storage = self.storage_client or self._get_storage()
        if storage and storage.is_s3:
            is_http = clean_url.startswith(("http://", "https://"))
            is_s3_target = not is_http or (
                storage.bucket_name in clean_url
                or (storage.endpoint_url and storage.endpoint_url in clean_url)
                or ".amazonaws.com" in clean_url
            )
            if is_s3_target:
                try:
                    raw_bytes = storage.download_bytes(clean_url)
                    if raw_bytes:
                        return to_png_bytes(raw_bytes)
                except Exception as exc:
                    logger.warning(
                        "S3 의류 이미지 다운로드 실패 (key_or_url=%s): %s. HTTP 다운로드로 폴백합니다.",
                        clean_url,
                        exc,
                    )

        # 2. HTTP(S) URL 다운로드 (외부 무신사 CDN 또는 fallback)
        if clean_url.startswith(("http://", "https://")):
            try:
                client = self._get_http_client()
                resp = client.get(clean_url)
                resp.raise_for_status()
                return to_png_bytes(resp.content)
            except Exception as exc:
                logger.warning(
                    "상품 이미지 HTTP 다운로드/변환 실패 (url=%s): %s: %s",
                    clean_url,
                    type(exc).__name__,
                    exc,
                )
                return None

        # 3. 로컬 파일 시스템 경로인 경우
        try:
            local_p = Path(clean_url)
            if local_p.is_file():
                return to_png_bytes(local_p.read_bytes())
        except Exception:
            pass

        return None

    def resolve_product_item(
        self,
        product_id: int,
        fallback_category: str | None = None,
        request_id_header: str | None = None,
        override_image_url: str | None = None,
    ) -> dict[str, Any]:
        """
        Qdrant 벡터 DB에서 상품 정보 및 이미지를 조회합니다.
        Qdrant에 상품이 존재하지 않거나 지원하지 않는 카테고리일 경우 VtonValidationError를 발생시킵니다.

        Args:
            product_id: 상품 ID
            fallback_category: 카테고리 폴백값 (기본 "상의")
            request_id_header: 추적용 Request ID
            override_image_url: 추천 서비스 등에서 전달받은 특정 색상의 상품 이미지 URL.

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
        if not product_info:
            raise VtonValidationError(f"존재하지 않거나 지원하지 않는 상품입니다: {product_id}")

        cat_l1 = str(product_info.get("categoryL1Name") or "").strip()
        if cat_l1 not in SUPPORTED_CATEGORIES:
            raise VtonValidationError(
                f"지원하지 않거나 유효하지 않은 상품 카테고리입니다 (productId={product_id}, category={cat_l1})"
            )
        category_name = cat_l1

        product_name = str(product_info.get("productName") or f"상품 #{product_id}")
        raw_price = product_info.get("normalPrice")
        if raw_price is None or raw_price == 0:
            raw_price = product_info.get("price")
        price = int(raw_price or 0)
        image_url = str(product_info.get("thumbnailUrl") or "")
        image_bytes: bytes | None = None

        # 1. 특정 색상 이미지 URL(override_image_url)이 제공된 경우 우선 다운로드
        if override_image_url and override_image_url.strip():
            downloaded = self.fetch_product_image_bytes(override_image_url.strip())
            if downloaded:
                image_bytes = downloaded
                image_url = override_image_url.strip()
                logger.info(
                    "추천 색상 의류 이미지 다운로드 적용 (productId=%s, url=%s)",
                    product_id,
                    image_url,
                )

        # 2. override_image_url이 없거나 다운로드 실패 시 product-service 기본 썸네일 다운로드
        if image_bytes is None and image_url:
            image_bytes = self.fetch_product_image_bytes(image_url)

        return {
            "productId": int(product_id),
            "productName": product_name,
            "price": price,
            "imageUrl": image_url,
            "categoryName": category_name,
            "imageBytes": image_bytes,
        }
