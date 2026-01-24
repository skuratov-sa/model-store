package com.model_store.controller;

import com.model_store.model.base.SocialNetwork;
import com.model_store.model.dto.SocialNetworkDto;
import com.model_store.service.JwtService;
import com.model_store.service.SocialNetworksService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/social-networks")
@RequiredArgsConstructor
public class SocialNetworkController {
    private final SocialNetworksService socialNetworksService;
    private final JwtService jwtService;

    @Operation(summary = "Получить список социальных сетей")
    @GetMapping
    public Flux<SocialNetwork> getSocialNetworksByParticipant(@RequestHeader("Authorization") String authorizationHeader) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return socialNetworksService.findByParticipantId(participantId);
    }

    @Operation(summary = "Создать новую социальную сеть")
    @PostMapping
    public Mono<Void> createSocialNetwork(
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestBody SocialNetworkDto dto) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return socialNetworksService.create(participantId, dto);
    }

    @Operation(summary = "Обновить социальную сеть")
    @PutMapping("/{id}")
    public Mono<SocialNetwork> updateSocialNetwork(
            @RequestHeader("Authorization") String authorizationHeader,
            @PathVariable Long id,
            @RequestBody SocialNetworkDto dto) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return socialNetworksService.update(participantId, id, dto);
    }

    @Operation(summary = "Удалить социальную сеть")
    @DeleteMapping("/{id}")
    public Mono<Void> deleteSocialNetwork(
            @RequestHeader("Authorization") String authorizationHeader,
            @PathVariable Long id) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return socialNetworksService.delete(participantId, id);
    }
}