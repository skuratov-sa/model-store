package com.model_store.service.impl;

import com.model_store.mapper.ProductMapper;
import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
import com.model_store.model.base.ProductFavorite;
import com.model_store.repository.ProductFavoriteRepository;
import com.model_store.repository.ProductRepository;
import com.model_store.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;
    private final ProductFavoriteRepository productFavoriteRepository;
    private final ProductMapper productMapper;

    public Mono<Product> findById(Long productId) {
        return productRepository.findById(productId);
    }

    public Flux<Product> findByParams(FindProductRequest searchParams) {
        return productRepository.findByParams(searchParams);
    }

    public Flux<Product> findFavoriteByParams(Long participantId, FindProductRequest searchParams) {
        return productFavoriteRepository.findByParticipantId(participantId)
                .map(ProductFavorite::getProductId)
                .collectList()
                .flatMapMany(ids -> productRepository.findByParams(searchParams, ids));
    }

    public Mono<Void> addToFavorites(Long participantId, Long productId) {
        return productFavoriteRepository.findByParticipantIdAndProductId(participantId, productId)
                .switchIfEmpty(productFavoriteRepository.save(ProductFavorite.builder()
                        .participantId(participantId)
                        .productId(productId)
                        .build())
                ).then();
    }

    public Mono<Void> removeFromFavorites(Long participantId, Long productId) {
        return productFavoriteRepository.deleteByParticipantIdAndProductId(participantId, productId);
    }

    public Mono<Void> createProduct(CreateOrUpdateProductRequest request) {
        Product product = productMapper.toProduct(request);
        product.setCreatedAt(Instant.now());

        return productRepository.save(product).then();
    }

    public Mono<Void> updateProduct(Long id, CreateOrUpdateProductRequest request) {
        return productRepository.findById(id)
                .map(product -> productMapper.updateProduct(request, product))
                .flatMap(productRepository::save)
                .then();
    }

    public Mono<Void> deleteProduct(Long id) {
        return productRepository.deleteById(id);
    }

    public Mono<Void> deleteProductsByParticipant(Long participantId) {
        return productRepository.deleteAllByParticipantId(participantId);
    }
}