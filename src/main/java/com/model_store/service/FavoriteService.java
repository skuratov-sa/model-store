package com.model_store.service;

import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
import com.model_store.model.page.PagedResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface FavoriteService {
    Flux<PagedResult<Product>> findFavoriteByParams(Long participantId, FindProductRequest searchParams);

    Mono<Void> addToFavorites(Long participantId, Long productId);

    Mono<Void> removeFromFavorites(Long participantId, Long productId);
}