package com.model_store.modern.identity.agent.application

/**
 * Caller has already verified an agent-access principal. Identity rechecks current eligibility
 * under a row lock retained by the caller's writable JPA transaction. No identity data escapes.
 */
interface ActiveAgentLock {
    fun lockActiveAgent(verifiedAgentId: Long)
}

class ActiveAgentTransactionRequired : RuntimeException("A writable identity JPA transaction is required")
