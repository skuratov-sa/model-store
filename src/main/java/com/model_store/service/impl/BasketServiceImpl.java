package com.model_store.service.impl;

import com.model_store.exception.ApiErrors;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
import com.model_store.model.base.ProductBasket;
import com.model_store.model.dto.FullParticipantDto;
import com.model_store.model.dto.ProductBasketDto;
import com.model_store.repository.ProductBasketRepository;
import com.model_store.repository.ProductRepository;
import com.model_store.service.BasketService;
import com.model_store.service.ParticipantService;
import com.model_store.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.stream.Collectors;

import static com.model_store.exception.constant.ErrorCode.BASKET_ITEM_NOT_FOUND;
import static com.model_store.exception.constant.ErrorCode.BASKET_UPDATE_FAILED;
import static com.model_store.exception.constant.ErrorCode.NOT_ENOUGH_STOCK;
import static com.model_store.exception.constant.ErrorCode.PARTICIPANT_NOT_FOUND;
import static com.model_store.exception.constant.ErrorCode.PRODUCT_ALREADY_IN_BASKET;
import static com.model_store.exception.constant.ErrorCode.PRODUCT_NOT_FOUND;

@Service
@RequiredArgsConstructor
public class BasketServiceImpl implements BasketService {
    private final ParticipantService participantService;
    private final ProductService productService;
    private final ProductRepository productRepository;
    private final ProductBasketRepository productBasketRepository;

    @Override
    public Flux<ProductBasketDto> findBasketProductsByParams(Long participantId, FindProductRequest searchParams) {
        return productBasketRepository.findByParticipantId(participantId)
                .collectList()
                .flatMapMany(baskets -> {
                    if (baskets.isEmpty()) {
                        return Flux.empty();
                    }

                    // productId -> count
                    Map<Long, Integer> countByProductId = baskets.stream()
                            .collect(Collectors.toMap(
                                    ProductBasket::getProductId,
                                    ProductBasket::getCount,
                                    Integer::sum // на случай дублей
                            ));

                    Long[] ids = countByProductId.keySet().toArray(Long[]::new);

                    return productRepository.findByParams(searchParams, ids)
                            .concatMap(productService::buildProductDto)
                            .map(dto -> {
                                ProductBasketDto out = new ProductBasketDto();
                                out.setProduct(dto);
                                out.setCount(countByProductId.getOrDefault(dto.getId(), 0));
                                return out;
                            });
                });
    }


    @Override
    @Transactional
    public Mono<Void> addToBasket(Long participantId, Long productId, Integer count) {
        final int qty = (count == null ? 0 : count);
        if (qty <= 0) {
            return Mono.error(ApiErrors.badRequest(ErrorCode.COUNT_INVALID, "Количество должно быть > 0"));
        }

        Mono<Product> actualProductMono = productService.findActualProduct(productId)
                .switchIfEmpty(Mono.error(ApiErrors.notFound(PRODUCT_NOT_FOUND, "Не удалось найти товар")))
                .filter(p -> p.getCount() == null || p.getCount() >= qty)
                .switchIfEmpty(Mono.error(ApiErrors.badRequest(NOT_ENOUGH_STOCK, "Нельзя добавить столько товаров")));

        Mono<FullParticipantDto> participantMono = participantService.findActualById(participantId)
                .switchIfEmpty(Mono.error(ApiErrors.notFound(PARTICIPANT_NOT_FOUND, "Не удалось распознать пользователя")));

        return Mono.zip(actualProductMono, participantMono)
                .then(
                        productBasketRepository.findByParticipantIdAndProductId(participantId, productId)
                                .flatMap(existing ->
                                        Mono.error(ApiErrors.alreadyExist(PRODUCT_ALREADY_IN_BASKET, "Товар уже в корзине"))
                                )
                                .switchIfEmpty(Mono.defer(() ->
                                        productBasketRepository.save(ProductBasket.builder()
                                                        .productId(productId)
                                                        .participantId(participantId)
                                                        .count(qty)
                                                        .build())
                                                .then()
                                ))
                ).then();
    }


    @Transactional
    @Override
    public Mono<Void> updateCount(Long participantId, Long productId, Integer count) {
        final int qty = (count == null ? 0 : count);
        if (qty <= 0) {
            return Mono.error(ApiErrors.badRequest(ErrorCode.COUNT_INVALID, "Количество должно быть > 0"));
        }

        Mono<Product> actualProductMono = productService.findActualProduct(productId)
                .switchIfEmpty(Mono.error(ApiErrors.notFound(PRODUCT_NOT_FOUND, "Не удалось найти товар")))
                .filter(p -> p.getCount() == null || p.getCount() >= qty)
                .switchIfEmpty(Mono.error(ApiErrors.badRequest(NOT_ENOUGH_STOCK, "Нельзя поставить столько товаров")));

        Mono<FullParticipantDto> participantMono = participantService.findActualById(participantId)
                .switchIfEmpty(Mono.error(ApiErrors.notFound(PARTICIPANT_NOT_FOUND, "Не удалось распознать пользователя")));

        return Mono.zip(actualProductMono, participantMono)
                .then(
                        productBasketRepository.findByParticipantIdAndProductId(participantId, productId)
                                .switchIfEmpty(Mono.error(ApiErrors.notFound(BASKET_ITEM_NOT_FOUND, "Товара нет в корзине")))
                                .flatMap(existing ->
                                        productBasketRepository.updateQty(participantId, productId, qty)
                                                .flatMap(rows -> rows == 1
                                                        ? Mono.empty()
                                                        : Mono.error(ApiErrors.badRequest(BASKET_UPDATE_FAILED,"Не удалось обновить корзину"
                                                )))
                                )
                );
    }


    @Override
    public Mono<Void> removeFromBasket(Long participantId, Long productId) {
        return productBasketRepository.deleteByParticipantIdAndProductId(participantId, productId);
    }
}