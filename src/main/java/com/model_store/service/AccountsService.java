package com.model_store.service;

import com.model_store.model.base.Account;
import com.model_store.model.dto.AccountDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AccountsService {
    Mono<Void> create(Long participantId, AccountDto dto);
    Flux<Account> findByParticipantId(Long participantId);
    Mono<Void> delete(Long participantId, Long id);
}