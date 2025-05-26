package com.model_store.service;

import reactor.core.publisher.Mono;

public interface VerificationCodeService {
    Mono<Void> addCode(Long userId, String code);

    Mono<Void> verifyCode(Long userId, String code);
}
