package com.model_store.repository;

import com.model_store.model.base.Order;
import com.model_store.model.constant.OrderStatus;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Repository
public interface OrderRepository extends ReactiveCrudRepository<Order, Long> {

    @Modifying
    @Query("""
            UPDATE "order" SET status = CAST(:nextStatus AS order_status), comment = :comment
            WHERE id = :orderId AND status = CAST(:expectedStatus AS order_status)
            """)
    Mono<Integer> transition(Long orderId, String expectedStatus, String nextStatus, String comment);

    @Modifying
    @Query("""
            UPDATE "order" SET status = CAST(:nextStatus AS order_status),
                comment = :comment, image_payment_proof_id = :imageId
            WHERE id = :orderId AND status = CAST(:expectedStatus AS order_status)
            """)
    Mono<Integer> transitionWithProof(Long orderId, String expectedStatus, String nextStatus,
                                      String comment, Long imageId);

    @Modifying
    @Query("""
            UPDATE "order" SET image_payment_proof_id = :imageId
            WHERE id = :orderId AND status = 'DISPUTED'
              AND image_payment_proof_id = :previousProofId
            """)
    Mono<Integer> recordDisputedPayment(Long orderId, Long previousProofId, Long imageId);

    @Modifying
    @Query("""
            UPDATE "order" SET status = CAST(:nextStatus AS order_status),
                comment = :comment, delivery_url = :deliveryUrl
            WHERE id = :orderId AND status = CAST(:expectedStatus AS order_status)
            """)
    Mono<Integer> transitionWithDelivery(Long orderId, String expectedStatus, String nextStatus,
                                         String comment, String deliveryUrl);

    @Query("SELECT * FROM \"order\" WHERE id = :orderId FOR UPDATE")
    Mono<Order> findByIdForUpdate(Long orderId);

    @Query("SELECT EXISTS (SELECT 1 FROM \"order\" WHERE product_id = :productId AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED'))")
    Mono<Boolean> existsActiveOrderForProduct(Long productId);

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

