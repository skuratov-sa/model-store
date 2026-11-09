package com.model_store.modern.identity.agent.infrastructure

import com.model_store.modern.identity.agent.application.ActiveAgentLock
import com.model_store.modern.identity.agent.application.ActiveAgentTransactionRequired
import com.model_store.modern.identity.agent.domain.AgentTokenFailure
import jakarta.persistence.EntityManagerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

@Repository
@Profile("modern")
class JdbcActiveAgentLock(private val jdbc: NamedParameterJdbcTemplate,
    private val entityManagerFactory: EntityManagerFactory, private val dataSource: DataSource) : ActiveAgentLock {
    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    override fun lockActiveAgent(verifiedAgentId: Long) {
        val holder = TransactionSynchronizationManager.getResource(entityManagerFactory) as? EntityManagerHolder
        val connection = TransactionSynchronizationManager.getResource(dataSource) as? ConnectionHolder
        val jdbcSource = jdbc.jdbcTemplate.dataSource
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            TransactionSynchronizationManager.isCurrentTransactionReadOnly() ||
            holder?.entityManager?.isJoinedToTransaction != true ||
            connection == null || jdbcSource == null ||
            TransactionSynchronizationManager.getResource(jdbcSource) !== connection)
            throw ActiveAgentTransactionRequired()
        // JDBC bypasses a possibly stale JPA identity map and observes the tuple after lock waiting.
        val allowed = jdbc.query("SELECT is_agent, status::text FROM participant WHERE id=:id FOR UPDATE",
            mapOf("id" to verifiedAgentId)) { rs, _ -> rs.getBoolean(1) && rs.getString(2) == "ACTIVE" }.firstOrNull()
        if (allowed != true) throw AgentTokenFailure.InvalidAgentAccess()
    }
}
