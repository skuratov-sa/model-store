package com.model_store.service;

import com.model_store.model.base.Transfer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TransferService {
    Mono<Transfer> findById(Long id);
    Flux<Transfer> findByParticipantId(Long id);
}