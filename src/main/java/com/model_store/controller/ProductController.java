package com.model_store.controller;

import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.FindMyProductRequest;
import com.model_store.model.FindProductRequest;
import com.model_store.model.constant.ProductStatus;
import com.model_store.model.dto.GetProductResponse;
import com.model_store.model.dto.ProductDto;
import com.model_store.service.JwtService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ProductController {
    private final ProductService productService;
    private final JwtService jwtService;

    @Operation(summary = "Поиск списка товаров")
    @PostMapping(path = "/products/find")
    public Flux<ProductDto> findProducts(@RequestBody FindProductRequest searchParams) {
        return productService.findByParams(searchParams);
    }

    @Operation(summary = "Поиск названий товаров и категорий в поисковой строке")
    @PostMapping(path = "/products/names/find")
    public Mono<List<String>> findNamesBySearch(String name) {
        return productService.findNamesBySearch(name).collectList();
    }

    @Operation(summary = "Поиск списка созданных товаров")
    @PostMapping(path = "/products/my")
    public Flux<ProductDto> findProducts(@RequestHeader("Authorization") String authorizationHeader,
            @RequestBody FindMyProductRequest searchParams) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return productService.findMyByParams(searchParams, participantId);
    }


    @Operation(summary = "Продлить время жизни товара")
    @PostMapping(path = "/products/extend/{id}")
    public Mono<Void> extendExpirationDate(@PathVariable Long id, @RequestHeader("Authorization") String authorizationHeader) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return productService.extendExpirationDate(id, participantId);
    }

    @Operation(summary = "Полная информация о товаре")
    @GetMapping(path = "/product/{id}")
    public Mono<ResponseEntity<GetProductResponse>> getProduct(@PathVariable Long id) {
        return productService.getProductById(id)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Создать товар")
    @PostMapping(path = "/products")
    public Mono<Long> createProduct(@RequestHeader("Authorization") String authorizationHeader, @RequestBody CreateOrUpdateProductRequest request) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        var role = jwtService.getRoleByAccessToken(authorizationHeader);
        return productService.createProduct(request, participantId, role);
    }

    @Operation(summary = "Обновить товар по id")
    @PutMapping(path = "/product/{id}")
    public Mono<Void> updateProduct(@PathVariable Long id, @RequestBody CreateOrUpdateProductRequest request, @RequestHeader("Authorization") String authorizationHeader) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return productService.updateProduct(id, request, participantId);
    }

    @Operation(summary = "Удалить товар")
    @DeleteMapping(path = "/product/{id}")
    public Mono<Void> deleteProduct(@PathVariable Long id, @RequestHeader("Authorization") String authorizationHeader) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return productService.deleteProduct(id, participantId);
    }
}