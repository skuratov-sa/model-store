package com.model_store.service.impl;

import com.model_store.exception.ApiException;
import com.model_store.exception.constant.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationCodeServiceImplTest {

    private VerificationCodeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VerificationCodeServiceImpl();
    }

    @Test
    void enforceSendLimits_firstRequestPassesImmediately() {
        StepVerifier.create(service.enforceSendLimits(1L))
                .verifyComplete();
    }

    @Test
    void enforceSendLimits_secondImmediateRequestUsesTenSecondCooldown() {
        StepVerifier.create(service.enforceSendLimits(1L))
                .verifyComplete();

        StepVerifier.create(service.enforceSendLimits(1L))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApiException.class);
                    ApiException apiException = (ApiException) error;
                    assertThat(apiException.getCode()).isEqualTo(ErrorCode.VERIFICATION_COOLDOWN);
                    assertThat(apiException.getMessage()).contains("10");
                })
                .verify();
    }
}
