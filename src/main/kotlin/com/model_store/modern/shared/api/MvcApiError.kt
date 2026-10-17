package com.model_store.modern.shared.api

import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import java.time.OffsetDateTime

/** The existing five-field error envelope; each controller advice still chooses its own code and status. */
data class MvcApiError(
    val code: String,
    val message: String?,
    val status: Int,
    val timestamp: String,
    val details: Any? = null,
)

fun mvcApiError(
    status: HttpStatusCode,
    code: String,
    message: String?,
    details: Any? = null,
): ResponseEntity<MvcApiError> = ResponseEntity.status(status)
    .body(MvcApiError(code, message, status.value(), OffsetDateTime.now().toString(), details))
