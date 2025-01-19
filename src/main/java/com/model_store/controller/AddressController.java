package com.model_store.controller;

import com.model_store.service.AddressService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class AddressController {
    private final AddressService service;

    @Operation(summary = "Получить список всех регионов")
    @GetMapping("/regions")
    public Mono<List<String>> getCategories() {
        return service.getAllRegions();
    }
}