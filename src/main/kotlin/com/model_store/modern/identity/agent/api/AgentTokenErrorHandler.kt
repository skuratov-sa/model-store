package com.model_store.modern.identity.agent.api

import com.model_store.modern.identity.agent.domain.AgentTokenFailure
import com.model_store.modern.shared.api.MvcApiError
import com.model_store.modern.shared.api.mvcApiError
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

typealias AgentTokenError = MvcApiError

@RestControllerAdvice(assignableTypes = [AgentTokenController::class])
@Profile("modern")
class AgentTokenErrorHandler {
    @ExceptionHandler(AgentTokenFailure.InvalidAccessTtl::class, AgentTokenFailure.InvalidRefreshTtl::class)
    fun invalid(ex: AgentTokenFailure) = error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ex.message.orEmpty())

    @ExceptionHandler(AgentTokenFailure.AgentNotFound::class)
    fun notFound(ex: AgentTokenFailure.AgentNotFound) = error(HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND", ex.message.orEmpty())

    @ExceptionHandler(AgentTokenFailure.AgentInactive::class, AgentTokenFailure.AccessDenied::class)
    fun denied(ex: AgentTokenFailure) = error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", ex.message.orEmpty())

    private fun error(status: HttpStatus, code: String, message: String) = mvcApiError(status, code, message)
}
