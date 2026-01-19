package com.model_store.controller;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.support.WebExchangeBindException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(NotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuth(AuthenticationException ex) {
        return switch (ex) {
            case BadCredentialsException ignored ->
                    build(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Неверный логин или пароль", null);
            case DisabledException ignored ->
                    build(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "Учетная запись отключена", null);
            case LockedException ignored ->
                    build(HttpStatus.FORBIDDEN, "ACCOUNT_LOCKED", "Учетная запись заблокирована", null);
            case AccountExpiredException ignored ->
                    build(HttpStatus.FORBIDDEN, "ACCOUNT_EXPIRED", "Срок действия учетной записи истек", null);
            case CredentialsExpiredException ignored ->
                    build(HttpStatus.FORBIDDEN, "CREDENTIALS_EXPIRED", "Срок действия пароля истек", null);
            default ->
                    build(HttpStatus.UNAUTHORIZED, "AUTH_ERROR", "Ошибка аутентификации", Map.of("reason", ex.getMessage()));
        };
    }

    @ExceptionHandler({org.springframework.dao.DuplicateKeyException.class,
            org.springframework.dao.DataIntegrityViolationException.class})
    public ResponseEntity<ApiErrorResponse> handleDbIntegrity(Exception ex) {
        String msg = rootMessage(ex);

        if (msg != null && msg.contains("uq_product_active")) {
            return build(HttpStatus.CONFLICT, "PRODUCT_ALREADY_EXISTS", "Товар с такими параметрами уже существует", null);
        }

        // общий кейс
        return build(HttpStatus.CONFLICT, "DUPLICATE_KEY", "Нарушено ограничение уникальности", Map.of("dbMessage", msg));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, WebExchangeBindException.class})
    public ResponseEntity<ApiErrorResponse> handleValidation(Exception ex) {
        List<String> errors;

        if (ex instanceof MethodArgumentNotValidException manv) {
            errors = manv.getBindingResult().getFieldErrors().stream()
                    .map(f -> f.getField() + ": " + f.getDefaultMessage())
                    .toList();
        } else {
            WebExchangeBindException web = (WebExchangeBindException) ex;
            errors = web.getFieldErrors().stream()
                    .map(f -> f.getField() + ": " + f.getDefaultMessage())
                    .toList();
        }

        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Ошибка валидации", Map.of("errors", errors));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Внутренняя ошибка", null);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApi(ApiException ex) {
        return build(ex.getStatus(), ex.getCode().toString(), ex.getMessage(), ex.getDetails());
    }

    private String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage();
    }

    private ResponseEntity<ApiErrorResponse> build(HttpStatus status, String code, String message, Object details) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                code,
                message,
                status.value(),
                OffsetDateTime.now().toString(),
                details
        ));
    }

    public record ApiErrorResponse(
            String code,
            String message,
            int status,
            String timestamp,
            Object details
    ) { }
}
