package com.model_store.modern.identity.agent.api

import com.model_store.modern.identity.agent.domain.AgentTokenFailure
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.OffsetDateTime

data class AgentTokenError(val code: String, val message: String, val status: Int, val timestamp: String, val details: Any? = null)

@RestControllerAdvice(assignableTypes = [AgentTokenController::class])
@Profile("modern")
class AgentTokenErrorHandler {
    @ExceptionHandler(AgentTokenFailure.InvalidAccessTtl::class, AgentTokenFailure.InvalidRefreshTtl::class)
    fun invalid(ex: AgentTokenFailure) = error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ex.message.orEmpty())

    @ExceptionHandler(AgentTokenFailure.AgentNotFound::class)
    fun notFound(ex: AgentTokenFailure.AgentNotFound) = error(HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND", ex.message.orEmpty())

    @ExceptionHandler(AgentTokenFailure.AgentInactive::class, AgentTokenFailure.AccessDenied::class)
    fun denied(ex: AgentTokenFailure) = error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", ex.message.orEmpty())

    private fun error(status: HttpStatus, code: String, message: String) = ResponseEntity.status(status).body(
        AgentTokenError(code, message, status.value(), OffsetDateTime.now().toString()),
    )
}
