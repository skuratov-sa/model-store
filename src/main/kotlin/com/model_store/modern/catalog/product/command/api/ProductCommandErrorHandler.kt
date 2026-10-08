package com.model_store.modern.catalog.product.command.api

import com.model_store.modern.catalog.product.command.application.*
import org.springframework.context.annotation.Profile
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.OffsetDateTime

data class ProductCommandError(val code: String, val message: String, val status: Int,
                               val timestamp: String, val details: Any? = null)

@RestControllerAdvice(assignableTypes = [ProductCommandController::class, AdminProductStatusController::class])
@Profile("modern")
class ProductCommandErrorHandler {
    @ExceptionHandler(ProductCommandFailure::class)
    fun failure(error: ProductCommandFailure): ResponseEntity<ProductCommandError> {
        val status = when (error.kind) {
            FailureKind.BAD_REQUEST -> HttpStatus.BAD_REQUEST
            FailureKind.FORBIDDEN -> HttpStatus.FORBIDDEN
            FailureKind.NOT_FOUND -> HttpStatus.NOT_FOUND
            FailureKind.CONFLICT -> HttpStatus.CONFLICT
        }
        return response(status, error.code, error.message.orEmpty())
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrity(error: DataIntegrityViolationException): ResponseEntity<ProductCommandError> {
        var root: Throwable = error
        while (root.cause != null) root = root.cause!!
        val message = root.message.orEmpty()
        if ("uq_product_one_client" in message)
            return response(HttpStatus.CONFLICT, "PRODUCT_ALREADY_EXISTS", "Товар с такими параметрами уже существует")
        if ("foreign key constraint" in message)
            return response(HttpStatus.BAD_REQUEST, "INVALID_REFERENCE", "Указана ссылка на несуществующий объект",
                mapOf("dbMessage" to message))
        return response(HttpStatus.CONFLICT, "DUPLICATE_KEY", "Нарушено ограничение уникальности",
            mapOf("dbMessage" to message))
    }

    private fun response(status: HttpStatus, code: String, message: String, details: Any? = null) =
        ResponseEntity.status(status)
            .body(ProductCommandError(code, message, status.value(), OffsetDateTime.now().toString(), details))
}
