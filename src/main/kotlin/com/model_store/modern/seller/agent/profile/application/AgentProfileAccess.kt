package com.model_store.modern.seller.agent.profile.application

import com.model_store.modern.identity.participant.application.AgentProfileCommands
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

class AgentProfileDenied : RuntimeException("Доступ запрещён")

/** Identity owns the current administrator and target checks; this adapter only passes IDs. */
@Service
@Profile("modern")
class AgentProfileAccess(private val profiles: AgentProfileCommands) {
    fun requireAdminActor(actor: Actor?): Long {
        if (actor?.tokenType != TokenType.ACCESS || actor.role != "ADMIN") throw AgentProfileDenied()
        return actor.participantId ?: throw AgentProfileDenied()
    }

    fun requireTarget(actor: Actor?, targetId: Long): Long {
        profiles.read(requireAdminActor(actor), targetId)
        return targetId
    }
}
