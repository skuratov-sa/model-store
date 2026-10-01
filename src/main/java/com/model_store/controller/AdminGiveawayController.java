package com.model_store.controller;

import com.model_store.exception.ApiErrors;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.model.GiveawaySettingsRequest;
import com.model_store.model.base.Product;
import com.model_store.model.dto.AdminGiveawayProductResponse;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.service.JwtService;
import com.model_store.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/actions/giveaways")
public class AdminGiveawayController {
    private final ProductService products;
    private final JwtService jwtService;

    @GetMapping("/history")
    public Flux<Product> history(@RequestHeader("Authorization") String authorization,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "50") int size) {
        return Flux.defer(() -> products.findGiveawayHistory(requireAdminId(authorization), page, size));
    }

    @GetMapping("/{productId}")
    public Mono<AdminGiveawayProductResponse> product(@PathVariable Long productId,
                                 @RequestHeader("Authorization") String authorization) {
        return Mono.defer(() -> products.findAdminGiveawayDetails(productId, requireAdminId(authorization)));
    }

    @PutMapping("/{productId}")
    public Mono<Void> update(@PathVariable Long productId,
                             @RequestHeader("Authorization") String authorization,
                             @RequestBody GiveawaySettingsRequest request) {
        return Mono.defer(() -> products.updateAdminGiveaway(productId, request, requireAdminId(authorization)));
    }

    private Long requireAdminId(String authorization) {
        if (jwtService.getRoleByAccessToken(authorization) != ParticipantRole.ADMIN) {
            throw ApiErrors.forbidden(ErrorCode.ACCESS_DENIED, "Управление розыгрышами доступно только администратору");
        }
        return jwtService.getIdByAccessToken(authorization);
    }
}
