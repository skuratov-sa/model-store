package com.model_store.service.impl;

import com.model_store.exception.ApiErrors;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.mapper.ReviewMapper;
import com.model_store.model.ReviewRequestDto;
import com.model_store.model.ReviewResponseDto;
import com.model_store.model.base.Order;
import com.model_store.model.base.Review;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.OrderStatus;
import com.model_store.repository.OrderRepository;
import com.model_store.repository.ReviewRepository;
import com.model_store.service.ImageService;
import com.model_store.service.ParticipantService;
import com.model_store.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
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
        log.info("Adding review: orderId={}, participantId={}", dto.getOrderId(), participantId);
        return reviewRepository.findByOrderIdAndReviewerId(dto.getOrderId(), participantId)
                .flatMap(i -> Mono.error(ApiErrors.badRequest(ErrorCode.REVIEW_ALREADY_EXIST, "Вы уже оставили отзыв для этого заказа")))
                .switchIfEmpty(
                        orderRepository.findById(dto.getOrderId())
                                .filter(order -> order.getCustomerId().equals(participantId))
                                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.ORDER_NOT_FOUND, "Нет данных о заказе товара")))
                                .filter(order -> List.of(OrderStatus.COMPLETED, OrderStatus.FAILED).contains(order.getStatus()))
                                .switchIfEmpty(Mono.error(ApiErrors.badRequest(ErrorCode.ORDER_NOT_TERMINATED, "Нельзя оставить отзыв пока заказ еще активен")))
                )
                .cast(Order.class)
                .flatMap(order -> reviewRepository.save(reviewMapper.toReview(dto, order, participantId)).map(Review::getId));
    }

    @Override
    public Flux<ReviewResponseDto> getReviewsForSeller(Long sellerId) {
        return reviewRepository.findBySellerIdOrderByCreatedAtDesc(sellerId)
                .concatMap(review -> participantService.findFullNameById(review.getReviewerId())
                        .zipWith(imageService.findMainImage(review.getReviewerId(), ImageTag.PARTICIPANT)
                                .defaultIfEmpty(-1L))
                        .map(tuple2 -> reviewMapper.toReviewResponseDto(review, tuple2.getT1(), tuple2.getT2() == -1L ? null : tuple2.getT2()))
                );
    }

    @Override
    public Flux<ReviewResponseDto> findByProductId(Long productId) {
        return reviewRepository.findByProductIdOrderByCreatedAtDesc(productId)
                .flatMap(review ->
                        Mono.zip(
                                        participantService.findFullNameById(review.getReviewerId()).defaultIfEmpty("unknown"),
                                        imageService.findMainImage(review.getReviewerId(), ImageTag.PARTICIPANT).defaultIfEmpty(-1L)
                                )
                                .map(t -> reviewMapper.toReviewResponseDto(review, t.getT1(), t.getT2() == -1L ? null : t.getT2()))
                );
    }

    @Override
    public Mono<Void> deleteReview(Long reviewId, Long participantId) {
        log.info("Deleting review: reviewId={}, participantId={}", reviewId, participantId);
        return reviewRepository.findById(reviewId)
                .filter(review -> review.getReviewerId().equals(participantId))
                .flatMap(i -> reviewRepository.deleteById(reviewId));
    }
}
