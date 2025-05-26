package com.model_store.repository;

import com.model_store.model.base.Review;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ReviewRepository extends ReactiveCrudRepository<Review, Long> {

    Flux<Review> findBySellerIdOrderByCreatedAtDesc(Long sellerId);

    Flux<Review> findByProductIdOrderByCreatedAtDesc(Long productId);

    Mono<Boolean> existsByOrderIdAndReviewerId(Long orderProductId, Long reviewerId);

    Mono<Review> findByReviewerId(Long reviewerId);
}