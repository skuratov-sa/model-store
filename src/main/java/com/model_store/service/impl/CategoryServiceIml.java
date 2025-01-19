package com.model_store.service.impl;

import com.model_store.mapper.CategoryMapper;
import com.model_store.model.base.Category;
import com.model_store.model.dto.CategoryDto;
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

    @Override
    public Mono<Long> createCategory(String name, Long parentId) {
        return categoryRepository.save(Category.builder().name(name).parentId(parentId).build())
                .map(Category::getId);
    }

    @Override
    public Mono<CategoryDto> findById(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .map(categoryMapper::toCategoryDto);
    }
}