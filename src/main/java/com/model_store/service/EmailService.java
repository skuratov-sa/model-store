package com.model_store.service;

import reactor.core.publisher.Mono;

public interface EmailService {
    Mono<Void> sendVerificationCode(Long participantId, String email);
}