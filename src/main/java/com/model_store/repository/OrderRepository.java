package com.model_store.repository;

import com.model_store.model.base.Order;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface OrderRepository extends ReactiveCrudRepository<Order, Long> {

    Flux<Order> findBySellerId(Long sellerId);

    Flux<Order> findByCustomerId(Long customerId);

    @Query("SELECT COUNT(*) FROM \"order\" WHERE seller_id = :sellerId AND status = 'COMPLETED'")
    Mono<Integer> findCompletedCountBySellerId(Long sellerId);

    @Query("SELECT COUNT(*) FROM \"order\" WHERE customer_id = :customerId AND status = 'COMPLETED'")
    Mono<Integer> findCompletedCountByCustomerId(Long customerId);
}

