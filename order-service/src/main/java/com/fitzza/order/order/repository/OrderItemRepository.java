package com.fitzza.order.order.repository;

import com.fitzza.order.order.entity.OrderItemEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItemEntity, Long> {

    @EntityGraph(attributePaths = "order")
    Optional<OrderItemEntity> findWithOrderByOrderItemId(Long orderItemId);

    @Query("""
            select i.order.orderId as orderId, count(i) as itemCount
            from OrderItemEntity i
            where i.order.orderId in :orderIds
            group by i.order.orderId
            """)
    List<OrderItemCountProjection> countByOrderIds(@Param("orderIds") Collection<Long> orderIds);

    @Query("""
            select i.order.orderId as orderId, i.optionId as optionId
            from OrderItemEntity i
            where i.order.orderId in :orderIds
              and i.orderItemId = (
                  select min(first.orderItemId)
                  from OrderItemEntity first
                  where first.order.orderId = i.order.orderId
              )
            """)
    List<OrderFirstOptionProjection> findFirstOptionIdsByOrderIds(@Param("orderIds") Collection<Long> orderIds);
}
