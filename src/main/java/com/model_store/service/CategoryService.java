package com.model_store.service;

import com.model_store.model.dto.CategoryDto;
import com.model_store.model.dto.CategoryResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public interface CategoryService {
    Mono<List<CategoryResponse>> getCategories();

    Mono<Long> createCategory(String name, Long parentId);

    Flux<CategoryDto> findByProductId(Long categoryId);

    Mono<Void> updateCategory(Long categoryId, String name);

    Mono<Void> addLinkProductAndCategories(List<Long> categoryIds, Long productId);
}