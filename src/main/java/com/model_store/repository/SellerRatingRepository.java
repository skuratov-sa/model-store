package com.model_store.repository;

import com.model_store.model.base.SellerRating;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface SellerRatingRepository extends ReactiveCrudRepository<SellerRating, Long> {
    Mono<SellerRating> findBySellerId(Long sellerId);

    @Query("SELECT seller_id, average_rating, total_reviews FROM seller_rating WHERE seller_id = ANY(:sellerIds)")
    Flux<SellerRating> findBySellerIds(Long[] sellerIds);
}
