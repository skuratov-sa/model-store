package com.model_store.service;

import reactor.core.publisher.Mono;

public interface VerificationCodeService {
    Mono<Void> addCode(Long userId, String code);

    Mono<Void> checkAndConsumeCode(Long userId, String code);

    Mono<Void> enforceSendLimits(Long userId);
}
