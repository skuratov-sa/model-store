package com.model_store.modern.identity.agent.api

import com.model_store.modern.identity.agent.application.AgentTokens
import com.model_store.modern.identity.agent.application.IssueAgentTokens
import com.model_store.modern.shared.domain.Actor
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@Profile("modern")
@RequestMapping("/admin/actions")
class AgentTokenController(private val issue: IssueAgentTokens) {
    @PostMapping("/agents/{participantId}/token")
    fun issue(
        @AuthenticationPrincipal actor: Actor?,
        @PathVariable participantId: Long,
        @RequestParam(defaultValue = "30") accessTokenTtlMinutes: Int,
        @RequestParam(defaultValue = "90") refreshTokenTtlDays: Int,
    ): AgentTokens = issue.issue(actor, participantId, accessTokenTtlMinutes, refreshTokenTtlDays)
}
