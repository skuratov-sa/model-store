package com.model_store.modern.catalog.favorite.api

import com.model_store.modern.catalog.favorite.domain.FavoriteAccessDenied
import com.model_store.modern.catalog.favorite.domain.FavoriteTargetNotFound
import com.model_store.modern.shared.api.MvcApiError
import com.model_store.modern.shared.api.mvcApiError
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

typealias FavoriteErrorResponse = MvcApiError

@RestControllerAdvice(assignableTypes = [FavoriteController::class])
class FavoriteErrorHandler {
    @ExceptionHandler(FavoriteTargetNotFound::class)
    fun missing(error: FavoriteTargetNotFound) = response(HttpStatus.NOT_FOUND, "NOT_FOUND", error.message.orEmpty())

    @ExceptionHandler(FavoriteAccessDenied::class)
    fun denied(error: FavoriteAccessDenied) = response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", error.message.orEmpty())

    private fun response(status: HttpStatus, code: String, message: String) = mvcApiError(status, code, message)
}
