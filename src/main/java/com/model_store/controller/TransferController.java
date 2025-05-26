package com.model_store.controller;

import com.model_store.model.base.Transfer;
import com.model_store.model.dto.TransferDto;
import com.model_store.service.JwtService;
import com.model_store.service.TransferService;
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

@RestController
@RequestMapping("/transfer")
@RequiredArgsConstructor
public class TransferController {
    private final TransferService transferService;
    private final JwtService jwtService;

    @PostMapping
    public Mono<Void> createSocialNetwork(
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestBody TransferDto dto) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return transferService.create(participantId, dto);
    }

    @GetMapping
    public Flux<Transfer> getSocialNetworksByParticipant(@RequestHeader("Authorization") String authorizationHeader) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return transferService.findByParticipantId(participantId);
    }

    @DeleteMapping("/{id}")
    public Mono<Void> deleteSocialNetwork(
            @RequestHeader("Authorization") String authorizationHeader,
            @PathVariable Long id) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return transferService.delete(participantId, id);
    }
}