package com.model_store.service.impl;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.mapper.AccountMapper;
import com.model_store.model.base.Account;
import com.model_store.model.dto.AccountDto;
import com.model_store.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountsServiceImplTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountMapper accountMapper;

    @InjectMocks
    private AccountsServiceImpl accountsService;

    private AccountDto accountDto;
    private Account account;

    @BeforeEach
    void setUp() {
        accountDto =  AccountDto.builder().entityValue("test_value").build();

        account = new Account();
        account.setId(1L);
        account.setParticipantId(1L);
        account.setEntityValue("test_value");
    }

    @Test
    void create_shouldSucceed_whenAccountDoesNotExist() {
        when(accountRepository.findByParticipantId(1L)).thenReturn(Flux.empty());
        when(accountMapper.toAccount(accountDto, 1L)).thenReturn(account);
        when(accountRepository.save(account)).thenReturn(Mono.just(account));

        StepVerifier.create(accountsService.create(1L, accountDto))
                .verifyComplete();

        verify(accountRepository).findByParticipantId(1L);
        verify(accountMapper).toAccount(accountDto, 1L);
        verify(accountRepository).save(account);
    }

    @Test
    void create_shouldFail_whenAccountWithSameEntityValueExists() {
        when(accountRepository.findByParticipantId(1L)).thenReturn(Flux.just(account));

        StepVerifier.create(accountsService.create(1L, accountDto))
                .expectError(RuntimeException.class)
                .verify();

        verify(accountRepository).findByParticipantId(1L);
    }

    @Test
    void findByParticipantId_shouldReturnAccounts_whenParticipantExists() {
        when(accountRepository.findByParticipantId(1L)).thenReturn(Flux.just(account));

        StepVerifier.create(accountsService.findByParticipantId(1L))
                .expectNext(account)
                .verifyComplete();

        verify(accountRepository).findByParticipantId(1L);
    }

    @Test
    void findByParticipantId_shouldFail_whenParticipantDoesNotExist() {
        when(accountRepository.findByParticipantId(1L)).thenReturn(Flux.empty());

        StepVerifier.create(accountsService.findByParticipantId(1L))
                .expectError(RuntimeException.class)
                .verify();

        verify(accountRepository).findByParticipantId(1L);
    }

    @Test
    void delete_shouldSucceed_whenAccountExistsAndBelongsToParticipant() {
        when(accountRepository.findById(1L)).thenReturn(Mono.just(account));
        when(accountRepository.delete(account)).thenReturn(Mono.empty());

        StepVerifier.create(accountsService.delete(1L, 1L))
                .verifyComplete();

        verify(accountRepository).findById(1L);
        verify(accountRepository).delete(account);
    }

    @Test
    void delete_shouldFail_whenAccountDoesNotExist() {
        when(accountRepository.findById(1L)).thenReturn(Mono.empty());

        StepVerifier.create(accountsService.delete(1L, 1L))
                .expectError(NotFoundException.class)
                .verify();

        verify(accountRepository).findById(1L);
    }

    @Test
    void delete_shouldFail_whenAccountDoesNotBelongToParticipant() {
        Account otherAccount = new Account();
        otherAccount.setId(1L);
        otherAccount.setParticipantId(2L); // Different participant ID
        otherAccount.setEntityValue("test_value");

        when(accountRepository.findById(1L)).thenReturn(Mono.just(otherAccount));

        StepVerifier.create(accountsService.delete(1L, 1L))
                .expectError(NotFoundException.class)
                .verify();

        verify(accountRepository).findById(1L);
    }
}
