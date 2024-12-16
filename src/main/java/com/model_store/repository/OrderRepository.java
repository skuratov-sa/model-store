package com.model_store.repository;

import com.model_store.model.base.Order;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface OrderRepository extends ReactiveCrudRepository<Order, Long> {

    Mono<Void> deleteAllBySellerId(Long participantId);

    Mono<Void> deleteAllByCustomerId(Long participantId);
}

