package com.model_store.repository;

import com.model_store.model.base.ProductFavorite;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ProductFavoriteRepository extends ReactiveCrudRepository<ProductFavorite, Long> {
    Flux<ProductFavorite> findByParticipantId(Long participantId);

    Flux<ProductFavorite> findByParticipantIdAndProductId(Long participantId, Long productId);

    Mono<Void> deleteByParticipantIdAndProductId(Long participantId, Long productId);
}