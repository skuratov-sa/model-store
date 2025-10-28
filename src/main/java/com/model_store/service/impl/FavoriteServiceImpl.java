package com.model_store.service.impl;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.mapper.ProductMapper;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.ProductFavorite;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.dto.ProductDto;
import com.model_store.repository.ProductFavoriteRepository;
import com.model_store.repository.ProductRepository;
import com.model_store.service.CategoryService;
import com.model_store.service.FavoriteService;
import com.model_store.service.ImageService;
import com.model_store.service.ParticipantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class FavoriteServiceImpl implements FavoriteService {
    private final ProductFavoriteRepository productFavoriteRepository;
    private final ProductRepository productRepository;
    private final ParticipantService participantService;
    private final ImageService imageService;
    private final ProductMapper productMapper;
    private final CategoryService categoryService;

    @Override
    public Flux<ProductDto> findFavoriteByParams(Long participantId, FindProductRequest searchParams) {
        return productFavoriteRepository.findByParticipantId(participantId)
                .map(ProductFavorite::getProductId)
                .collectList()
                .flatMapMany(ids ->
                        productRepository.findByParams(searchParams, ids.toArray(Long[]::new))
                                .concatMap(product -> categoryService.findByProductId(product.getId()).collectList()
                                        .zipWith(imageService.findMainImage(product.getId(), ImageTag.PRODUCT).defaultIfEmpty(-1L))
                                        .map(tuple -> productMapper.toProductDto(product, tuple.getT1(), tuple.getT2() == -1L ? null : tuple.getT2()))
                                )
                );
    }

    @Override
    public Mono<Void> addToFavorites(Long participantId, Long productId) {
        return Mono.zip(productRepository.findActualProduct(productId), participantService.findActualById(participantId))
                .switchIfEmpty(Mono.error(new NotFoundException("Product or participant not found")))
                .flatMap(ignore ->
                        productFavoriteRepository.findByParticipantIdAndProductId(participantId, productId)
                                .switchIfEmpty(productFavoriteRepository.save(ProductFavorite.builder()
                                        .participantId(participantId)
                                        .productId(productId)
                                        .build())
                                ).then()
                );
    }

    @Override
    public Mono<Void> removeFromFavorites(Long participantId, Long productId) {
        return productFavoriteRepository.deleteByParticipantIdAndProductId(participantId, productId);
    }
}