package com.model_store.modern.identity.participant.infrastructure

import com.model_store.modern.identity.participant.application.*
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
class JdbcCurrentAdminLock(
    private val entityManagerFactory: EntityManagerFactory,
    private val dataSource: DataSource,
) : CurrentAdminLock {
    private val jdbc = JdbcTemplate(dataSource)

    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    override fun lockActiveAdmin(callerId: Long) {
        val holder = TransactionSynchronizationManager.getResource(entityManagerFactory) as? EntityManagerHolder
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            TransactionSynchronizationManager.isCurrentTransactionReadOnly() ||
            holder?.entityManager?.isJoinedToTransaction != true ||
            !TransactionSynchronizationManager.hasResource(dataSource))
            throw CurrentAdminTransactionRequired()

        // Read the locked tuple after any wait, independently of the JPA identity map.
        val allowed = jdbc.query("""SELECT role::text, status::text, is_agent FROM participant
            WHERE id=? FOR UPDATE""", { rs, _ ->
            rs.getString("role") == "ADMIN" && rs.getString("status") == "ACTIVE" && !rs.getBoolean("is_agent")
        }, callerId).firstOrNull() == true
        if (!allowed) throw CurrentAdminForbidden()
    }
}
