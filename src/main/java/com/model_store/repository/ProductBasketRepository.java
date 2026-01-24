package com.model_store.repository;

import com.model_store.model.base.ProductBasket;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProductBasketRepository extends ReactiveCrudRepository<ProductBasket, Long> {
    Flux<ProductBasket> findByParticipantId(Long participantId);

    Mono<ProductBasket> findByParticipantIdAndProductId(Long participantId, Long productId);

    Mono<Void> deleteByParticipantIdAndProductId(Long participantId, Long productId);

    @Query("UPDATE product_basket SET count = :qty WHERE participant_id = :participantId AND product_id = :productId")
    Mono<Integer> updateQty(Long participantId, Long productId, int qty);
}