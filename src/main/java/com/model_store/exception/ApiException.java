package com.model_store.exception;

import com.model_store.exception.constant.ErrorCode;
import lombok.Data;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {
    private final ErrorCode code;
    private final HttpStatus status;
    private final Object details;

    public ApiException(HttpStatus status, ErrorCode code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, ErrorCode code, String message, Object details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details;
    }
}
