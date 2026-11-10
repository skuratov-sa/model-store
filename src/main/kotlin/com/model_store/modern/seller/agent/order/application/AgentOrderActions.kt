package com.model_store.modern.seller.agent.order.application

import com.model_store.modern.ordering.agent.application.*
import com.model_store.modern.ordering.lifecycle.application.LifecycleStore
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/** Only verified caller identity crosses into ordering; no delegated Actor is constructed. */
@Service
@Profile("modern")
class AgentOrderActions(private val commands: AgentOrderCommands, private val queries: AgentOrderQueries,
    private val lifecycle: LifecycleStore) {
    fun authorize(actor: Actor?) { adminId(actor) }
    fun queue(actor: Actor?, agentId: Long?, status: String?, page: Int, size: Int) =
        queries.queue(adminId(actor), agentId, status, page, size)
    fun execute(actor: Actor?, agentId: Long, orderId: Long, action: String, comment: String?, deliveryUrl: String?) =
        commands.execute(adminId(actor), agentId, orderId, action, comment, deliveryUrl)

    private fun adminId(actor: Actor?): Long {
        if (actor?.tokenType != TokenType.ACCESS || actor.role != "ADMIN")
            throw AgentOrderFailure(AgentOrderProblem.ACCESS_DENIED)
        val id = actor.participantId?.takeIf { it > 0 } ?: throw AgentOrderFailure(AgentOrderProblem.INVALID_ACTOR)
        // Legacy account filter refuses even a bot carrying an ordinary signed ADMIN ACCESS token.
        if (lifecycle.isAgent(id)) throw AgentOrderFailure(AgentOrderProblem.BOT_CALLER)
        return id
    }
}
