package com.model_store.modern.identity.participant.application

import com.model_store.modern.identity.participant.domain.Participant
import com.model_store.modern.identity.participant.domain.ParticipantRole
import com.model_store.modern.identity.participant.domain.ParticipantStatus
import jakarta.persistence.EntityManagerFactory
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

/**
 * Authorizes an active, non-agent administrator for an existing agent at any status.
 * Both participant rows stay locked until the caller's JPA transaction completes.
 * This port has no transaction of its own and returns no participant data.
 */
interface AgentAccessLock {
    fun lockActiveAdminAndAgent(callerAdminId: Long, agentId: Long)
}

class AgentAccessTransactionRequired : RuntimeException("An active participant JPA transaction is required")

@Service
class AgentAccessLockService(
    private val store: ParticipantStore,
    private val entityManagerFactory: EntityManagerFactory,
    private val dataSource: DataSource,
) : AgentAccessLock {
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    override fun lockActiveAdminAndAgent(callerAdminId: Long, agentId: Long) {
        lockAndRequireAgent(callerAdminId, agentId)
    }

    /** Also used by the owner profile update, which opens its own JPA transaction. */
    internal fun lockAndRequireAgent(callerAdminId: Long, agentId: Long): Participant {
        requireJpaTransaction()
        // Lock both rows before either validation. An invalid target may itself be another caller.
        val locked = listOf(callerAdminId, agentId).distinct().sorted()
            .associateWith(store::findForUpdate)
        val caller = locked[callerAdminId]
        if (caller?.role != ParticipantRole.ADMIN || caller.status != ParticipantStatus.ACTIVE || caller.isAgent)
            throw AgentProfileFailure.AdminForbidden()
        return locked[agentId]?.takeIf { it.isAgent }
            ?: throw AgentProfileFailure.AgentNotFound(agentId)
    }

    private fun requireJpaTransaction() {
        val holder = TransactionSynchronizationManager.getResource(entityManagerFactory) as? EntityManagerHolder
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            TransactionSynchronizationManager.isCurrentTransactionReadOnly() ||
            holder?.entityManager?.isJoinedToTransaction != true ||
            !TransactionSynchronizationManager.hasResource(dataSource))
            throw AgentAccessTransactionRequired()
    }
}
