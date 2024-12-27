package com.model_store.service;

import com.model_store.model.dto.CategoryResponse;
import reactor.core.publisher.Mono;

import java.util.List;

public interface CategoryService {
    Mono<List<CategoryResponse>> getCategories();
}