package com.model_store.modern.catalog.basket.api

import com.model_store.modern.catalog.basket.application.BasketFailure
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.OffsetDateTime

data class BasketErrorResponse(
    val code: String,
    val message: String,
    val status: Int,
    val timestamp: String,
    val details: Any? = null,
)

@RestControllerAdvice(assignableTypes = [ModernBasketController::class])
@Profile("modern")
class BasketErrorHandler {
    @ExceptionHandler(BasketFailure::class)
    fun api(error: BasketFailure) = ResponseEntity.status(error.status).body(
        BasketErrorResponse(error.code.name, error.message.orEmpty(), error.status.value(), OffsetDateTime.now().toString()),
    )
}
