package com.model_store.modern.seller.agent.product.application

import com.model_store.modern.catalog.product.agent.application.*
import com.model_store.modern.identity.agent.application.VerifyAgentAccess
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Profile("modern")
class AgentProductActions(private val products: AgentProducts, private val verify: VerifyAgentAccess) {
    @Transactional("transactionManager")
    fun create(actor: Actor?, patch: AgentProductPatch): Long {
        val id = verify.requireActiveAgent(actor)
        return products.create(AgentProductCaller(id, actor!!.role, true), patch)
    }

    fun products(actor: Actor?, agentId: Long) = products.products(admin(actor), agentId)
    fun product(actor: Actor?, productId: Long) = products.product(admin(actor), productId)
    fun update(actor: Actor?, agentId: Long, productId: Long, patch: AgentProductPatch) =
        products.update(admin(actor), agentId, productId, patch)
    fun extend(actor: Actor?, agentId: Long, productId: Long) = products.extend(admin(actor), agentId, productId)

    private fun admin(actor: Actor?): AgentProductCaller {
        if (actor?.tokenType != TokenType.ACCESS || actor.role != "ADMIN" || actor.participantId == null)
            throw AgentProductDenied()
        return AgentProductCaller(actor.participantId, actor.role, false)
    }
}
