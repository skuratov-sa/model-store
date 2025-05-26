package com.model_store.controller;

import com.model_store.model.ReviewRequestDto;
import com.model_store.model.ReviewResponseDto;
import com.model_store.service.JwtService;
import com.model_store.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/reviews")
@RequiredArgsConstructor
public class ReviewController {
    private final ReviewService reviewService;
    private final JwtService jwtService;

    @GetMapping("/seller/{sellerId}")
    public Flux<ReviewResponseDto> getReviewsBySeller(@PathVariable Long sellerId) {
        return reviewService.getReviewsForSeller(sellerId);
    }

    @PostMapping
    public Mono<Long> addReview(@RequestHeader("Authorization") String authorizationHeader,
                                @RequestBody @Valid ReviewRequestDto request) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return reviewService.addReview(request, participantId);
    }

    @DeleteMapping
    public Mono<Void> deleteReview(@RequestHeader("Authorization") String authorizationHeader, @RequestParam Long reviewId) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return reviewService.deleteReview(reviewId, participantId);
    }
}
