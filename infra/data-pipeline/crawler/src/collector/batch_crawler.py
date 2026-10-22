from __future__ import annotations

import asyncio
from typing import Any

from playwright.async_api import Browser, Page, Playwright

from src.musinsa_essential import ESSENTIAL_API_URL, apply_essential_material
from src.musinsa_parser import normalize_musinsa_product
from src.musinsa_size import normalize_actual_size


class BatchProductCrawler:
    def __init__(
        self,
        playwright: Playwright,
        *,
        headless: bool = True,
        delay_seconds: float = 1.0,
    ) -> None:
        self.playwright = playwright
        self.headless = headless
        self.delay_seconds = delay_seconds
        self.browser: Browser | None = None
        self.page: Page | None = None

    async def start(self) -> None:
        self.browser = await self.playwright.chromium.launch(
            headless=self.headless
        )
        self.page = await self.browser.new_page(
            viewport={"width": 1440, "height": 900}
        )

    async def close(self) -> None:
        if self.browser:
            await self.browser.close()

    async def fetch_basic_product(self, product_id: int) -> dict[str, Any]:
        if not self.page:
            raise RuntimeError("crawler가 start()되지 않았습니다.")

        url = f"https://www.musinsa.com/products/{product_id}"
        response = await self.page.goto(
            url,
            wait_until="domcontentloaded",
            timeout=60_000,
        )

        if response is None:
            raise RuntimeError(f"응답 없음: {url}")
        if response.status >= 400:
            raise RuntimeError(
                f"상품 페이지 HTTP {response.status}: {url}"
            )

        await self.page.wait_for_timeout(600)
        html = await self.page.content()

        product = normalize_musinsa_product(html, url)
        if product["product_id"] != product_id:
            raise ValueError("Requested product identity mismatch")

        await asyncio.sleep(self.delay_seconds)
        return product

    async def fetch_actual_size(
        self,
        product_id: int,
        *,
        strict: bool = False,
    ) -> dict[str, Any]:
        if not self.page:
            raise RuntimeError("crawler가 start()되지 않았습니다.")

        api_url = (
            f"https://goods-detail.musinsa.com/api2/goods/"
            f"{product_id}/actual-size"
        )

        response = await self.page.context.request.get(
            api_url,
            headers={
                "Referer": f"https://www.musinsa.com/products/{product_id}",
                "Accept": "application/json",
            },
            timeout=30_000,
        )

        if not response.ok:
            if strict:
                raise RuntimeError(
                    f"actual-size API HTTP {response.status}: {api_url}"
                )
            await asyncio.sleep(self.delay_seconds)
            return normalize_actual_size(None)

        try:
            payload = await response.json()
        except Exception as exc:
            if strict:
                raise RuntimeError(
                    f"actual-size API JSON parse failed: {api_url}"
                ) from exc
            normalized = normalize_actual_size(None)
        else:
            actual_size = (
                payload.get("data")
                if isinstance(payload, dict)
                else None
            )
            normalized = normalize_actual_size(actual_size)

        await asyncio.sleep(self.delay_seconds)
        return normalized

    async def attach_actual_size(
        self,
        product: dict[str, Any],
        *,
        strict: bool = False,
    ) -> dict[str, Any]:
        product["size"] = await self.fetch_actual_size(
            int(product["product_id"]),
            strict=strict,
        )
        return product

    async def fetch_essential(
        self,
        product_id: int,
        *,
        strict: bool = False,
    ) -> dict[str, Any] | None:
        """상품정보제공고시(essential) API 원본 응답을 반환한다. 실패하면 None."""
        if not self.page:
            raise RuntimeError("crawler가 start()되지 않았습니다.")

        api_url = ESSENTIAL_API_URL.format(product_id=product_id)
        response = await self.page.context.request.get(
            api_url,
            headers={
                "Referer": f"https://www.musinsa.com/products/{product_id}",
                "Accept": "application/json",
            },
            timeout=30_000,
        )

        payload: dict[str, Any] | None = None
        if not response.ok:
            if strict:
                raise RuntimeError(
                    f"essential API HTTP {response.status}: {api_url}"
                )
        else:
            try:
                payload = await response.json()
            except Exception as exc:
                if strict:
                    raise RuntimeError(
                        f"essential API JSON parse failed: {api_url}"
                    ) from exc

        await asyncio.sleep(self.delay_seconds)
        return payload

    async def attach_essential_material(
        self,
        product: dict[str, Any],
        *,
        strict: bool = False,
    ) -> dict[str, Any]:
        """고시 소재를 붙인다. 소재가 없거나 "상세페이지 참조"면 추측하지 않고 null로 둔다."""
        payload = await self.fetch_essential(
            int(product["product_id"]),
            strict=strict,
        )
        return apply_essential_material(product, payload)
