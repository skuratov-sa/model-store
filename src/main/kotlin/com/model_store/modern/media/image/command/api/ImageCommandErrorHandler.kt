package com.model_store.modern.media.image.command.api

import com.model_store.modern.media.image.command.domain.*
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.OffsetDateTime

data class ImageCommandError(val code: String, val message: String, val status: Int, val timestamp: String)

@RestControllerAdvice(assignableTypes = [ImageCommandController::class])
@Profile("modern")
class ImageCommandErrorHandler {
    @ExceptionHandler(ImageCommandDenied::class)
    fun denied(ex: ImageCommandDenied) = error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", ex.message.orEmpty())

    @ExceptionHandler(ImageCommandNotFound::class)
    fun notFound(ex: ImageCommandNotFound) = error(HttpStatus.NOT_FOUND, "IMAGE_NOT_FOUND", ex.message.orEmpty())

    @ExceptionHandler(InvalidImage::class)
    fun invalid(ex: InvalidImage) = error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.message.orEmpty())

    @ExceptionHandler(ImageLimitExceeded::class)
    fun limit(ex: ImageLimitExceeded) = error(HttpStatus.CONTENT_TOO_LARGE, "BAD_REQUEST", ex.message.orEmpty())

    @ExceptionHandler(ImageCompensationFailed::class)
    fun compensation(ex: ImageCompensationFailed) = error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", ex.message.orEmpty())

    private fun error(status: HttpStatus, code: String, message: String) =
        ResponseEntity.status(status).body(ImageCommandError(code, message, status.value(), OffsetDateTime.now().toString()))
}
