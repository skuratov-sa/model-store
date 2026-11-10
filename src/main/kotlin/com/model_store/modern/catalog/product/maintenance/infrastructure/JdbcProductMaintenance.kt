package com.model_store.modern.catalog.product.maintenance.infrastructure

import com.model_store.modern.catalog.product.maintenance.application.*
import com.model_store.modern.identity.participant.maintenance.application.MaintenanceParticipantLocks
import com.model_store.modern.media.image.maintenance.application.ImageProductReferences
import com.model_store.modern.ordering.maintenance.application.ProductOrderReferences
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import jakarta.persistence.EntityManagerFactory
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.orm.jpa.JpaTransactionManager
import javax.sql.DataSource
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager as Tx
import org.springframework.transaction.support.TransactionTemplate

@Service
@Profile("modern")
class JdbcProductMaintenance(
    private val jdbc: JdbcTemplate,
    @Qualifier("transactionManager") private val manager: PlatformTransactionManager,
    private val entityManagerFactory: EntityManagerFactory,
    private val dataSource: DataSource,
    private val participants: MaintenanceParticipantLocks,
    private val images: ImageProductReferences,
    private val orders: ProductOrderReferences,
) : ProductMaintenance {
    private val transactions = TransactionTemplate(manager).apply { timeout = 30 }
    private val log = LoggerFactory.getLogger(javaClass)

    override fun lastCandidateId(): Long = jdbc.queryForObject("SELECT coalesce(max(id),0) FROM product", Long::class.java)!!

    override fun expireDueOrdinary(): Int {
        requireNoTransaction()
        return requireNotNull(transactions.execute {
            requireJpa()
            jdbc.update("""UPDATE product SET status='TIME_EXPIRED'
                WHERE status='ACTIVE' AND availability <> 'GIVEAWAY' AND expiration_date <= CURRENT_TIMESTAMP""")
        })
    }

    override fun cleanupDeletedBatch(afterId: Long, limit: Int, upToId: Long): ProductCleanupBatch {
        requireNoTransaction()
        require(afterId >= 0 && upToId >= 0 && limit in 1..200)
        val rows = jdbc.query("""SELECT id,participant_id FROM product WHERE id>? AND id<=? AND status='DELETED'
            ORDER BY id LIMIT ?""", { rs, _ -> rs.getLong(1) to rs.getLong(2) }, afterId, upToId, limit)
        var deleted = 0
        var failed = 0
        for ((id, owner) in rows) {
            try {
                if (transactions.execute {
                    requireJpa()
                    jdbc.execute("SET LOCAL lock_timeout='10s'")
                    if (!participants.lockIds(listOf(owner))) return@execute false
                    // Lock the product only after all discovered participants; never follow a changed owner.
                    val eligible = jdbc.query("""SELECT participant_id,status::text,availability::text,
                        giveaway_start_at,giveaway_end_at FROM product WHERE id=? FOR UPDATE SKIP LOCKED""", { rs, _ ->
                        rs.getLong(1) == owner && rs.getString(2) == "DELETED" &&
                            rs.getString(3) != "GIVEAWAY" && rs.getObject(4) == null && rs.getObject(5) == null
                    }, id).firstOrNull() == true
                    if (!eligible || images.hasImages(id) || orders.hasOrders(id)) return@execute false
                    for (table in listOf("product_favorite", "product_basket", "product_cart", "product_category")) {
                        jdbc.update("DELETE FROM $table WHERE product_id=?", id)
                    }
                    jdbc.update("DELETE FROM product WHERE id=? AND status='DELETED'", id) == 1
                } == true) deleted++
            } catch (error: Exception) {
                // Each item has an independent transaction. Failed FK/concurrent references roll back dependents.
                failed++
                log.error("Product cleanup failed for id={}; retained for next run", id, error)
            }
        }
        return ProductCleanupBatch(rows.size, deleted, failed, rows.lastOrNull()?.first)
    }

    private fun requireNoTransaction() {
        if (Tx.isActualTransactionActive() || manager !is JpaTransactionManager ||
            manager.entityManagerFactory !== entityManagerFactory || manager.dataSource !== dataSource ||
            jdbc.dataSource !== dataSource) throw ProductMaintenanceTransactionRequired()
    }

    private fun requireJpa() {
        val holder = Tx.getResource(entityManagerFactory) as? EntityManagerHolder
        if (!(Tx.isActualTransactionActive() && !Tx.isCurrentTransactionReadOnly() &&
            holder?.entityManager?.isJoinedToTransaction == true && Tx.hasResource(dataSource)))
            throw ProductMaintenanceTransactionRequired()
    }
}
