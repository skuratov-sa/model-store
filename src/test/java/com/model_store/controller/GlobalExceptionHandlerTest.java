package com.model_store.controller;

import com.model_store.exception.ApiErrors;
import com.model_store.exception.constant.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void openCaseConflictHasSameHttpContractForServiceAndDatabaseFailures() {
        var service = handler.handleApi(ApiErrors.alreadyExist(
                ErrorCode.ORDER_CASE_ALREADY_OPEN, "По заказу уже открыто обращение"));
        var database = handler.handleDbIntegrity(new DuplicateKeyException(
                "duplicate key value violates unique constraint \"uq_open_order_case\""));

        assertThat(service.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(database.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(service.getBody().code()).isEqualTo(ErrorCode.ORDER_CASE_ALREADY_OPEN.name());
        assertThat(database.getBody().code()).isEqualTo(service.getBody().code());
    }
}
