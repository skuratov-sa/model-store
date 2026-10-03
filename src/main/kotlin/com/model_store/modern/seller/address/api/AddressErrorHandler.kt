package com.model_store.modern.seller.address.api

import com.model_store.modern.seller.address.domain.AddressAccessDenied
import com.model_store.modern.seller.address.domain.AddressNotFound
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.OffsetDateTime

data class AddressError(val code: String, val message: String, val status: Int,
                        val timestamp: String, val details: Any? = null)

@RestControllerAdvice(assignableTypes = [AddressController::class])
@Profile("modern")
class AddressErrorHandler {
    @ExceptionHandler(AddressNotFound::class)
    fun notFound(error: AddressNotFound) = response(HttpStatus.NOT_FOUND, "ADDRESS_NOT_FOUND", error.message.orEmpty())

    @ExceptionHandler(AddressAccessDenied::class)
    fun denied(error: AddressAccessDenied) = response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", error.message.orEmpty())

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(error: IllegalArgumentException) = response(HttpStatus.BAD_REQUEST, "BAD_REQUEST", error.message.orEmpty())

    private fun response(status: HttpStatus, code: String, message: String): ResponseEntity<AddressError> =
        ResponseEntity.status(status).body(AddressError(code, message, status.value(), OffsetDateTime.now().toString()))
}
