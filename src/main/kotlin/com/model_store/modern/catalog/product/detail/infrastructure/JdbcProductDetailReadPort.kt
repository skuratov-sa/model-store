package com.model_store.modern.catalog.product.detail.infrastructure

import com.model_store.modern.catalog.product.detail.application.ProductDetailReadPort
import com.model_store.modern.catalog.product.detail.domain.*
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

@Repository
class JdbcProductDetailReadPort(private val jdbc: NamedParameterJdbcTemplate) : ProductDetailReadPort {
    override fun publicCard(id: Long): ProductCard? {
        // The legacy endpoint is public and has no viewer-specific adult-content check.
        val base = jdbc.query("""SELECT p.id, p.name, p.description, p.price, p.prepayment_amount, p.count,
            p.currency::text AS currency, p.originality, p.participant_id, p.status::text AS status,
            p.availability::text AS availability, p.used, s.login AS seller_login,
            COALESCE(sr.average_rating, 0) AS seller_rating, COALESCE(sr.total_reviews, 0) AS total_reviews
            FROM product p LEFT JOIN participant s ON s.id = p.participant_id
            LEFT JOIN seller_rating sr ON sr.seller_id = p.participant_id
            WHERE p.id = :id AND p.status = 'ACTIVE' AND p.availability <> 'GIVEAWAY'""",
            mapOf("id" to id)) { rs, _ ->
            ProductCard(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                rs.getFloat("price"), rs.nullableFloat("prepayment_amount"), rs.nullableInt("count"),
                rs.getString("currency"), rs.getString("originality"), rs.getLong("participant_id"),
                rs.getString("status"), rs.getString("availability"), rs.nullableBoolean("used"),
                emptyList(), emptyList(), emptyList(), rs.getString("seller_login") ?: "unknown",
                rs.getFloat("seller_rating"), rs.getInt("total_reviews"))
        }.firstOrNull() ?: return null
        // Legacy detail traverses product_category rows, unlike the bulk "my products" projection.
        val categories = jdbc.query("""SELECT c.id, c.name FROM product_category pc
            JOIN category c ON c.id = pc.category_id WHERE pc.product_id = :id ORDER BY pc.id""",
            mapOf("id" to id)) { rs, _ -> DetailCategory(rs.getLong("id"), rs.getString("name")) }
        val images = jdbc.query("""SELECT id FROM image WHERE entity_id = :id AND tag = 'PRODUCT'
            AND status = 'ACTIVE' ORDER BY created_at""", mapOf("id" to id)) { rs, _ -> rs.getLong(1) }
        val reviews = jdbc.query("""WITH reviewer_images AS (
            SELECT DISTINCT ON (i.entity_id) i.entity_id, i.id AS image_id FROM image i
            JOIN review selected_review ON selected_review.reviewer_id = i.entity_id
            WHERE selected_review.product_id = :id AND i.tag = 'PARTICIPANT' AND i.status = 'ACTIVE'
            ORDER BY i.entity_id, i.created_at, i.id
        ) SELECT r.id, r.rating, r.comment, r.created_at, reviewer.full_name, reviewer_images.image_id
            FROM review r LEFT JOIN participant reviewer ON reviewer.id = r.reviewer_id
            LEFT JOIN reviewer_images ON reviewer_images.entity_id = r.reviewer_id
            WHERE r.product_id = :id ORDER BY r.created_at DESC""", mapOf("id" to id)) { rs, _ ->
            DetailReview(rs.getLong("id"), rs.getInt("rating"), rs.getString("comment"),
                rs.getString("full_name") ?: "unknown", rs.nullableLong("image_id"),
                rs.getTimestamp("created_at")?.toInstant())
        }
        return base.copy(categories = categories, imageIds = images, reviews = reviews)
    }

