package com.model_store.service;

import com.model_store.model.FindProductRequest;
import com.model_store.model.dto.ProductBasketDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface BasketService {
    Flux<ProductBasketDto> findBasketProductsByParams(Long participantId, FindProductRequest searchParams);

    Mono<Void> addToBasket(Long participantId, Long productId, Integer count);
    Mono<Void> updateCount(Long participantId, Long productId, Integer count);

    Mono<Void> removeFromBasket(Long participantId, Long productId);
}