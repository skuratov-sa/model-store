package com.model_store.controller;

import com.model_store.exception.ApiErrors;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.model.CustomUserDetails;
import com.model_store.model.IssueTokenResponse;
import com.model_store.model.constant.ParticipantStatus;
import com.model_store.model.constant.ProductStatus;
import com.model_store.service.CategoryService;
import com.model_store.service.JwtService;
import com.model_store.service.ParticipantService;
import com.model_store.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
@RestController
@RequestMapping("/admin/actions")
@RequiredArgsConstructor
public class AdminController {
    private final ParticipantService participantService;
    private final ReactiveUserDetailsService userDetailsService;
    private final CategoryService categoryService;
    private final ProductService productService;
    private final JwtService jwtService;

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

    @Operation(summary = "Создать токен для агента (на год)")
    @PostMapping("/agents/{participantId}/token")
    public Mono<IssueTokenResponse> issueAgentToken(@PathVariable Long participantId) {
        return participantService.findActualById(participantId)
                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.PARTICIPANT_NOT_FOUND, "Пользователь не найден")))
                .flatMap(p -> userDetailsService.findByUsername(p.getMail()))
                .map(userDetails -> {
                    var lifetime = java.time.Period.ofDays(365);
                    String token = jwtService.generateAgentToken((CustomUserDetails) userDetails, lifetime); // см. ниже
                    Instant expiresAt = LocalDateTime.now().plus(lifetime).atZone(ZoneId.systemDefault()).toInstant();
                    return IssueTokenResponse.builder().token(token).expiresAt(expiresAt).build();
                });
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
