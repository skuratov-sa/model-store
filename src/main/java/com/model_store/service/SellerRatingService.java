package com.model_store.service;

import com.model_store.model.base.SellerRating;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SellerRatingService {


    Flux<SellerRating> findAll();

    Mono<SellerRating> findBySellarId(Long sellerId);

    Mono<SellerRating> create(SellerRating rating);

    Mono<SellerRating> update(Long sellerId, SellerRating updated);

    Mono<Void> delete(Long sellerId);
}