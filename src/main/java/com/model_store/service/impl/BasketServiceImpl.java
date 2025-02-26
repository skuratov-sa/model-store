package com.model_store.service.impl;

import com.model_store.exception.EntityAlreadyExistException;
import com.model_store.exception.EntityNotFoundException;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
import com.model_store.model.base.ProductBasket;
import com.model_store.model.page.PagedResult;
import com.model_store.repository.ProductBasketRepository;
import com.model_store.repository.ProductRepository;
import com.model_store.service.BasketService;
import com.model_store.service.ParticipantService;
import com.model_store.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static com.model_store.exception.constant.EntityException.BASKET;

@Service
@RequiredArgsConstructor
public class BasketServiceImpl implements BasketService {
    private final ParticipantService participantService;
    private final ProductService productService;
    private final ProductRepository productRepository;
    private final ProductBasketRepository productBasketRepository;

    @Override
    public Flux<PagedResult<Product>> findBasketProductsByParams(Long participantId, FindProductRequest searchParams) {
        return productBasketRepository.findByParticipantId(participantId)
                .map(ProductBasket::getProductId)
                .collectList()
                .flatMapMany(productIds -> {
                    var monoProducts = productRepository.findByParams(searchParams, productIds.toArray(Long[]::new)).collectList();
                    return monoProducts.map(list -> new PagedResult<>(list, productIds.size(), searchParams.getPageable()));
                });
    }

    @Override
    public Mono<Void> addToBasket(Long participantId, Long productId) {
        return Mono.zip(productService.findActualProduct(productId), participantService.findActualById(participantId))
                .switchIfEmpty(Mono.error(new EntityNotFoundException(BASKET, productId)))
                .flatMap(ignore ->
                        productBasketRepository.findByParticipantIdAndProductId(participantId, productId)
                                .flatMap(e -> Mono.error(new EntityAlreadyExistException(BASKET)))
                                .switchIfEmpty(productBasketRepository.save(ProductBasket.builder()
                                        .participantId(participantId)
                                        .productId(productId)
                                        .build())
                                ).then()
                );
    }

    @Override
    public Mono<Void> removeFromBasket(Long participantId, Long productId) {
        return productBasketRepository.deleteByParticipantIdAndProductId(participantId, productId);
    }
}