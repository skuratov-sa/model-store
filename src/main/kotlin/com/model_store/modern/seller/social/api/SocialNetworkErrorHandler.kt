package com.model_store.modern.seller.social.api

import com.model_store.modern.seller.social.domain.*
import com.model_store.modern.shared.api.MvcApiError
import com.model_store.modern.shared.api.mvcApiError
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

typealias SocialNetworkError = MvcApiError

@RestControllerAdvice(assignableTypes = [SocialNetworkController::class])
@Profile("modern")
class SocialNetworkErrorHandler {
    @ExceptionHandler(SocialNetworkNotFound::class)
    fun missing(error: SocialNetworkNotFound) = response(HttpStatus.NOT_FOUND, "SOCIAL_NETWORK_NOT_FOUND", error.message.orEmpty())

    @ExceptionHandler(SocialNetworkAlreadyExists::class)
    fun duplicate(error: SocialNetworkAlreadyExists) = response(HttpStatus.CONFLICT, "SOCIAL_NETWORK_ALREADY_EXISTS", error.message.orEmpty())

    @ExceptionHandler(SocialNetworkAccessDenied::class)
    fun denied(error: SocialNetworkAccessDenied) = response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", error.message.orEmpty())

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(error: IllegalArgumentException) = response(HttpStatus.BAD_REQUEST, "BAD_REQUEST", error.message.orEmpty())

    private fun response(status: HttpStatus, code: String, message: String): ResponseEntity<SocialNetworkError> =
        mvcApiError(status, code, message)
}
