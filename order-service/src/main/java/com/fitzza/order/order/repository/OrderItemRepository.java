package com.fitzza.order.order.repository;

import com.fitzza.order.order.entity.OrderItemEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItemEntity, Long> {

    @Query("""
            select i.order.orderId as orderId, count(i) as itemCount
            from OrderItemEntity i
            where i.order.orderId in :orderIds
            group by i.order.orderId
            """)
    List<OrderItemCountProjection> countByOrderIds(@Param("orderIds") Collection<Long> orderIds);
}
