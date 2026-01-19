package com.model_store.service.impl;

import com.model_store.exception.ApiErrors;
import com.model_store.mapper.ProductMapper;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.ProductBasket;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.dto.ProductDto;
import com.model_store.repository.ProductBasketRepository;
import com.model_store.repository.ProductRepository;
import com.model_store.service.BasketService;
import com.model_store.service.CategoryService;
import com.model_store.service.ImageService;
import com.model_store.service.ParticipantService;
import com.model_store.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static com.model_store.exception.constant.ErrorCode.PRODUCT_ALREADY_IN_BASKET;
import static com.model_store.exception.constant.ErrorCode.PRODUCT_NOT_FOUND;

@Service
@RequiredArgsConstructor
public class BasketServiceImpl implements BasketService {
    private final ParticipantService participantService;
    private final ProductService productService;
    private final ProductRepository productRepository;
    private final ProductBasketRepository productBasketRepository;
    private final ProductMapper productMapper;
    private final ImageService imageService;
    private final CategoryService categoryService;

    @Override
    public Flux<ProductDto> findBasketProductsByParams(Long participantId, FindProductRequest searchParams) {
        return productBasketRepository.findByParticipantId(participantId)
                .map(ProductBasket::getProductId)
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
    public Mono<Void> addToBasket(Long participantId, Long productId) {
        return Mono.zip(productService.findActualProduct(productId), participantService.findActualById(participantId))
                .switchIfEmpty(Mono.error(
                        ApiErrors.notFound(PRODUCT_NOT_FOUND, "Не удалось найти товар")
                ))
                .flatMap(ignore ->
                        productBasketRepository.findByParticipantIdAndProductId(participantId, productId)
                                .flatMap(e -> Mono.error(
                                        ApiErrors.alreadyExist(PRODUCT_ALREADY_IN_BASKET, "Товар уже добавлен в корзину")
                                ))
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