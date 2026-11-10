package com.model_store.modern.identity.participant.maintenance.infrastructure

import com.model_store.modern.identity.participant.maintenance.application.MaintenanceParticipantLocks
import com.model_store.modern.identity.participant.maintenance.application.MaintenanceParticipantTransactionRequired
import jakarta.persistence.EntityManagerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager as Tx
import javax.sql.DataSource

@Repository
@Profile("modern")
class JdbcMaintenanceParticipantLocks(private val jdbc: JdbcTemplate,
    private val entityManagerFactory: EntityManagerFactory, private val dataSource: DataSource,
) : MaintenanceParticipantLocks {
    override fun lockIds(ids: List<Long>): Boolean {
        val holder = Tx.getResource(entityManagerFactory) as? EntityManagerHolder
        if (!(Tx.isActualTransactionActive() && !Tx.isCurrentTransactionReadOnly() &&
            holder?.entityManager?.isJoinedToTransaction == true && Tx.hasResource(dataSource) && jdbc.dataSource === dataSource))
            throw MaintenanceParticipantTransactionRequired()
        // Individual statements prevent query planner/join ordering from changing acquisition order.
        for (id in ids.distinct().sorted()) {
            if (jdbc.query("SELECT id FROM participant WHERE id=? FOR UPDATE", { rs, _ -> rs.getLong(1) }, id).isEmpty())
                return false
        }
        return true
    }
}
