package com.model_store.modern.ordering.agent.infrastructure

import com.model_store.modern.ordering.agent.application.AgentOrderAudit
import com.model_store.modern.ordering.lifecycle.application.LifecycleTransaction
import com.model_store.modern.ordering.lifecycle.application.LifecycleTransactionRequired
import jakarta.persistence.EntityManagerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.transaction.support.TransactionSynchronizationManager as Tx
import javax.sql.DataSource

@Repository
@Profile("modern")
class JdbcAgentOrderAudit(private val jdbc: NamedParameterJdbcTemplate,
    private val transaction: LifecycleTransaction, private val entityManagerFactory: EntityManagerFactory,
    private val dataSource: DataSource) : AgentOrderAudit {
    override fun record(adminId: Long, agentId: Long, orderId: Long, action: String) {
        transaction.requireWritableJpa()
        // A valid application guard alone cannot make a template on another DataSource join this tx.
        val holder = Tx.getResource(entityManagerFactory) as? EntityManagerHolder
        if (!Tx.isActualTransactionActive() || Tx.isCurrentTransactionReadOnly() ||
            holder?.entityManager?.isJoinedToTransaction != true || !Tx.hasResource(dataSource) ||
            jdbc.jdbcTemplate.dataSource !== dataSource)
            throw LifecycleTransactionRequired()
        jdbc.update("""INSERT INTO admin_agent_order_action(admin_id,agent_id,order_id,action,created_at)
            VALUES (:admin,:agent,:order,:action,:created)""", mapOf("admin" to adminId, "agent" to agentId,
            "order" to orderId, "action" to action, "created" to java.sql.Timestamp.from(java.time.Instant.now())))
    }
}
