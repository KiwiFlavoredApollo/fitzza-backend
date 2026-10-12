package com.fitzza.product.product.repository;

import com.fitzza.product.global.exception.BusinessException;
import com.fitzza.product.global.exception.ErrorCode;
import com.fitzza.product.product.entity.ProductEntity;
import com.fitzza.product.product.entity.ProductStatus;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

public final class ProductSpecifications {

    private ProductSpecifications() {
    }

    public static Specification<ProductEntity> visibleInCategory(String category) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.notEqual(root.get("status"), ProductStatus.HIDDEN));
            if (!StringUtils.hasText(category)) {
                return builder.and(predicates.toArray(Predicate[]::new));
            }
            String code = category.trim();
            String field = switch (code.length()) {
                case 3 -> "category1";
                case 6 -> "category2";
                case 9 -> "subcategory";
                default -> throw new BusinessException(
                        ErrorCode.INVALID_REQUEST, "카테고리 코드 길이는 3, 6, 9여야 합니다.");
            };
            predicates.add(builder.equal(root.get(field), code));
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
