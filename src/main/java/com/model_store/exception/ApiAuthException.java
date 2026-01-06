package com.model_store.exception;

import lombok.Getter;

@Getter
public class ApiAuthException extends RuntimeException {
    private final String code;
    private final int httpStatus;

    public ApiAuthException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public static ApiAuthException waitingVerify() {
        return new ApiAuthException("WAITING_VERIFY", "Необходимо подтвердить почту", 403);
    }

    public static ApiAuthException blocked() {
        return new ApiAuthException("ACCOUNT_BLOCKED", "Пользователь заблокирован", 403);
    }

    public static ApiAuthException deleted() {
        return new ApiAuthException("ACCOUNT_DELETED", "Учетная запись была удалена", 403);
    }
}
