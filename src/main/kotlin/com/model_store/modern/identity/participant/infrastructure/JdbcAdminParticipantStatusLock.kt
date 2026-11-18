package com.model_store.modern.identity.participant.infrastructure

import com.model_store.modern.identity.participant.application.*
import com.model_store.modern.identity.participant.domain.ParticipantStatusUnavailable
import jakarta.persistence.EntityManagerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

@Repository
@Profile("modern")
class JdbcAdminParticipantStatusLock(
    private val entityManagerFactory: EntityManagerFactory,
    private val dataSource: DataSource,
) : AdminParticipantStatusLock {
    private val jdbc = JdbcTemplate(dataSource)

    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    override fun lockActiveAdminAndTarget(callerId: Long, targetId: Long) {
        val holder = TransactionSynchronizationManager.getResource(entityManagerFactory) as? EntityManagerHolder
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            TransactionSynchronizationManager.isCurrentTransactionReadOnly() ||
            holder?.entityManager?.isJoinedToTransaction != true ||
            !TransactionSynchronizationManager.hasResource(dataSource))
            throw CurrentAdminTransactionRequired()

        // Read locked tuples after any wait, independently of managed JPA snapshots.
        val locked = listOf(callerId, targetId).distinct().sorted().associateWith { id ->
            jdbc.query("""SELECT role::text, status::text, is_agent FROM participant
                WHERE id=? FOR UPDATE""", { rs, _ ->
                rs.getString("role") == "ADMIN" && rs.getString("status") == "ACTIVE" && !rs.getBoolean("is_agent")
            }, id).firstOrNull()
        }
        if (locked[callerId] != true) throw CurrentAdminForbidden()
        // An absent lower target must not be looked up/locked anew beyond the frontier.
        if (locked[targetId] == null) throw ParticipantStatusUnavailable(targetId)
    }
}
