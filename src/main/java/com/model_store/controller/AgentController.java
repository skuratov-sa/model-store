package com.model_store.controller;

import com.model_store.exception.ApiErrors;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.model.CreateAgentProductRequest;
import com.model_store.service.JwtService;
import com.model_store.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {
    private final ProductService productService;
    private final JwtService jwtService;

    @Operation(summary = "Создать товар от имени агента (EXTERNAL_ONLY, сразу активен)")
    @PostMapping("/products")
    public Mono<Long> createAgentProduct(
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestBody CreateAgentProductRequest request) {
        if (!jwtService.isAgentToken(authorizationHeader)) {
            return Mono.error(ApiErrors.forbidden(ErrorCode.ACCESS_DENIED, "Доступ только для агентских токенов"));
        }
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return productService.createAgentProduct(request, participantId);
    }
}
