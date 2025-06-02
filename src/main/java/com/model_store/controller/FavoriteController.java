package com.model_store.controller;

import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
import com.model_store.model.dto.ProductDto;
import com.model_store.service.FavoriteService;
import com.model_store.service.JwtService;
import io.swagger.v3.oas.annotations.Operation;
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
@RequestMapping("/favorites")
@RequiredArgsConstructor
public class FavoriteController {
    private final FavoriteService favoriteService;
    private final JwtService jwtService;

    @Operation(summary = "Получения списка избранных товаров пользователя")
    @PostMapping("/find")
    public Flux<ProductDto> findFavorites(@RequestHeader("Authorization") String authorizationHeader, @RequestBody FindProductRequest searchParams) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return favoriteService.findFavoriteByParams(participantId, searchParams);
    }

    @Operation(summary = "Добавить товар в избранное")
    @PostMapping
    public Mono<Void> addToFavorites(@RequestHeader("Authorization") String authorizationHeader, @RequestParam Long productId) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return favoriteService.addToFavorites(participantId, productId);
    }

    @Operation(summary = "Удалить товар из избранного")
    @DeleteMapping
    public Mono<Void> removeFromFavorites(@RequestHeader("Authorization") String authorizationHeader, @RequestParam Long productId) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return favoriteService.removeFromFavorites(participantId, productId);
    }
}