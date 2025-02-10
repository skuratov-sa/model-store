package com.model_store.service;

import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
import com.model_store.model.dto.GetProductResponse;
import com.model_store.model.dto.ProductDto;
import com.model_store.model.page.PagedResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


public interface ProductService {

    Mono<GetProductResponse> getProductById(Long productId);

    Mono<Product> findById(Long productId);

    Mono<ProductDto> shortInfoById(Long productId);

    Mono<PagedResult<ProductDto>> findByParams(FindProductRequest searchParams);

    Flux<PagedResult<Product>> findFavoriteByParams(Long participantId, FindProductRequest searchParams);

    Mono<Long> createProduct(CreateOrUpdateProductRequest request);

    Mono<Void> updateProduct(Long id, CreateOrUpdateProductRequest request);

    Mono<Void> deleteProduct(Long id);

    Mono<Void> addToFavorites(Long participantId, Long productId);

    Mono<Void> removeFromFavorites(Long participantId, Long productId);

    Mono<Void> deleteProductsByParticipant(Long participantId);

    Mono<Long> save(Product product);
}