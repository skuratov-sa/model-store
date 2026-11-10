package com.model_store.modern.ordering.agent.application

/** Joins the caller's writable application JPA transaction; never commits independently. */
interface AgentOrderAudit {
    fun record(adminId: Long, agentId: Long, orderId: Long, action: String)
}
