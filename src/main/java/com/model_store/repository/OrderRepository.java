package com.model_store.repository;

import com.model_store.model.base.Order;
import com.model_store.model.constant.OrderStatus;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Repository
public interface OrderRepository extends ReactiveCrudRepository<Order, Long> {

    Flux<Order> findBySellerId(Long sellerId);

    @Query("""
            SELECT o.* FROM "order" o
            JOIN participant p ON p.id = o.seller_id
            WHERE p.is_agent = true
              AND (:agentId IS NULL OR o.seller_id = :agentId)
              AND (:status IS NULL OR o.status::text = :status)
            ORDER BY o.created_at DESC, o.id DESC
            LIMIT :limit OFFSET :offset
            """)
    Flux<Order> findAgentOrders(Long agentId, OrderStatus status, int limit, long offset);

    Flux<Order> findByCustomerId(Long customerId);

    @Query("SELECT COUNT(*) FROM \"order\" WHERE seller_id = :sellerId AND status = 'COMPLETED'")
    Mono<Integer> findCompletedCountBySellerId(Long sellerId);

    @Query("SELECT COUNT(*) FROM \"order\" WHERE customer_id = :customerId AND status = 'COMPLETED'")
    Mono<Integer> findCompletedCountByCustomerId(Long customerId);
}

