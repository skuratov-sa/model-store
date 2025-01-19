package com.model_store.repository;

import com.model_store.model.base.OrderStatusHistory;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface OrderStatusHistoryRepository extends ReactiveCrudRepository<OrderStatusHistory, Long> {
    Flux<OrderStatusHistory> findByOrderIdOrderByChangedAt(Long orderId);
}