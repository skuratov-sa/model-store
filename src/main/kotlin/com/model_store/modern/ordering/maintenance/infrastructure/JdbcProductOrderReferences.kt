package com.model_store.modern.ordering.maintenance.infrastructure

import com.model_store.modern.ordering.maintenance.application.*
import jakarta.persistence.EntityManagerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager as Tx
import javax.sql.DataSource

@Repository
@Profile("modern")
class JdbcProductOrderReferences(private val jdbc: JdbcTemplate,
    private val entityManagerFactory: EntityManagerFactory, private val dataSource: DataSource,
) : ProductOrderReferences, ImageOrderMaintenance {
    override fun hasOrders(productId: Long): Boolean {
        requireJpa()
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM \"order\" WHERE product_id=?)",
            Boolean::class.java, productId) == true
    }

    override fun observeImageOrder(orderId: Long) = order(orderId, false)
    override fun lockImageOrder(orderId: Long): ImageOrderParties? {
        requireJpa()
        return order(orderId, true)
    }

    override fun isImageReferenced(imageId: Long): Boolean {
        requireJpa()
        return jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM "order" WHERE image_payment_proof_id=?)
            OR EXISTS(SELECT 1 FROM order_case_image WHERE image_id=?)""",
            Boolean::class.java, imageId, imageId) == true
    }

    private fun order(id: Long, lock: Boolean) = jdbc.query(
        "SELECT customer_id,seller_id,status::text FROM \"order\" WHERE id=? ${if (lock) "FOR UPDATE" else ""}",
        { rs, _ -> ImageOrderParties(rs.getLong(1), rs.getLong(2), rs.getString(3)) }, id,
    ).firstOrNull()

    private fun requireJpa() {
        val holder = Tx.getResource(entityManagerFactory) as? EntityManagerHolder
        if (!(Tx.isActualTransactionActive() && !Tx.isCurrentTransactionReadOnly() &&
            holder?.entityManager?.isJoinedToTransaction == true && Tx.hasResource(dataSource) && jdbc.dataSource === dataSource))
            throw OrderMaintenanceTransactionRequired()
    }
}
