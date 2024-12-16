package com.model_store.service.impl;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.mapper.ProductMapper;
import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
import com.model_store.model.base.ProductFavorite;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.constant.ProductStatus;
import com.model_store.repository.ProductCategoryRepository;
import com.model_store.repository.ProductFavoriteRepository;
import com.model_store.repository.ProductRepository;
import com.model_store.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static java.util.Objects.isNull;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final ProductFavoriteRepository productFavoriteRepository;
    private final ProductMapper productMapper;
    private final ImageServiceImpl imageService;

    public Mono<Product> findById(Long productId) {
        return productRepository.findById(productId);
    }

    public Flux<Product> findByParams(FindProductRequest searchParams) {
        return productRepository.findByParams(searchParams);
    }

    public Flux<Product> findFavoriteByParams(Long participantId, FindProductRequest searchParams) {
        return productFavoriteRepository.findByParticipantId(participantId)
                .map(ProductFavorite::getProductId)
                .flatMap(id -> productRepository.findByParams(searchParams, List.of(id)));
        //TODO переделать
//                .collectList()
//                .flatMapMany(ids -> productRepository.findByParams(searchParams, ids));
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

    @Transactional
    public Mono<Long> createProduct(CreateOrUpdateProductRequest request) {
        Product product = productMapper.toProduct(request);

        return productRepository.save(product)
                .flatMap(p -> updateImagesStatus(request.getImageIds(), p.getId())
                        .then(Mono.just(p.getId()))
                );
    }

    @Transactional
    public Mono<Void> updateProduct(Long id, CreateOrUpdateProductRequest request) {
        return productRepository.findById(id)
                .filter(product -> product.getStatus().equals(ProductStatus.ACTIVE))
                .switchIfEmpty(Mono.error(new NotFoundException("Product not found")))
                .map(product -> productMapper.updateProduct(request, product))
                .flatMap(productRepository::save)
                .flatMap(p -> updateImagesStatus(request.getImageIds(), id))
                .then();
    }

    public Mono<Void> deleteProduct(Long id) {
        return productRepository.findById(id)
                .switchIfEmpty(Mono.error(new NotFoundException("Product not found")))
                .flatMap(product -> {
                    product.setStatus(ProductStatus.DELETED);
                    return productRepository.save(product);
                }).then();
    }

    public Mono<Void> deleteProductsByParticipant(Long participantId) {
        return productRepository.findByParticipantId(participantId)
                .flatMap(product ->
                        productCategoryRepository.deleteAllByProductId(product.getParticipantId())
                                .then(productRepository.delete(product))
                ).then();
    }

    private Mono<Void> updateImagesStatus(List<Long> imageIds, Long productId) {
        if (isNull(imageIds) || imageIds.isEmpty()) {
            return Mono.empty();
        }
        return imageService.updateImagesStatus(imageIds, productId, ImageStatus.ACTIVE, ImageTag.PRODUCT);
    }
}