package com.model_store.service.impl;

import com.model_store.exception.ApiErrors;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.mapper.TransferMapper;
import com.model_store.model.base.Transfer;
import com.model_store.model.constant.TransferStatus;
import com.model_store.model.dto.TransferDto;
import com.model_store.repository.TransferRepository;
import com.model_store.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
        return transferRepository.existsByParticipantIdAndType(participantId, dto.getSending())
                .flatMap(exists -> exists
                        ? Mono.error(ApiErrors.alreadyExist(ErrorCode.TRANSFER_NOT_FOUND, "Такой способ доставки уже добавлен"))
                        : transferRepository.save(transferMapper.toTransfer(dto, participantId)).then()
                );
    }

    @Override
    public Flux<Transfer> findByParticipantId(Long participantId) {
        return transferRepository.findByParticipantId(participantId)
                .filter(transfer -> TransferStatus.ACTIVE.equals(transfer.getStatus()))
                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.TRANSFER_NOT_FOUND, "Способ доставки не найден")));
    }

    @Override
    @Transactional
    public Mono<Void> softDelete(Long participantId, Long id) {
        return transferRepository.findById(id)
                .filter(t -> t.getParticipantId().equals(participantId))
                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.TRANSFER_NOT_FOUND, "Способ доставки не найден")))
                .flatMap(t -> {
                    t.setStatus(TransferStatus.DELETED);
                    return transferRepository.save(t);
                }).then();
    }

    @Override
    @Transactional
    public Mono<Transfer> update(Long participantId, Long id, TransferDto dto) {
        return transferRepository.findById(id)
                .filter(t -> t.getParticipantId().equals(participantId) && TransferStatus.ACTIVE.equals(t.getStatus()))
                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.TRANSFER_NOT_FOUND, "Способ доставки не найден")))
                .flatMap(t -> {
                            var updateTransfer = transferMapper.toUpdateTransfer(t, dto, participantId);
                            return transferRepository.save(updateTransfer);
                        }
                );
    }
}