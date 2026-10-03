package com.model_store.modern.seller.account.api

import com.model_store.modern.seller.account.domain.*
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.OffsetDateTime

data class AccountErrorResponse(
    val code: String, val message: String, val status: Int, val timestamp: String, val details: Any? = null,
)

@RestControllerAdvice(assignableTypes = [ModernAccountController::class])
@Profile("modern")
class AccountErrorHandler {
    @ExceptionHandler(AccountNotFound::class)
    fun missing(error: AccountNotFound) = response(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", error.message.orEmpty())

    @ExceptionHandler(AccountAlreadyExists::class)
    fun duplicate(error: AccountAlreadyExists) = response(HttpStatus.CONFLICT, "ACCOUNT_ALREADY_EXISTS", error.message.orEmpty())

    @ExceptionHandler(AccountAccessDenied::class)
    fun denied(error: AccountAccessDenied) = response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", error.message.orEmpty())

    @ExceptionHandler(AgentAccountDenied::class)
    fun agentDenied(): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.FORBIDDEN).build()

    private fun response(status: HttpStatus, code: String, message: String) = ResponseEntity.status(status)
        .body(AccountErrorResponse(code, message, status.value(), OffsetDateTime.now().toString()))
}
