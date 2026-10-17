package com.model_store.modern.catalog.basket.api

import com.model_store.modern.catalog.basket.application.BasketFailure
import com.model_store.modern.shared.api.MvcApiError
import com.model_store.modern.shared.api.mvcApiError
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

typealias BasketErrorResponse = MvcApiError

@RestControllerAdvice(assignableTypes = [ModernBasketController::class])
@Profile("modern")
class BasketErrorHandler {
    @ExceptionHandler(BasketFailure::class)
    fun api(error: BasketFailure) = mvcApiError(error.status, error.code.name, error.message.orEmpty())
}
