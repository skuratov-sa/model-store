package com.model_store.controller;

import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
import com.model_store.model.page.PagedResult;
import com.model_store.service.BasketService;
import com.model_store.service.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    public Flux<PagedResult<Product>> findBasketProductsByParams(@RequestHeader("Authorization") String authorizationHeader, @RequestBody @Valid FindProductRequest request) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return basketService.findBasketProductsByParams(participantId, request);
    }

    @Operation(summary = "Добавить товар в корзину")
    @PostMapping
    public Mono<Void> addToBasket(@RequestHeader("Authorization") String authorizationHeader, @RequestParam Long productId) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return basketService.addToBasket(participantId, productId);
    }

    @Operation(summary = "Удалить товар из избранного")
    @DeleteMapping
    public Mono<Void> removeFromFavorites(@RequestHeader("Authorization") String authorizationHeader, @RequestParam Long productId) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return basketService.removeFromBasket(participantId, productId);
    }
}