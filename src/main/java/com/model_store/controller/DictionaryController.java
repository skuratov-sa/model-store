package com.model_store.controller;

import com.model_store.model.base.Dictionary;
import com.model_store.model.constant.DictionaryType;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.service.DictionaryService;
import com.model_store.service.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequiredArgsConstructor
public class DictionaryController {
    private final DictionaryService dictionaryService;
    private final JwtService jwtService;

    @Operation(summary = "Получить информацию из словаря")
    @GetMapping(path = "/dictionary")
    public Flux<Dictionary> getProduct(@RequestParam DictionaryType type,
                                       @RequestHeader(value = "Authorization", required = false) String authorization) {
        Flux<Dictionary> values = dictionaryService.findAllByType(type);
        if (type != DictionaryType.PRODUCT_AVAILABILITY || isAdmin(authorization)) return values;
        return values.filter(entry -> "PURCHASABLE".equals(entry.getValue()) || "PREORDER".equals(entry.getValue()));
    }

    private boolean isAdmin(String authorization) {
        if (authorization == null) return false;
        try {
            return jwtService.getRoleByAccessToken(authorization) == ParticipantRole.ADMIN;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
