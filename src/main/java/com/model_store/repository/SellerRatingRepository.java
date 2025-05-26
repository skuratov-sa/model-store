package com.model_store.repository;

import com.model_store.model.base.SellerRating;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface SellerRatingRepository extends ReactiveCrudRepository<SellerRating, Long> {
    Mono<SellerRating> findBySellerId(Long sellerId);
}