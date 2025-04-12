package com.model_store.service.impl;

import com.model_store.model.base.Transfer;
import com.model_store.repository.TransferRepository;
import com.model_store.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class TransferServiceImpl implements TransferService {
    private final TransferRepository transferRepository;

    @Override
    public Mono<Transfer> findById(Long id) {
        return transferRepository.findById(id);
    }

    @Override
    public Flux<Transfer> findByParticipantId(Long participantId) {
        return transferRepository.findByParticipantId(participantId);
    }
}