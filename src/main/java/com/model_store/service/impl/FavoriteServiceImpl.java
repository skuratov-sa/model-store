package com.model_store.service.impl;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
import com.model_store.model.base.ProductFavorite;
import com.model_store.model.page.PagedResult;
import com.model_store.repository.ProductFavoriteRepository;
import com.model_store.repository.ProductRepository;
import com.model_store.service.FavoriteService;
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

    @Override
    public Flux<PagedResult<Product>> findFavoriteByParams(Long participantId, FindProductRequest searchParams) {
        return productFavoriteRepository.findByParticipantId(participantId)
                .map(ProductFavorite::getProductId)
                .collectList()
                .flatMapMany(productIds -> {
                    var monoProducts = productRepository.findByParams(searchParams, productIds.toArray(Long[]::new)).collectList();
                    var monoTotalCount = productRepository.findCountBySearchParams(searchParams, null).defaultIfEmpty(0);

                    return monoProducts.zipWith(monoTotalCount)
                            .map(tuple -> new PagedResult<>(tuple.getT1(), tuple.getT2(), searchParams.getPageable()));

                });
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