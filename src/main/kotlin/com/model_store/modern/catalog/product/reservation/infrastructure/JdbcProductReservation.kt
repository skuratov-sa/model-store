package com.model_store.modern.catalog.product.reservation.infrastructure

import com.model_store.modern.catalog.product.reservation.application.*
import jakarta.persistence.EntityManagerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.ResultSet

@Repository
@Profile("modern")
class JdbcProductReservation(
    private val jdbc: NamedParameterJdbcTemplate,
    private val entityManagerFactory: EntityManagerFactory,
) : ProductReservation {
    override fun reserve(lines: List<ProductQuantity>): List<OrderProductSnapshot> {
        requireTransaction()
        if (lines.isEmpty()) return emptyList()
        lines.forEach { validate(it.productId, it.quantity) }
        if (lines.map { it.productId }.toSet().size != lines.size) fail(ProductReservationProblem.DUPLICATE_IDS)

        val sorted = lines.sortedBy { it.productId }
        val snapshots = sorted.map { line ->
            val product = lock(line.productId) ?: fail(ProductReservationProblem.PRODUCT_NOT_FOUND, line.productId)
            if (product.status != "ACTIVE" || product.availability == "GIVEAWAY")
                fail(ProductReservationProblem.PRODUCT_NOT_FOUND, line.productId)
            if (product.count != null && product.count < line.quantity)
                fail(ProductReservationProblem.OUT_OF_STOCK, line.productId)
            product
        }
        sorted.zip(snapshots).forEach { (line, product) ->
            if (product.availability == "PURCHASABLE" && product.count != null) {
                val changed = jdbc.update(
                    """UPDATE product SET count = count - :quantity WHERE id = :id
                       AND status = 'ACTIVE' AND availability = 'PURCHASABLE' AND count >= :quantity""",
                    mapOf("id" to line.productId, "quantity" to line.quantity),
                )
                if (changed != 1) fail(ProductReservationProblem.RESERVATION_CONFLICT, line.productId)
            }
        }
        return snapshots
    }

    override fun returnLimitedStock(productId: Long, quantity: Int): Boolean {
        requireTransaction()
        validate(productId, quantity)
        val product = lock(productId) ?: return false
        if (product.availability != "PURCHASABLE" || product.count == null) return false
        if (product.count > Int.MAX_VALUE - quantity) fail(ProductReservationProblem.COUNT_OVERFLOW, productId)
        val changed = jdbc.update(
            """UPDATE product SET count = count + :quantity WHERE id = :id
               AND availability = 'PURCHASABLE' AND count IS NOT NULL""",
            mapOf("id" to productId, "quantity" to quantity),
        )
        if (changed != 1) fail(ProductReservationProblem.RESERVATION_CONFLICT, productId)
        return true
    }

    private fun lock(id: Long): OrderProductSnapshot? = jdbc.query(
        """SELECT id, participant_id, name, price, prepayment_amount, currency::text AS currency,
                  availability::text AS availability, status::text AS status, count
           FROM product WHERE id = :id FOR UPDATE""",
        mapOf("id" to id), ::snapshot,
    ).firstOrNull()

    private fun snapshot(rs: ResultSet, ignored: Int) = OrderProductSnapshot(
        id = rs.getLong("id"), ownerId = rs.getLong("participant_id"), name = rs.getString("name"),
        unitPrice = rs.getFloat("price"),
        prepaymentAmount = rs.getFloat("prepayment_amount").takeUnless { rs.wasNull() },
        currency = rs.getString("currency"), availability = rs.getString("availability"),
        status = rs.getString("status"), count = rs.getInt("count").takeUnless { rs.wasNull() },
    )

    private fun validate(id: Long, quantity: Int) {
        if (id <= 0) fail(ProductReservationProblem.INVALID_PRODUCT_ID, id)
        if (quantity <= 0) fail(ProductReservationProblem.INVALID_QUANTITY, id)
    }

    private fun requireTransaction() {
        val dataSource = jdbc.jdbcTemplate.dataSource
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            !TransactionSynchronizationManager.hasResource(entityManagerFactory) ||
            dataSource == null || !TransactionSynchronizationManager.hasResource(dataSource))
            fail(ProductReservationProblem.TRANSACTION_REQUIRED)
    }

    private fun fail(problem: ProductReservationProblem, id: Long? = null): Nothing =
        throw ProductReservationFailure(problem, id)
}
