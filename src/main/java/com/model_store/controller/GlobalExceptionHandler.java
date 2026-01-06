package com.model_store.controller;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.exception.ApiAuthException;
import com.model_store.exception.TooManyRequestsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Object> handleNotFoundException(NotFoundException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleGenericException(Exception ex) {
        log.error("Unhandled exception", ex);
        HttpStatus status = (ex instanceof RuntimeException) ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        Map<String, Object> body = Map.of(
                "error", ex.getMessage(),
                "status", status.value(),
                "timestamp", OffsetDateTime.now().toString()
        );

        return new ResponseEntity<>(body, status);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthException(AuthenticationException ex) {
        log.warn("Ошибка при авторизации: {}", ex.getLocalizedMessage());

        return switch (ex) {
            case BadCredentialsException ignored ->
                    new ResponseEntity<>("Ошибка: Неверный логин или пароль", HttpStatus.UNAUTHORIZED);
            case DisabledException ignored ->
                    new ResponseEntity<>("Ошибка: Учетная запись отключена", HttpStatus.FORBIDDEN);
            case LockedException ignored ->
                    new ResponseEntity<>("Ошибка: Учетная запись заблокирована", HttpStatus.FORBIDDEN);
            case AccountExpiredException ignored ->
                    new ResponseEntity<>("Ошибка: Срок действия учетной записи истек", HttpStatus.FORBIDDEN);
            case CredentialsExpiredException ignored ->
                    new ResponseEntity<>("Ошибка: Срок действия пароля истек", HttpStatus.FORBIDDEN);
            default -> new ResponseEntity<>("Ошибка аутентификации: " + ex.getMessage(), HttpStatus.UNAUTHORIZED);
        };
    }

    @ExceptionHandler(ApiAuthException.class)
    public ResponseEntity<Map<String, Object>> handle(ApiAuthException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", ex.getCode());
        body.put("message", ex.getMessage());

        if ("WAITING_VERIFY".equals(ex.getCode())) {
            body.put("next", "VERIFY_EMAIL");
        }

        return ResponseEntity.status(ex.getHttpStatus()).body(body);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<Map<String, Object>> handle(TooManyRequestsException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", ex.getCode());
        body.put("message", "Слишком много запросов");
        if (ex.getRetryAfterSec() >= 0) body.put("retryAfterSec", ex.getRetryAfterSec());

        return ResponseEntity.status(429).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<Map<String, Object>> handleValidationErrors(MethodArgumentNotValidException ex) {
        log.warn("Ошибка при валидации запроса: {}", ex.getMessage());

        Map<String, Object> error = new HashMap<>();
        error.put("error", "Ошибка валидации");
        error.put("Описание", ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .toList());

        return Mono.just(error);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<Object> handleConstraintViolationException(WebExchangeBindException ex) {
        List<String> errorMessages = ex.getFieldErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage) // Получаем сообщение об ошибке
                .toList();

        return new ResponseEntity<>(errorMessages, HttpStatus.BAD_REQUEST);
    }
}