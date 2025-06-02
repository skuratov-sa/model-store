package com.model_store.service;

import com.model_store.model.FindProductRequest;
import com.model_store.model.dto.ProductDto;
import com.model_store.model.page.PagedResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface BasketService {
    Flux<ProductDto> findBasketProductsByParams(Long participantId, FindProductRequest searchParams);

    Mono<Void> addToBasket(Long participantId, Long productId);

    Mono<Void> removeFromBasket(Long participantId, Long productId);
}