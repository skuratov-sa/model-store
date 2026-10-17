package com.model_store.modern.shared.api

import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** Matches the legacy input errors without intercepting domain failures or replacing local advice. */
@RestControllerAdvice(basePackages = ["com.model_store.modern"])
@Profile("modern")
class ModernMvcInputErrorHandler {
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(): ResponseEntity<MvcApiError> =
        mvcApiError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Failed to read HTTP message")

    @ExceptionHandler(MissingServletRequestPartException::class)
    fun missingPart(error: MissingServletRequestPartException): ResponseEntity<MvcApiError> =
        mvcApiError(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
            "Required request part '${error.requestPartName}' is not present.")

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingQuery(error: MissingServletRequestParameterException): ResponseEntity<MvcApiError> =
        mvcApiError(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
            "Required query parameter '${error.parameterName}' is not present.")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(): ResponseEntity<MvcApiError> =
        mvcApiError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Type mismatch.")
}
