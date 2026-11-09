package com.model_store.modern.reputation.infrastructure

import com.model_store.modern.reputation.application.*
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
@Profile("modern")
class JdbcReviewRead(private val jdbc: JdbcTemplate) : ReviewRead {
    override fun isAgent(participantId: Long): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM participant WHERE id=? AND is_agent=true)",Boolean::class.javaObjectType,participantId) == true
    override fun forSeller(sellerId: Long) = reviews("r.seller_id", sellerId, false)
    override fun forProduct(productId: Long) = reviews("r.product_id", productId, true)
    override fun rating(sellerId: Long): SellerRating? = jdbc.query(
        "SELECT seller_id,average_rating,total_reviews FROM seller_rating WHERE seller_id=?",
        { rs, _ -> SellerRating(rs.getLong(1),rs.getBigDecimal(2),rs.getObject(3, Int::class.javaObjectType)) }, sellerId).firstOrNull()

    private fun reviews(column: String, id: Long, unknownName: Boolean): List<ReviewView> = jdbc.query("""
        SELECT r.id,r.rating,r.comment,r.created_at,p.full_name,
            (SELECT i.id FROM image i WHERE i.entity_id=r.reviewer_id AND i.tag='PARTICIPANT'
             AND i.status='ACTIVE' ORDER BY i.created_at LIMIT 1) AS image_id
        FROM review r LEFT JOIN participant p ON p.id=r.reviewer_id
        WHERE $column=? ${if (unknownName) "" else "AND p.full_name IS NOT NULL"} ORDER BY r.created_at DESC
    """, { rs, _ -> ReviewView(rs.getLong("id"),rs.getInt("rating"),rs.getString("comment"),
        rs.getString("full_name") ?: "unknown",rs.getObject("image_id", Long::class.javaObjectType),rs.getTimestamp("created_at")?.toInstant()) }, id)
}
