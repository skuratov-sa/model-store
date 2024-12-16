package com.model_store.service;

import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


public interface ProductService {

    Mono<Product> findById(Long productId);

    Flux<Product> findByParams(FindProductRequest searchParams);

    Flux<Product> findFavoriteByParams(Long participantId, FindProductRequest searchParams);

    Mono<Long> createProduct(CreateOrUpdateProductRequest request);

    Mono<Void> updateProduct(Long id, CreateOrUpdateProductRequest request);

    Mono<Void> deleteProduct(Long id);

    Mono<Void> addToFavorites(Long participantId, Long productId);

    Mono<Void> removeFromFavorites(Long participantId, Long productId);

    Mono<Void> deleteProductsByParticipant(Long participantId);
}