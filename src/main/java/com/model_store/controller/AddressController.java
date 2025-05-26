package com.model_store.controller;

import com.model_store.model.base.Address;
import com.model_store.model.dto.AddressDto;
import com.model_store.service.AddressService;
import com.model_store.service.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/address")
@RequiredArgsConstructor
public class AddressController {
    private final AddressService service;
    private final JwtService jwtService;

    @Operation(summary = "Получить список всех регионов")
    @GetMapping("/regions")
    public Mono<List<String>> getCategories() {
        return service.getAllRegions();
    }

    @Operation(summary = "Добавить адрес участнику")
    @PostMapping
    public Mono<Long> addAddress(
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestBody AddressDto addressDto) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return service.addAddresses(participantId, addressDto);
    }

    @Operation(summary = "Получить список адресов пользователя")
    @GetMapping
    public Flux<Address> getAddress(@RequestHeader("Authorization") String authorizationHeader) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return service.getAddress(participantId);
    }

    @Operation(summary = "Удалить адрес участника")
    @DeleteMapping("/{addressId}")
    public Mono<Void> deleteAddress(
            @RequestHeader("Authorization") String authorizationHeader,
            @PathVariable Long addressId) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return service.deleteAddresses(participantId, addressId);
    }
}