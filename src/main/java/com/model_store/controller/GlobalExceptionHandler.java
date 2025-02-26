package com.model_store.controller;

import com.amazonaws.services.kms.model.NotFoundException;
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
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.support.WebExchangeBindException;

import java.util.List;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Object> handleNotFoundException(NotFoundException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleGenericException(Exception ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
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

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<Object> handleConstraintViolationException(WebExchangeBindException ex) {
        List<String> errorMessages = ex.getFieldErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage) // Получаем сообщение об ошибке
                .toList();

        return new ResponseEntity<>(errorMessages, HttpStatus.BAD_REQUEST);
    }
}