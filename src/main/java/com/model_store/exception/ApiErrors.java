package com.model_store.exception;

import com.model_store.exception.constant.ErrorCode;
import org.springframework.http.HttpStatus;

public final class ApiErrors {
    public static ApiException notFound(ErrorCode code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException authException(ErrorCode code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    public static ApiException tooManyRequests(ErrorCode code, String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, message);
    }

    public static ApiException alreadyExist(ErrorCode code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }


    public static ApiException badRequest(ErrorCode code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
    public static ApiException forbidden(ErrorCode code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }
}
