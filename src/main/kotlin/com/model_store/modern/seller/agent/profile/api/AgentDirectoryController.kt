package com.model_store.modern.seller.agent.profile.api

import com.model_store.modern.identity.participant.application.AgentDirectoryQuery
import com.model_store.modern.identity.participant.application.AgentProfileFailure
import com.model_store.modern.shared.api.mvcApiError
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

data class AgentDirectoryResponse(val id: Long, val login: String?, val status: String)

@RestController
@Profile("modern")
class AgentDirectoryController(private val query: AgentDirectoryQuery) {
    @GetMapping("/admin/actions/agents")
    fun agents(@AuthenticationPrincipal actor: Actor?): List<AgentDirectoryResponse> {
        if (actor?.tokenType != TokenType.ACCESS || actor.role != "ADMIN" || actor.participantId == null)
            throw AgentProfileFailure.AdminForbidden()
        return query.agents(actor.participantId).map { AgentDirectoryResponse(it.id, it.login, it.status) }
    }
}

@RestControllerAdvice(assignableTypes = [AgentDirectoryController::class])
@Profile("modern")
class AgentDirectoryErrorHandler {
    @ExceptionHandler(AgentProfileFailure.AdminForbidden::class)
    fun denied() = mvcApiError(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Доступ запрещён")
}
