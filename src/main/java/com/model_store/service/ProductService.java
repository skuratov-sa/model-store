package com.model_store.service;

import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.constant.ProductStatus;
import com.model_store.model.dto.GetProductResponse;
import com.model_store.model.dto.ProductDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProductService {

    Mono<GetProductResponse> getProductById(Long productId);

    Mono<Product> findById(Long productId);

    Mono<ProductDto> shortInfoById(Long productId);

    Flux<ProductDto> findByParams(FindProductRequest searchParams);

    Mono<Long> createProduct(CreateOrUpdateProductRequest request, Long participantId, ParticipantRole role);

    Mono<Void> updateProduct(Long id, CreateOrUpdateProductRequest request, Long participantId);

    Mono<Void> deleteProduct(Long id, Long participantId);

    Mono<Product> findActualProduct(Long productId);

    Mono<Void> updateProductStatus(Long id, ProductStatus status);

    Mono<Long> save(Product product);
}