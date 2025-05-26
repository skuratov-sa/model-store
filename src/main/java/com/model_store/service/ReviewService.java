package com.model_store.service;

import com.model_store.model.ReviewRequestDto;
import com.model_store.model.ReviewResponseDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ReviewService {
    Mono<Long> addReview(ReviewRequestDto dto, Long participantId);

    Flux<ReviewResponseDto> getReviewsForSeller(Long sellerId);

    Mono<Void> deleteReview(Long reviewId, Long participantId);
}
