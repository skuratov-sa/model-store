package com.model_store.service;

import com.model_store.model.base.Transfer;
import com.model_store.model.dto.TransferDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TransferService {
    Mono<Transfer> findById(Long id);
    Mono<Void> create(Long participantId, TransferDto dto);
    Flux<Transfer> findByParticipantId(Long id);
    Mono<Void> delete(Long participantId, Long id);
}