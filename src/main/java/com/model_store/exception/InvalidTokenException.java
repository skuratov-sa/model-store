package com.model_store.exception;

import org.springframework.security.core.AuthenticationException;

public class InvalidTokenException extends AuthenticationException {
    private static final String DEFAULT_MESSAGE = "Token недействителен или срок его действия истек";

    public InvalidTokenException() {
        super(DEFAULT_MESSAGE);
    }
}