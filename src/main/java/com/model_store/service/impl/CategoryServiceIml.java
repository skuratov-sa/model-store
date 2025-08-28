package com.model_store.service.impl;

import com.model_store.mapper.CategoryMapper;
import com.model_store.model.base.Category;
import com.model_store.model.base.ProductCategory;
import com.model_store.model.dto.CategoryDto;
import com.model_store.model.dto.CategoryResponse;
import com.model_store.repository.CategoryRepository;
import com.model_store.repository.ProductCategoryRepository;
import com.model_store.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryServiceIml implements CategoryService {
    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;
    private final ProductCategoryRepository productCategoryRepository;

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
    public Flux<CategoryDto> findByProductId(Long productId) {
        return productCategoryRepository.findByProductId(productId)
                .map(ProductCategory::getCategoryId)
                .flatMap(categoryRepository::findById)
                .map(categoryMapper::toCategoryDto);
    }

    @Override
    public Mono<Void> updateCategory(Long categoryId, String name) {
        return categoryRepository.findById(categoryId)
                .doOnNext(category -> category.setName(name))
                .flatMap(categoryRepository::save)
                .then();
    }

    @Override
    public Mono<Void> addLinkProductAndCategories(List<Long> categoryIds, Long productId) {
        return Flux.fromIterable(categoryIds)
                .map(categoryId -> new ProductCategory(productId, categoryId))
                .flatMap(productCategoryRepository::save)
                .then();
    }
}