package com.model_store.modern.seller.transfer.api

import com.model_store.modern.seller.transfer.domain.*
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.OffsetDateTime

data class TransferError(val code: String, val message: String, val status: Int, val timestamp: String, val details: Any? = null)

@RestControllerAdvice(assignableTypes = [TransferController::class])
@Profile("modern")
class TransferErrorHandler {
    @ExceptionHandler(TransferNotFound::class)
    fun missing(error: TransferNotFound) = response(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", error.message.orEmpty())

    @ExceptionHandler(TransferAlreadyExists::class)
    fun duplicate(error: TransferAlreadyExists) = response(HttpStatus.CONFLICT, "TRANSFER_NOT_FOUND", error.message.orEmpty())

    @ExceptionHandler(TransferAccessDenied::class)
    fun denied(error: TransferAccessDenied) = response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", error.message.orEmpty())

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(error: IllegalArgumentException) = response(HttpStatus.BAD_REQUEST, "BAD_REQUEST", error.message.orEmpty())

    private fun response(status: HttpStatus, code: String, message: String): ResponseEntity<TransferError> =
        ResponseEntity.status(status).body(TransferError(code, message, status.value(), OffsetDateTime.now().toString()))
}
