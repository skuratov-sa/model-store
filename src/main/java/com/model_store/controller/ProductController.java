package com.model_store.controller;

import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.FindProductRequest;
import com.model_store.model.dto.GetProductResponse;
import com.model_store.model.dto.ProductDto;
import com.model_store.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
public class ProductController {
    private final ProductService productService;

    @Operation(summary = "Полная информация о товаре")
    @GetMapping(path = "/product/{id}")
    public Mono<ResponseEntity<GetProductResponse>> getProduct(@PathVariable Long id) {
        return productService.getProductById(id)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Поиск списка товаров")
    @PostMapping(path = "/products/find")
    public Flux<ProductDto> findProducts(@RequestBody FindProductRequest searchParams) {
        return productService.findByParams(searchParams);
    }

    @Operation(summary = "Создать товар")
    @PostMapping(path = "/product")
    public Mono<Long> createProduct(@RequestBody CreateOrUpdateProductRequest request) {
        return productService.createProduct(request);
    }

    @Operation(summary = "Обновить товар по id")
    @PutMapping(path = "/product/{id}")
    public Mono<Void> updateProduct(@PathVariable Long id, @RequestBody CreateOrUpdateProductRequest request) {
        return productService.updateProduct(id, request);
    }

    @Operation(summary = "Удалить товар")
    @DeleteMapping(path = "/product/{id}")
    public Mono<Void> deleteProduct(@PathVariable Long id) {
        return productService.deleteProduct(id);
    }

    @Operation(summary = "Получения списка избранных товаров пользователя")
    @PostMapping("/favorites/{participantId}")
    public Flux<GetProductResponse> findFavorites(@PathVariable Long participantId, @RequestBody FindProductRequest searchParams) {
        return productService.findFavoriteByParams(participantId, searchParams);
    }

    @Operation(summary = "Добавить товар в избранное")
    @PostMapping("/favorites")
    public Mono<Void> addToFavorites(@RequestParam Long participantId, @RequestParam Long productId) {
        return productService.addToFavorites(participantId, productId);
    }

    @Operation(summary = "Удалить товар из избранного")
    @DeleteMapping("/favorites")
    public Mono<Void> removeFromFavorites(@RequestParam Long participantId, @RequestParam Long productId) {
        return productService.removeFromFavorites(participantId, productId);
    }
}