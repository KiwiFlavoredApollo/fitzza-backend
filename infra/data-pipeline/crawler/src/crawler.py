from __future__ import annotations

import asyncio
import json
from datetime import datetime, timezone
from typing import Any

from bs4 import BeautifulSoup
from playwright.async_api import async_playwright

from src.musinsa_parser import normalize_musinsa_product
from src.musinsa_size import normalize_actual_size


def _extract_json_ld(soup: BeautifulSoup) -> list[Any]:
    results: list[Any] = []

    for tag in soup.find_all("script", attrs={"type": "application/ld+json"}):
        text = tag.string or tag.get_text(strip=True)
        if not text:
            continue

        try:
            results.append(json.loads(text))
        except json.JSONDecodeError:
            results.append({"_raw": text})

    return results


def _extract_meta(soup: BeautifulSoup) -> dict[str, str]:
    keys = [
        "og:title",
        "og:description",
        "og:image",
        "og:url",
        "product:price:amount",
        "product:price:currency",
    ]

    result: dict[str, str] = {}

    for key in keys:
        tag = soup.find("meta", attrs={"property": key})
        if tag and tag.get("content"):
            result[key] = tag["content"].strip()

    description = soup.find("meta", attrs={"name": "description"})
    if description and description.get("content"):
        result["description"] = description["content"].strip()

    return result


async def _open_size_section(page) -> None:
    """
    디버깅/검증용.
    실제 실측값 수집은 이제 actual-size API를 직접 사용하므로
    이 클릭에 의존하지 않는다.
    """
    try:
        locator = page.get_by_text("사이즈", exact=True)
        count = await locator.count()

        for i in range(min(count, 5)):
            target = locator.nth(i)
            if await target.is_visible():
                await target.scroll_into_view_if_needed()
                await target.click(timeout=3000)
                await page.wait_for_timeout(1000)
                return
    except Exception:
        pass


async def _fetch_actual_size(page, product_id: int) -> tuple[dict[str, Any] | None, dict[str, Any]]:
    """
    브라우저 컨텍스트의 request client를 사용해
    무신사 actual-size API를 직접 호출한다.
    """
    api_url = f"https://goods-detail.musinsa.com/api2/goods/{product_id}/actual-size"

    response = await page.context.request.get(
        api_url,
        headers={
            "Referer": f"https://www.musinsa.com/products/{product_id}",
        },
        timeout=30_000,
    )

    record: dict[str, Any] = {
        "url": api_url,
        "status": response.status,
        "data": None,
    }

    if not response.ok:
        return None, record

    try:
        payload = await response.json()
    except Exception:
        return None, record

    record["data"] = payload

    if not isinstance(payload, dict):
        return None, record

    return payload.get("data"), record


async def crawl_product(
    url: str,
    headless: bool = True,
) -> tuple[dict[str, Any], dict[str, Any], str, list[dict[str, Any]], str]:
    network_json: list[dict[str, Any]] = []

    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=headless)
        page = await browser.new_page(viewport={"width": 1600, "height": 1000})

        try:
            response = await page.goto(
                url,
                wait_until="domcontentloaded",
                timeout=60_000,
            )
            await page.wait_for_timeout(1500)

            # 기본 페이지 HTML에서 상품 정보 정규화
            html = await page.content()
            normalized = normalize_musinsa_product(html, url)

            product_id = normalized.get("product_id")
            if not product_id:
                raise ValueError("상품 ID를 찾지 못했습니다.")

            # 핵심: 실측 사이즈 API 직접 호출
            actual_size_raw, actual_size_network = await _fetch_actual_size(
                page,
                int(product_id),
            )
            network_json.append(actual_size_network)

            # product.json에 실측 데이터 병합
            normalized["size"] = normalize_actual_size(actual_size_raw)

            # 화면 검증용으로 사이즈 탭도 한 번 열어둠
            await _open_size_section(page)
            await page.wait_for_timeout(700)

            rendered_html = await page.content()
            body_text = await page.locator("body").inner_text()
            soup = BeautifulSoup(rendered_html, "html.parser")

            raw_result: dict[str, Any] = {
                "source_url": url,
                "http_status": response.status if response else None,
                "crawled_at": datetime.now(timezone.utc).isoformat(),
                "page_title": await page.title(),
                "meta": _extract_meta(soup),
                "json_ld": _extract_json_ld(soup),
            }

            return (
                raw_result,
                normalized,
                rendered_html,
                network_json,
                body_text,
            )

        finally:
            await browser.close()
