package com.model_store.service.impl;

import com.model_store.exception.ApiErrors;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.mapper.AccountMapper;
import com.model_store.model.base.Account;
import com.model_store.model.dto.AccountDto;
import com.model_store.repository.AccountRepository;
import com.model_store.service.AccountsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static com.model_store.exception.constant.ErrorCode.ACCOUNT_ALREADY_EXISTS;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountsServiceImpl implements AccountsService {
    private final AccountRepository accountRepository;
    private final AccountMapper accountMapper;

    @Override
    public Mono<Void> create(Long participantId, AccountDto dto) {
        log.info("Creating account: participantId={}, type={}", participantId, dto.getEntityValue());
        return accountRepository.findByParticipantId(participantId).
                map(Account::getEntityValue).collectList()
                .flatMap(accounts -> {
                    if (accounts.contains(dto.getEntityValue())) {
                        return Mono.error(
                                ApiErrors.alreadyExist(ACCOUNT_ALREADY_EXISTS, "Способ оплаты с такой категории уже был загружен")
                        );
                    } else return accountRepository.save(accountMapper.toAccount(dto, participantId));
                }).then();
    }

    @Override
    public Mono<Account> update(Long participantId, Long id, AccountDto dto) {
        return accountRepository.findById(id)
                .filter(s -> s.getParticipantId().equals(participantId))
                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.ACCOUNT_NOT_FOUND, "Не удалось найти способы оплаты для данного пользователя")))
                .map(account -> accountMapper.toAccount(account.getId(), dto, participantId))
                .flatMap(accountRepository::save);
    }

    @Override
    public Flux<Account> findByParticipantId(Long participantId) {
        return accountRepository.findByParticipantId(participantId)
                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.ACCOUNT_NOT_FOUND, "Не удалось найти способы оплаты для данного пользователя")));
    }

    @Override
    public Mono<Void> delete(Long participantId, Long id) {
        log.info("Deleting account: id={}, participantId={}", id, participantId);
        return accountRepository.findById(id)
                .filter(s -> s.getParticipantId().equals(participantId))
                .switchIfEmpty(Mono.error(ApiErrors.notFound(ErrorCode.ACCOUNT_NOT_FOUND, "Не удалось найти способы оплаты для данного пользователя")))
                .flatMap(accountRepository::delete);
    }
}