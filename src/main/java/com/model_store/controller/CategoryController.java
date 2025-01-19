package com.model_store.controller;

import com.model_store.model.dto.CategoryResponse;
import com.model_store.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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
    @PostMapping("/categories")
    public Mono<Long> createCategory(@RequestParam String name, @RequestParam Long parentId) {
        return service.createCategory(name, parentId);
    }
}