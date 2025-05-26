package com.model_store.service.impl;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.exeption.ParticipantNotFoundException;
import com.model_store.mapper.AccountMapper;
import com.model_store.model.base.Account;
import com.model_store.model.dto.AccountDto;
import com.model_store.repository.AccountRepository;
import com.model_store.service.AccountsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class AccountsServiceImpl implements AccountsService {
    private final AccountRepository accountRepository;
    private final AccountMapper accountMapper;

    @Override
    public Mono<Void> create(Long participantId, AccountDto dto) {
        return accountRepository.findByParticipantId(participantId).
                map(Account::getEntityValue).collectList()
                .flatMap(accounts -> {
                    if (accounts.contains(dto.getEntityValue())) return Mono.error(new RuntimeException("Способ оплаты с такой категории уже был загружен"));
                    else return accountRepository.save(accountMapper.toAccount(dto, participantId));
                }).then();
    }

    @Override
    public Flux<Account> findByParticipantId(Long participantId) {
        return accountRepository.findByParticipantId(participantId)
                .switchIfEmpty(Mono.error(new RuntimeException("Не удалось найти способы оплаты для данного пользователя")));
    }

    @Override
    public Mono<Void> delete(Long participantId, Long id) {
        return accountRepository.findById(id)
                .filter(s -> s.getParticipantId().equals(participantId))
                .switchIfEmpty(Mono.error(new NotFoundException("Не удалось найти способ оплаты")))
                .flatMap(accountRepository::delete);
    }
}