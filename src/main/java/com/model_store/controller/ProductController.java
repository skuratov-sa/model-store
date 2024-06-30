package com.model_store.controller;

import com.model_store.model.CreateOrUpdateProductRequest;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
import com.model_store.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
public class ProductController {
    private final ProductService productService;

    @GetMapping(path = "/product/{id}")
    public Mono<Product> getProduct(@PathVariable Long id) {
        return productService.findById(id);
    }

    @PostMapping(path = "/find/products")
    public Flux<Product> findProducts(@RequestBody FindProductRequest searchParams) {
        return productService.findByParams(searchParams);
    }

    @PostMapping(path = "/product")
    public Mono<Void> createProduct(@RequestBody CreateOrUpdateProductRequest request) {
        return productService.createProduct(request);
    }

    @PutMapping(path = "/product/{id}")
    public Mono<Void> updateProduct(@PathVariable Long id, @RequestBody CreateOrUpdateProductRequest request) {
        return productService.updateProduct(id, request);
    }

    @DeleteMapping(path = "/product/{id}")
    public Mono<Void> deleteProduct(@PathVariable Long id) {
        return productService.deleteProduct(id);
    }


    @PostMapping("/find/favorites/{participantId}")
    public Flux<Product> findFavorites(@PathVariable Long participantId, @RequestBody FindProductRequest searchParams) {
        return productService.findFavoriteByParams(participantId, searchParams);
    }

    @PostMapping("/favorites")
    public Mono<Void> addToFavorites(@RequestParam Long participantId, @RequestParam Long productId) {
        return productService.addToFavorites(participantId, productId);
    }

    @DeleteMapping("/favorites")
    public Mono<Void> removeFromFavorites(@RequestParam Long participantId, @RequestParam Long productId) {
        return productService.removeFromFavorites(participantId, productId);
    }
}