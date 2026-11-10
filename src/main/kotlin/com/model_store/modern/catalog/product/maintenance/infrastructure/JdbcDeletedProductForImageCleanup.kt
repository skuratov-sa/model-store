package com.model_store.modern.catalog.product.maintenance.infrastructure

import com.model_store.modern.catalog.product.maintenance.application.CleanupProduct
import com.model_store.modern.catalog.product.maintenance.application.CleanupProductTransactionRequired
import com.model_store.modern.catalog.product.maintenance.application.DeletedProductForImageCleanup
import jakarta.persistence.EntityManagerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager as Tx
import javax.sql.DataSource

@Repository
@Profile("modern")
class JdbcDeletedProductForImageCleanup(
    private val jdbc: JdbcTemplate,
    private val entityManagerFactory: EntityManagerFactory,
    private val dataSource: DataSource,
) : DeletedProductForImageCleanup {
    override fun observe(id: Long) = read(id, false)

    override fun lock(id: Long): CleanupProduct? {
        val holder = Tx.getResource(entityManagerFactory) as? EntityManagerHolder
        if (!(Tx.isActualTransactionActive() && !Tx.isCurrentTransactionReadOnly() &&
            holder?.entityManager?.isJoinedToTransaction == true && Tx.hasResource(dataSource) && jdbc.dataSource === dataSource))
            throw CleanupProductTransactionRequired()
        return read(id, true)
    }

    private fun read(id: Long, lock: Boolean): CleanupProduct? = jdbc.query(
        "SELECT participant_id, status::text FROM product WHERE id=? ${if (lock) "FOR UPDATE" else ""}",
        { rs, _ -> CleanupProduct(rs.getLong(1), rs.getString(2) == "DELETED") }, id,
    ).firstOrNull()
}
