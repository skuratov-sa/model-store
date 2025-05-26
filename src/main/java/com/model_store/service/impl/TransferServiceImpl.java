package com.model_store.service.impl;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.mapper.TransferMapper;
import com.model_store.model.base.Transfer;
import com.model_store.model.dto.TransferDto;
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
    private final TransferMapper transferMapper;

    @Override
    public Mono<Transfer> findById(Long id) {
        return transferRepository.findById(id);
    }

    @Override
    public Mono<Void> create(Long participantId, TransferDto dto) {
        return transferRepository.findByParticipantId(participantId)
                .map(Transfer::getSending).collectList()
                .flatMap(shippingMethodsTypes -> {
                    if (shippingMethodsTypes.contains(dto.getSending())) return Mono.empty();
                    else return transferRepository.save(transferMapper.toTransfer(dto, participantId));
                }).then();
    }

    @Override
    public Flux<Transfer> findByParticipantId(Long participantId) {
        return transferRepository.findByParticipantId(participantId)
                .switchIfEmpty(Mono.error(new RuntimeException("Не удалось найти указанные способы доставки для пользователя")));
    }

    @Override
    public Mono<Void> delete(Long participantId, Long id) {
        return transferRepository.findById(id)
                .filter(s -> s.getParticipantId().equals(participantId))
                .switchIfEmpty(Mono.error(new RuntimeException("Не удалось найти данный способ доставки")))
                .flatMap(transferRepository::delete);
    }
}