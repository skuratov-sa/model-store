package com.model_store.service.impl;

import com.model_store.mapper.CategoryMapper;
import com.model_store.model.dto.CategoryResponse;
import com.model_store.repository.CategoryRepository;
import com.model_store.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryServiceIml implements CategoryService {
    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;

    @Override
    public Mono<List<CategoryResponse>> getCategories() {
        return categoryRepository.findAll()
                .collectList()
                .map(categoryMapper::toCategoryResponse);
    }
}