package com.model_store.service.impl;

import com.model_store.model.base.SellerRating;
import com.model_store.repository.SellerRatingRepository;
import com.model_store.service.SellerRatingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
@Service
@RequiredArgsConstructor
public class SellerRatingServiceImpl implements SellerRatingService {
    private final SellerRatingRepository sellerRatingRepository;


    @Override
    public Flux<SellerRating> findAll() {
        return sellerRatingRepository.findAll();
    }

    @Override
    public Mono<SellerRating> findBySellarId(Long sellerId) {
        return sellerRatingRepository.findBySellerId(sellerId);
    }

    @Override
    public Mono<SellerRating> create(SellerRating rating) {
        return null;
    }

    @Override
    public Mono<SellerRating> update(Long sellerId, SellerRating updated) {
        return null;
    }

    @Override
    public Mono<Void> delete(Long sellerId) {
        return null;
    }
}