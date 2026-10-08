package com.model_store.modern.catalog.favorite.api

import com.model_store.modern.catalog.favorite.domain.FavoriteAccessDenied
import com.model_store.modern.catalog.favorite.domain.FavoriteTargetNotFound
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.OffsetDateTime

data class FavoriteErrorResponse(
    val code: String, val message: String, val status: Int, val timestamp: String, val details: Any? = null,
)

@RestControllerAdvice(assignableTypes = [FavoriteController::class])
class FavoriteErrorHandler {
    @ExceptionHandler(FavoriteTargetNotFound::class)
    fun missing(error: FavoriteTargetNotFound) = response(HttpStatus.NOT_FOUND, "NOT_FOUND", error.message.orEmpty())

    @ExceptionHandler(FavoriteAccessDenied::class)
    fun denied(error: FavoriteAccessDenied) = response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", error.message.orEmpty())

    private fun response(status: HttpStatus, code: String, message: String) = ResponseEntity.status(status)
        .body(FavoriteErrorResponse(code, message, status.value(), OffsetDateTime.now().toString()))
}
