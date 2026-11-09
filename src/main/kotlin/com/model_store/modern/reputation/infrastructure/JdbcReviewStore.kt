package com.model_store.modern.reputation.infrastructure

import com.model_store.modern.reputation.application.*
import com.model_store.modern.reputation.domain.*
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
@Profile("modern")
class JdbcReviewStore(private val jdbc: JdbcTemplate, private val transaction: ReviewTransaction) : ReviewStore {
    override fun find(id: Long): Review? = review(id, false)
    override fun exists(orderId: Long, reviewerId: Long): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM review WHERE order_id=? AND reviewer_id=?)", Boolean::class.javaObjectType, orderId, reviewerId) == true

    override fun lockSeller(sellerId: Long) {
        transaction.requireWritableJpa()
        // Two-int advisory namespace is disjoint from the existing single-bigint owner locks.
        // Hash collisions only serialize unrelated sellers; they cannot lose an update.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(28, ?)", (sellerId xor (sellerId ushr 32)).toInt())
    }

    override fun lockReview(id: Long): Review? {
        transaction.requireWritableJpa()
        return review(id, true)
    }

    override fun insert(review: NewReview): Long {
        transaction.requireWritableJpa()
        // V1.2 trigger is the only rating updater and shares this transaction.
        return jdbc.queryForObject("""INSERT INTO review(order_id,reviewer_id,seller_id,product_id,rating,comment,created_at)
            VALUES (?,?,?,?,?,?,?) RETURNING id""", Long::class.javaObjectType, review.orderId, review.reviewerId,
            review.sellerId, review.productId, review.rating, review.comment, java.sql.Timestamp.from(java.time.Instant.now()))!!
    }

    override fun delete(id: Long) {
        transaction.requireWritableJpa()
        jdbc.update("DELETE FROM review WHERE id=?", id)
    }

    private fun review(id: Long, lock: Boolean) = jdbc.query(
        "SELECT id,order_id,reviewer_id,seller_id,product_id,rating,comment,created_at FROM review WHERE id=? ${if (lock) "FOR UPDATE" else ""}",
        { rs, _ -> Review(rs.getLong("id"),rs.getLong("order_id"),rs.getLong("reviewer_id"),rs.getLong("seller_id"),
            rs.getObject("product_id", Long::class.javaObjectType),rs.getInt("rating"),rs.getString("comment"),rs.getTimestamp("created_at")?.toInstant()) }, id).firstOrNull()
}
