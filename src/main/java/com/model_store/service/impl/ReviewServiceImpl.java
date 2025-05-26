package com.model_store.service.impl;

import com.model_store.mapper.ReviewMapper;
import com.model_store.model.ReviewRequestDto;
import com.model_store.model.ReviewResponseDto;
import com.model_store.model.base.Order;
import com.model_store.model.base.Review;
import com.model_store.model.constant.ImageTag;
import com.model_store.repository.OrderRepository;
import com.model_store.repository.ReviewRepository;
import com.model_store.service.ImageService;
import com.model_store.service.ParticipantService;
import com.model_store.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {
    private final ReviewRepository reviewRepository;
    private final ReviewMapper reviewMapper;
    private final ParticipantService participantService;
    private final OrderRepository orderRepository;
    private final ImageService imageService;

    @Override
    public Mono<Long> addReview(ReviewRequestDto dto, Long participantId) {
        return reviewRepository.findByReviewerId(participantId)
                .flatMap(i -> Mono.error(new IllegalStateException("Отзыв уже существует для этого заказа от пользователя.")))
                .switchIfEmpty(orderRepository.findById(dto.getOrderId()))
                .flatMap(order -> reviewRepository.save(reviewMapper.toReview(dto, (Order) order, participantId)).map(Review::getId));
    }

    @Override
    public Flux<ReviewResponseDto> getReviewsForSeller(Long sellerId) {
        return reviewRepository.findBySellerIdOrderByCreatedAtDesc(sellerId)
                .flatMap(review -> participantService.findFullNameById(review.getReviewerId())
                        .zipWith(imageService.findMainImage(review.getReviewerId(), ImageTag.PARTICIPANT))
                        .map(tuple2 -> reviewMapper.toReviewResponseDto(review, tuple2.getT1(), tuple2.getT2()))
                );
    }

    @Override
    public Flux<ReviewResponseDto> findByProductId(Long productId) {
        return reviewRepository.findByProductIdOrderByCreatedAtDesc(productId)
                .flatMap(review -> participantService.findFullNameById(review.getReviewerId())
                        .zipWith(imageService.findMainImage(review.getReviewerId(), ImageTag.PARTICIPANT))
                        .map(tuple2 -> reviewMapper.toReviewResponseDto(review, tuple2.getT1(), tuple2.getT2()))
                );
    }

    @Override
    public Mono<Void> deleteReview(Long reviewId, Long participantId) {
        return reviewRepository.findByReviewerId(participantId)
                .filter(review -> review.getId().equals(reviewId))
                .flatMap(i -> reviewRepository.deleteById(reviewId));
    }
}
