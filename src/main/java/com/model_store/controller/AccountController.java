package com.model_store.controller;

import com.model_store.model.base.Account;
import com.model_store.model.dto.AccountDto;
import com.model_store.model.dto.SocialNetworkDto;
import com.model_store.service.AccountsService;
import com.model_store.service.JwtService;
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
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {
    private final AccountsService accountsService;
    private final JwtService jwtService;

    @PostMapping
    public Mono<Void> createAccount(
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestBody AccountDto dto) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return accountsService.create(participantId, dto);
    }

    @GetMapping
    public Flux<Account> getAccounts(@RequestHeader("Authorization") String authorizationHeader) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return accountsService.findByParticipantId(participantId);
    }

    @DeleteMapping("/{id}")
    public Mono<Void> deleteAccount(
            @RequestHeader("Authorization") String authorizationHeader,
            @PathVariable Long id) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return accountsService.delete(participantId, id);
    }
}