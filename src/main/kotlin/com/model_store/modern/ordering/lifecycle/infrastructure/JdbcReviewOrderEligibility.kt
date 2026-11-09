package com.model_store.modern.ordering.lifecycle.infrastructure

import com.model_store.modern.ordering.lifecycle.application.*
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
@Profile("modern")
class JdbcReviewOrderEligibility(private val jdbc: JdbcTemplate, private val transaction: LifecycleTransaction,
                                private val locks: LifecycleStore) : ReviewOrderEligibility {
    override fun observe(orderId: Long): ReviewOrderSnapshot? = read(orderId, false)

    override fun lockForReview(observed: ReviewOrderSnapshot, additionalParticipantIds: Set<Long>): ReviewOrderSnapshot? {
        transaction.requireWritableJpa()
        (additionalParticipantIds + observed.customerId + observed.sellerId).sorted().forEach(locks::lockParticipant)
        val current = read(observed.orderId, true) ?: return null
        return current.takeIf { it.customerId == observed.customerId && it.sellerId == observed.sellerId &&
            it.productId == observed.productId }
    }

    private fun read(id: Long, lock: Boolean): ReviewOrderSnapshot? = jdbc.query(
        """SELECT id,customer_id,seller_id,product_id,status::text FROM "order" WHERE id=? ${if (lock) "FOR UPDATE" else ""}""",
        { rs, _ -> ReviewOrderSnapshot(rs.getLong("id"), rs.getLong("customer_id"), rs.getLong("seller_id"),
            rs.getObject("product_id", Long::class.javaObjectType), rs.getString("status")) }, id).firstOrNull()
}