    override fun myProducts(ownerId: Long, includeGiveaways: Boolean, filter: MyProductsFilter): List<MyProduct> {
        val params = MapSqlParameterSource().addValue("owner", ownerId).addValue("limit", filter.size)
        val predicates = mutableListOf("p.participant_id = :owner", "p.status IN ('ACTIVE','AWAITING_GIVEAWAY','BLOCKED','TIME_EXPIRED')")
        if (!includeGiveaways) predicates += "p.availability <> 'GIVEAWAY'"
        filter.name?.let {
            params.addValue("name", it)
            predicates += """(p.name ILIKE '%' || :name || '%' OR EXISTS
                (SELECT 1 FROM product_category npc JOIN category nc ON nc.id = npc.category_id
                 WHERE npc.product_id = p.id AND nc.name ILIKE '%' || :name || '%'
                 ${if (filter.categoryId != null) "AND npc.category_id = :categoryId" else ""}))"""
        }
        filter.categoryId?.let { params.addValue("categoryId", it); predicates += """EXISTS
            (SELECT 1 FROM product_category pc WHERE pc.product_id = p.id AND pc.category_id = :categoryId)""" }
        filter.originality?.let { params.addValue("originality", it); predicates += "p.originality = :originality" }
        filter.minPrice?.let { params.addValue("minPrice", it); predicates += "p.price >= :minPrice" }
        filter.maxPrice?.let { params.addValue("maxPrice", it); predicates += "p.price <= :maxPrice" }
        filter.from?.let { params.addValue("fromDate", it); predicates += "p.created_at >= :fromDate" }
        filter.to?.let { params.addValue("toDate", it); predicates += "p.created_at <= :toDate" }
        filter.preorder?.let { params.addValue("preorder", it); predicates += "(p.availability = 'PREORDER') = :preorder" }
        filter.usedFilter?.let { params.addValue("used", it); predicates += "p.used = :used" }
        when (filter.sort) {
            DetailSort.DATE_DESC -> filter.lastCreatedAt?.let {
                params.addValue("cursorDate", it).addValue("cursorId", filter.lastId)
                predicates += "(p.created_at < :cursorDate OR (p.created_at = :cursorDate AND p.id < :cursorId))"
            }
            DetailSort.PRICE_ASC -> filter.lastPrice?.let {
                params.addValue("cursorPrice", it).addValue("cursorId", filter.lastId)
                predicates += "(p.price > :cursorPrice OR (p.price = :cursorPrice AND p.id > :cursorId))"
            }
            DetailSort.PRICE_DESC -> filter.lastPrice?.let {
                params.addValue("cursorPrice", it).addValue("cursorId", filter.lastId)
                predicates += "(p.price < :cursorPrice OR (p.price = :cursorPrice AND p.id < :cursorId))"
            }
        }
        val ordering = when (filter.sort) {
            DetailSort.DATE_DESC -> "p.created_at DESC, p.id DESC"
            DetailSort.PRICE_ASC -> "p.price ASC, p.id DESC"
            DetailSort.PRICE_DESC -> "p.price DESC, p.id DESC"
        }
        val resultOrdering = ordering.replace("p.", "page.")
        val sql = """WITH page AS (
            SELECT p.id, p.name, p.count, p.price, p.prepayment_amount, p.currency::text AS currency,
            p.participant_id, p.expiration_date, p.status::text AS status,
            p.availability::text AS availability, p.used, p.created_at,
            p.giveaway_enabled, p.giveaway_start_at, p.giveaway_end_at
            FROM product p WHERE ${predicates.joinToString(" AND ")} ORDER BY $ordering LIMIT :limit
        ), main_image AS (
            SELECT DISTINCT ON (i.entity_id) i.entity_id, i.id AS image_id FROM image i
            JOIN page ON page.id = i.entity_id WHERE i.tag = 'PRODUCT' AND i.status = 'ACTIVE'
            ORDER BY i.entity_id, i.created_at, i.id
        ) SELECT page.*,
            s.login AS seller_login, COALESCE(sr.average_rating, 0) AS seller_rating,
            COALESCE(sr.total_reviews, 0) AS total_reviews, main_image.image_id
            FROM page LEFT JOIN main_image ON main_image.entity_id = page.id
            LEFT JOIN participant s ON s.id = page.participant_id
            LEFT JOIN seller_rating sr ON sr.seller_id = page.participant_id
            ORDER BY $resultOrdering"""
        val now = Instant.now()
        val rows = jdbc.query(sql, params) { rs, _ ->
            val status = effectiveStatus(rs, now)
            MyProduct(rs.getLong("id"), rs.getString("name"), rs.nullableInt("count"), rs.getFloat("price"),
                rs.nullableFloat("prepayment_amount"), rs.getString("currency"), emptyList(),
                rs.nullableLong("image_id"), rs.getLong("participant_id"),
                rs.getTimestamp("expiration_date")?.toInstant(), status, rs.getString("availability"),
                rs.nullableBoolean("used"), rs.getString("seller_login") ?: "unknown",
                rs.getFloat("seller_rating"), rs.getInt("total_reviews"), rs.getTimestamp("created_at")?.toInstant())
        }
        if (rows.isEmpty()) return rows
        val categories = categories(rows.map { it.id })
        return rows.map { it.copy(categories = categories[it.id].orEmpty()) }
    }

    private fun categories(ids: List<Long>): Map<Long, List<DetailCategory>> = jdbc.query(
        """SELECT pc.product_id, c.id, c.name FROM product_category pc JOIN category c ON c.id = pc.category_id
            WHERE pc.product_id IN (:ids) ORDER BY pc.product_id, c.id""", mapOf("ids" to ids)) { rs, _ ->
        rs.getLong("product_id") to DetailCategory(rs.getLong("id"), rs.getString("name"))
    }.groupBy({ it.first }, { it.second })

    private fun effectiveStatus(rs: ResultSet, now: Instant): String {
        val status = rs.getString("status")
        if (rs.getString("availability") != "GIVEAWAY" || !rs.getBoolean("giveaway_enabled") ||
            status !in listOf("ACTIVE", "AWAITING_GIVEAWAY")) return status
        val start = rs.getTimestamp("giveaway_start_at")?.toInstant() ?: return status
        val end = rs.getTimestamp("giveaway_end_at")?.toInstant() ?: return status
        return if (!end.isAfter(now)) "TIME_EXPIRED" else if (start.isAfter(now)) "AWAITING_GIVEAWAY" else "ACTIVE"
    }

    private fun ResultSet.nullableInt(name: String): Int? = getInt(name).takeUnless { wasNull() }
    private fun ResultSet.nullableLong(name: String): Long? = getLong(name).takeUnless { wasNull() }
    private fun ResultSet.nullableFloat(name: String): Float? = getFloat(name).takeUnless { wasNull() }
    private fun ResultSet.nullableBoolean(name: String): Boolean? = getBoolean(name).takeUnless { wasNull() }
}
