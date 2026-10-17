package com.model_store.modern.seller.account.api

import com.model_store.modern.seller.account.domain.*
import com.model_store.modern.shared.api.MvcApiError
import com.model_store.modern.shared.api.mvcApiError
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

typealias AccountErrorResponse = MvcApiError

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

    private fun response(status: HttpStatus, code: String, message: String) = mvcApiError(status, code, message)
}
