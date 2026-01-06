package com.model_store.service;

import reactor.core.publisher.Mono;

public interface EmailService {
    Mono<Long> sendVerificationCode(String email);

    Mono<Long> sendVerificationWithoutLimitCode(String email);

    Mono<Void> sendPasswordReset(String email);
}