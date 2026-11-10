package com.model_store.modern.ordering.agent.application

import com.model_store.modern.identity.participant.application.AgentAccessLock
import com.model_store.modern.identity.participant.application.CurrentAdminLock
import com.model_store.modern.ordering.lifecycle.application.*
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class AgentOrderView(val order: OrderView, val sellerLogin: String?)
interface AgentOrderQueue {
    fun page(agentId: Long?, status: String?, limit: Int, offset: Long): List<AgentOrderView>
}

@Service
@Profile("modern")
class AgentOrderQueries(private val admins: CurrentAdminLock, private val access: AgentAccessLock,
    private val transaction: LifecycleTransaction, private val queue: AgentOrderQueue) {
    @Transactional(transactionManager = "transactionManager")
    fun queue(callerAdminId: Long, agentId: Long?, status: String?, page: Int, size: Int): List<AgentOrderView> {
        transaction.requireWritableJpa()
        // Invalid page precedes target lookup, but never precedes current caller authorization.
        if (page < 0 || size !in 1..100) {
            admins.lockActiveAdmin(callerAdminId)
            throw AgentOrderFailure(AgentOrderProblem.INVALID_PAGE)
        }
        if (agentId == null) admins.lockActiveAdmin(callerAdminId)
        else access.lockActiveAdminAndAgent(callerAdminId, agentId)
        return queue.page(agentId, status, size, page.toLong() * size)
    }
}
