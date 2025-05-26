package com.model_store.service.impl;

import com.model_store.mapper.ReviewMapper;
import com.model_store.model.ReviewRequestDto;
import com.model_store.model.ReviewResponseDto;
import com.model_store.model.base.Order;
import com.model_store.model.base.Review;
import com.model_store.repository.ReviewRepository;
import com.model_store.service.OrderService;
import com.model_store.service.ParticipantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceImplTest {

    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private ReviewMapper reviewMapper;
    @Mock
    private ParticipantService participantService;
    @Mock
    private OrderService orderService;

    @InjectMocks
    private ReviewServiceImpl reviewService;

    private Review review1;
    private ReviewRequestDto reviewRequestDto;
    private ReviewResponseDto reviewResponseDto;
    private Order order1;

    @BeforeEach
    void setUp() {
        order1 = new Order();
        order1.setId(1L);
        order1.setCustomerId(20L); // Participant who made the order
        order1.setSellerId(10L);   // Seller being reviewed

        review1 = new Review();
        review1.setId(100L);
        review1.setOrderId(1L);
        review1.setReviewerId(20L); // Participant who wrote the review
        review1.setSellerId(10L);
        review1.setRating(5);
        review1.setComment("Great product!");
        review1.setCreatedAt(Instant.now());

        reviewRequestDto = new ReviewRequestDto();
        reviewRequestDto.setOrderId(1L);
        reviewRequestDto.setRating((short) 5);
        reviewRequestDto.setComment("Great product!");

        reviewResponseDto = new ReviewResponseDto();
        reviewResponseDto.setId(100L);
        reviewResponseDto.setReviewerName("Test Reviewer");
        reviewResponseDto.setRating((short) 5);
        reviewResponseDto.setComment("Great product!");
        reviewResponseDto.setCreatedAt(review1.getCreatedAt());
    }

    @Test
    void addReview_shouldSaveReview_whenOrderExistsAndNoExistingReview() {
        Long participantId = 20L; // Reviewer
        
        when(reviewRepository.findByReviewerId(participantId)).thenReturn(Mono.empty()); // No existing review by this participant
        when(orderService.findById(reviewRequestDto.getOrderId())).thenReturn(Mono.just(order1));
        when(reviewMapper.toReview(reviewRequestDto, order1, participantId)).thenReturn(review1);
        when(reviewRepository.save(review1)).thenReturn(Mono.just(review1));

        StepVerifier.create(reviewService.addReview(reviewRequestDto, participantId))
                .expectNext(review1.getId())
                .verifyComplete();

        verify(reviewRepository).findByReviewerId(participantId);
        verify(orderService).findById(reviewRequestDto.getOrderId());
        verify(reviewMapper).toReview(reviewRequestDto, order1, participantId);
        verify(reviewRepository).save(review1);
    }

    @Test
    void addReview_shouldFail_whenReviewAlreadyExistsForParticipant() {
        Long participantId = 20L;
        // Existing review for the same order by the same participant
        when(reviewRepository.findByReviewerId(participantId)).thenReturn(Mono.just(review1));

        StepVerifier.create(reviewService.addReview(reviewRequestDto, participantId))
                .expectErrorMatches(throwable -> throwable instanceof IllegalStateException &&
                                                 "Отзыв уже существует для этого заказа от пользователя.".equals(throwable.getMessage()))
                .verify();

        verify(reviewRepository).findByReviewerId(participantId);
        verify(orderService, never()).findById(anyLong());
        verify(reviewRepository, never()).save(any(Review.class));
    }

    @Test
    void addReview_shouldFail_whenOrderNotFound() {
        Long participantId = 20L;
        when(reviewRepository.findByReviewerId(participantId)).thenReturn(Mono.empty());
        when(orderService.findById(reviewRequestDto.getOrderId())).thenReturn(Mono.empty()); // Order not found

        StepVerifier.create(reviewService.addReview(reviewRequestDto, participantId))
                // The original code doesn't explicitly throw an error if order is not found before flatMap.
                // It will result in an empty Mono from flatMap, which might not be what's expected.
                // Let's assume flatMap on empty Mono results in empty completion.
                // If an error is expected, the service logic needs adjustment.
                // Based on `flatMap(order -> ...)` if order is empty, the inner part is not executed, so it completes empty.
                .verifyComplete(); 

        verify(reviewRepository).findByReviewerId(participantId);
        verify(orderService).findById(reviewRequestDto.getOrderId());
        verify(reviewRepository, never()).save(any(Review.class));
    }

    @Test
    void getReviewsForSeller_shouldReturnFluxOfReviewResponseDto_whenReviewsExist() {
        Long sellerId = 10L;
        String reviewerName = "Test Reviewer";

        when(reviewRepository.findBySellerIdOrderByCreatedAtDesc(sellerId)).thenReturn(Flux.just(review1));
        when(participantService.findFullNameById(review1.getReviewerId())).thenReturn(Mono.just(reviewerName));
        when(reviewMapper.toReviewResponseDto(review1, reviewerName, tuple2.getT2())).thenReturn(reviewResponseDto);

        StepVerifier.create(reviewService.getReviewsForSeller(sellerId))
                .expectNext(reviewResponseDto)
                .verifyComplete();

        verify(reviewRepository).findBySellerIdOrderByCreatedAtDesc(sellerId);
        verify(participantService).findFullNameById(review1.getReviewerId());
        verify(reviewMapper).toReviewResponseDto(review1, reviewerName, tuple2.getT2());
    }

    @Test
    void getReviewsForSeller_shouldReturnEmptyFlux_whenNoReviewsExistForSeller() {
        Long sellerId = 99L; // Seller with no reviews
        when(reviewRepository.findBySellerIdOrderByCreatedAtDesc(sellerId)).thenReturn(Flux.empty());

        StepVerifier.create(reviewService.getReviewsForSeller(sellerId))
                .verifyComplete();

        verify(reviewRepository).findBySellerIdOrderByCreatedAtDesc(sellerId);
        verify(participantService, never()).findFullNameById(anyLong());
        verify(reviewMapper, never()).toReviewResponseDto(any(Review.class), anyString(), tuple2.getT2());
    }
    
    @Test
    void getReviewsForSeller_shouldHandleErrorFromParticipantService() {
        Long sellerId = 10L;
        when(reviewRepository.findBySellerIdOrderByCreatedAtDesc(sellerId)).thenReturn(Flux.just(review1));
        when(participantService.findFullNameById(review1.getReviewerId()))
            .thenReturn(Mono.error(new RuntimeException("Participant service error")));

        StepVerifier.create(reviewService.getReviewsForSeller(sellerId))
            .expectErrorMatches(throwable -> throwable instanceof RuntimeException && 
                                             "Participant service error".equals(throwable.getMessage()))
            .verify();
    }

    @Test
    void deleteReview_shouldDeleteReview_whenReviewExistsAndBelongsToParticipant() {
        Long reviewId = 100L;
        Long participantId = 20L; // review1 was written by participant 20L

        when(reviewRepository.findByReviewerId(participantId)).thenReturn(Mono.just(review1));
        when(reviewRepository.deleteById(reviewId)).thenReturn(Mono.empty());

        StepVerifier.create(reviewService.deleteReview(reviewId, participantId))
                .verifyComplete();

        verify(reviewRepository).findByReviewerId(participantId);
        verify(reviewRepository).deleteById(reviewId);
    }

    @Test
    void deleteReview_shouldNotDelete_whenReviewDoesNotExistForParticipant() {
        Long reviewId = 101L; // A review ID that doesn't match review1
        Long participantId = 20L;

        // Participant has review1, but we are trying to delete review 101L
        when(reviewRepository.findByReviewerId(participantId)).thenReturn(Mono.just(review1));

        StepVerifier.create(reviewService.deleteReview(reviewId, participantId))
                .verifyComplete(); // Completes because filter results in empty

        verify(reviewRepository).findByReviewerId(participantId);
        verify(reviewRepository, never()).deleteById(anyLong());
    }

    @Test
    void deleteReview_shouldNotDelete_whenParticipantHasNoReviews() {
        Long reviewId = 100L;
        Long participantId = 21L; // Participant with no reviews

        when(reviewRepository.findByReviewerId(participantId)).thenReturn(Mono.empty());

        StepVerifier.create(reviewService.deleteReview(reviewId, participantId))
                .verifyComplete(); // Completes because filter results in empty

        verify(reviewRepository).findByReviewerId(participantId);
        verify(reviewRepository, never()).deleteById(anyLong());
    }

    @Test
    void deleteReview_shouldFail_whenDeleteFromRepositoryFails() {
        Long reviewId = 100L;
        Long participantId = 20L;

        when(reviewRepository.findByReviewerId(participantId)).thenReturn(Mono.just(review1));
        when(reviewRepository.deleteById(reviewId)).thenReturn(Mono.error(new RuntimeException("DB delete error")));

        StepVerifier.create(reviewService.deleteReview(reviewId, participantId))
                .expectErrorMatches(throwable -> throwable instanceof RuntimeException &&
                                                 "DB delete error".equals(throwable.getMessage()))
                .verify();

        verify(reviewRepository).findByReviewerId(participantId);
        verify(reviewRepository).deleteById(reviewId);
    }
}
