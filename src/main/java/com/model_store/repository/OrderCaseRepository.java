package com.model_store.repository;

import com.model_store.model.base.OrderCase;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OrderCaseRepository extends ReactiveCrudRepository<OrderCase, Long> {
    @Query("SELECT * FROM order_case WHERE state = :state ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
    Flux<OrderCase> findQueue(String state, int limit, long offset);

    @Query("SELECT * FROM order_case WHERE order_id = :orderId ORDER BY created_at DESC, id DESC")
    Flux<OrderCase> findByOrderId(Long orderId);

    @Query("SELECT * FROM order_case WHERE order_id = :orderId AND state = 'OPEN'")
    Mono<OrderCase> findOpenByOrderId(Long orderId);

    @Modifying
    @Query("UPDATE order_case SET telegram_url = :url, version = version + 1 WHERE id = :caseId AND state = 'OPEN' AND version = :version")
    Mono<Integer> updateTelegramUrl(Long caseId, Long version, String url);

    @Modifying
    @Query("""
            UPDATE order_case SET state = 'RESOLVED', resolved_by = :adminId,
                outcome = :outcome, resolution_comment = :comment, resolved_at = now(), version = version + 1
            WHERE id = :caseId AND state = 'OPEN' AND version = :version
            """)
    Mono<Integer> resolve(Long caseId, Long version, Long adminId, String outcome, String comment);

    @Modifying
    @Query("""
            UPDATE order_case SET state = 'RESOLVED', outcome = 'REJECTED',
                resolution_comment = 'Получено подтверждение оплаты', resolved_at = now(), version = version + 1
            WHERE order_id = :orderId AND kind = 'CANCELLATION_REQUEST' AND state = 'OPEN'
            """)
    Mono<Integer> dismissCancellationAfterPayment(Long orderId);

    @Modifying
    @Query("""
            INSERT INTO order_case_image(case_id, image_id, uploaded_by)
            VALUES (:caseId, :imageId, :participantId)
            """)
    Mono<Integer> addEvidence(Long caseId, Long imageId, Long participantId);
}
