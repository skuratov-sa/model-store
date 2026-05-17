package com.model_store.controller;

import com.model_store.model.IssueAgentTokensResponse;
import com.model_store.model.constant.ParticipantStatus;
import com.model_store.model.constant.ProductStatus;
import com.model_store.service.AgentTokenService;
import com.model_store.service.CategoryService;
import com.model_store.service.ParticipantService;
import com.model_store.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
@RestController
@RequestMapping("/admin/actions")
@RequiredArgsConstructor
public class AdminController {
    private final ParticipantService participantService;
    private final CategoryService categoryService;
    private final ProductService productService;
    private final AgentTokenService agentTokenService;

    /**
     * Товар
     */
    @Operation(summary = "Обновить статус товара")
    @PutMapping(path = "/product/{id}")
    public Mono<Void> updateProduct(@PathVariable Long id, @RequestParam ProductStatus productStatus) {
        return productService.updateProductStatus(id, productStatus);
    }

    /**
     * Пользователь
     */
    @Operation(summary = "Изменить статус пользователя")
    @PutMapping("/participants/{participantId}/status")
    public Mono<Void> updateParticipantStatus(@PathVariable Long participantId) {
        return participantService.updateParticipantStatus(participantId, ParticipantStatus.BLOCKED);
    }

    @Operation(summary = "Создать пару токенов для агента")
    @PostMapping("/agents/{participantId}/token")
    public Mono<IssueAgentTokensResponse> issueAgentToken(@PathVariable Long participantId,
                                                           @RequestParam(defaultValue = "30") Integer accessTokenTtlMinutes,
                                                           @RequestParam(defaultValue = "90") Integer refreshTokenTtlDays) {
        return agentTokenService.issueAgentTokens(participantId, accessTokenTtlMinutes, refreshTokenTtlDays);
    }
    /**
     * Категории
     */
    @Operation(summary = "Создать категорию")
    @PostMapping("/categories")
    public Mono<Long> createCategory(@RequestParam String name, @RequestParam(required = false) Long parentId) {
        return categoryService.createCategory(name, parentId);
    }

    @Operation(summary = "Обновить название категории")
    @PutMapping("/categories")
    public Mono<Void> updateCategory(@RequestParam Long categoryId, @RequestParam String name) {
        return categoryService.updateCategory(categoryId, name);
    }
}
