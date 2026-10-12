package com.fitzza.product.product.repository;

import com.fitzza.product.product.entity.ProductOptionEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductOptionRepository extends JpaRepository<ProductOptionEntity, Long> {

    @Query("select o from ProductOptionEntity o where o.product.productId in :productIds order by o.optionId")
    List<ProductOptionEntity> findAllByProductIds(@Param("productIds") Collection<Long> productIds);

    @Query("""
            select o from ProductOptionEntity o
            join fetch o.product p
            join fetch p.brand
            where o.optionId in :optionIds
            order by o.optionId
            """)
    List<ProductOptionEntity> findAllWithProductByOptionIdIn(@Param("optionIds") Collection<Long> optionIds);
}
