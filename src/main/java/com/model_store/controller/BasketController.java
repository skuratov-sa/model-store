package com.model_store.controller;

import com.model_store.model.FindProductRequest;
import com.model_store.model.dto.ProductBasketDto;
import com.model_store.service.BasketService;
import com.model_store.service.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/basket")
@RequiredArgsConstructor
public class BasketController {
    private final BasketService basketService;
    private final JwtService jwtService;

    @Operation(summary = "Получить список товаров из корзины")
    @PostMapping("/find")
    public Flux<ProductBasketDto> findBasketProductsByParams(@RequestHeader("Authorization") String authorizationHeader, @RequestBody @Valid FindProductRequest request) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return basketService.findBasketProductsByParams(participantId, request);
    }

    @Operation(summary = "Добавить товар в корзину")
    @PostMapping
    public Mono<Void> addToBasket(@RequestHeader("Authorization") String authorizationHeader,
                                  @RequestParam Long productId,
                                  @RequestParam Integer count
    ) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return basketService.addToBasket(participantId, productId, count);
    }

    @Operation(summary = "Обновить кол-во товара которые поместили в корзину")
    @PutMapping
    public Mono<Void> updateCountToBasket(@RequestHeader("Authorization") String authorizationHeader,
                                          @RequestParam Long productId,
                                          @RequestParam Integer count
    ) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return basketService.updateCount(participantId, productId, count);
    }

    @Operation(summary = "Удалить товар из корзины")
    @DeleteMapping
    public Mono<Void> removeFromFavorites(@RequestHeader("Authorization") String authorizationHeader, @RequestParam Long productId) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return basketService.removeFromBasket(participantId, productId);
    }
}