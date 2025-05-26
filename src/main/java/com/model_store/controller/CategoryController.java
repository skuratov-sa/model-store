package com.model_store.controller;

import com.model_store.model.dto.CategoryResponse;
import com.model_store.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class CategoryController {
    private final CategoryService service;

    @Operation(summary = "Получить все категории")
    @GetMapping("/categories")
    public Mono<List<CategoryResponse>> getCategories() {
        return service.getCategories();
    }

    @Operation(summary = "Создать категорию")
    @PostMapping("/admin/actions/categories")
    public Mono<Long> createCategory(@RequestParam String name, @RequestParam(required = false) Long parentId) {
        return service.createCategory(name, parentId);
    }

    @Operation(summary = "Обновить название категории")
    @PutMapping("/admin/actions/categories")
    public Mono<Void> updateCategory(@RequestParam Long categoryId, @RequestParam String name) {
        return service.updateCategory(categoryId, name);
    }
}