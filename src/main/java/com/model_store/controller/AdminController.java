package com.model_store.controller;

import com.model_store.model.IssueAgentTokensResponse;
import com.model_store.model.constant.ParticipantStatus;
import com.model_store.model.constant.ProductStatus;
import com.model_store.model.dto.OrderCaseDetail;
import com.model_store.service.AgentTokenService;
import com.model_store.service.CategoryService;
import com.model_store.service.ParticipantService;
import com.model_store.service.ProductService;
import com.model_store.service.JwtService;
import com.model_store.service.impl.OrderCaseService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
@RestController
@RequestMapping("/admin/actions")
@RequiredArgsConstructor
public class AdminController {
    private final ParticipantService participantService;
    private final CategoryService categoryService;
    private final ProductService productService;
    private final AgentTokenService agentTokenService;
    private final OrderCaseService orderCaseService;
    private final JwtService jwtService;

    @Operation(summary = "Открытые или завершённые обращения по заказам")
    @GetMapping("/order-cases")
    public Flux<OrderCaseDetail> listOrderCases(@RequestParam(defaultValue = "OPEN") String state,
                                                 @RequestParam(defaultValue = "50") int limit,
                                                 @RequestParam(defaultValue = "0") long offset) {
        return orderCaseService.list(state, limit, offset);
    }

    @Operation(summary = "Детали обращения")
    @GetMapping("/order-cases/{caseId}")
    public Mono<OrderCaseDetail> getOrderCase(@PathVariable Long caseId) {
        return orderCaseService.adminDetail(caseId);
    }

    @Operation(summary = "Указать ссылку на обсуждение спора в Telegram")
    @PutMapping("/order-cases/{caseId}/telegram-url")
    public Mono<Long> updateOrderCaseUrl(@RequestHeader("Authorization") String authorizationHeader,
                                         @PathVariable Long caseId, @RequestParam String url) {
        return orderCaseService.changeTelegramUrl(caseId, jwtService.getIdByAccessToken(authorizationHeader), url);
    }

    @Operation(summary = "Завершить спор или обращение")
    @PostMapping("/order-cases/{caseId}/resolve")
    public Mono<Long> resolveOrderCase(@RequestHeader("Authorization") String authorizationHeader,
                                        @PathVariable Long caseId, @RequestParam String outcome,
                                        @RequestParam String comment) {
        return orderCaseService.resolve(caseId, jwtService.getIdByAccessToken(authorizationHeader), outcome, comment);
    }

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
