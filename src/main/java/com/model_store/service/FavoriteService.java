package com.model_store.service;

import com.model_store.model.FindProductRequest;
import com.model_store.model.dto.ProductDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface FavoriteService {
    Flux<ProductDto> findFavoriteByParams(Long participantId, FindProductRequest searchParams);

    Mono<Void> addToFavorites(Long participantId, Long productId);

    Mono<Void> removeFromFavorites(Long participantId, Long productId);
}