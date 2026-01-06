package com.model_store.exception;

public class TooManyRequestsException extends RuntimeException {
    private final String code;
    private final int retryAfterSec; // -1 если не применимо

    public TooManyRequestsException(String code, int retryAfterSec) {
        super(code);
        this.code = code;
        this.retryAfterSec = retryAfterSec;
    }

    public String getCode() { return code; }
    public int getRetryAfterSec() { return retryAfterSec; }
}
