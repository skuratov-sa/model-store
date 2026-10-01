package com.model_store.controller;

import com.model_store.model.dto.GiveawayResponse;
import com.model_store.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
public class GiveawayController {
    private final ProductService products;

    @GetMapping("/giveaways/active")
    public Mono<ResponseEntity<GiveawayResponse>> active() {
        return products.findActiveGiveaway()
                .map(giveaway -> ResponseEntity.ok().header("X-Robots-Tag", "noindex, nofollow").body(giveaway))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/giveaways/products/{productId}")
    public Mono<ResponseEntity<GiveawayResponse>> product(@PathVariable Long productId) {
        return products.findPublicGiveawayById(productId)
                .map(giveaway -> ResponseEntity.ok().header("X-Robots-Tag", "noindex, nofollow").body(giveaway))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
