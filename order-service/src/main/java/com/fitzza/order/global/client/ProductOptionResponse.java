package com.fitzza.order.global.client;

public record ProductOptionResponse(
        Long optionId,
        Long productId,
        String brandName,
        String productName,
        String color,
        String size,
        Integer price,
        String imageUrl,
        String status) {

    private static final String ON_SALE_STATUS = "ON_SALE";

    public boolean onSale() {
        return ON_SALE_STATUS.equals(status);
    }
}
