package com.model_store.repository;

import com.model_store.model.base.ProductBasket;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProductBasketRepository extends ReactiveCrudRepository<ProductBasket, Long> {
    Flux<ProductBasket> findByParticipantId(Long participantId);

    Flux<ProductBasket> findByParticipantIdAndProductId(Long participantId, Long productId);

    Mono<Void> deleteByParticipantIdAndProductId(Long participantId, Long productId);
}