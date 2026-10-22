-- 무신사 color_image_mappings를 상품 상세 API로 내려주기 위해 저장한다.
ALTER TABLE product_service.product
    ADD COLUMN color_image_mappings jsonb;
