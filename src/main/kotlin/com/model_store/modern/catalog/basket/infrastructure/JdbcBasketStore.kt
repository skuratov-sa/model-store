package com.model_store.modern.catalog.basket.infrastructure

import com.model_store.modern.catalog.basket.application.*
import com.model_store.modern.catalog.product.search.application.ProductSearchRow
import com.model_store.modern.catalog.product.search.application.SearchCategory
import com.model_store.modern.catalog.product.search.domain.ProductSearchCriteria
import com.model_store.modern.catalog.product.search.domain.SearchSort
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcBasketStore(private val jdbc: NamedParameterJdbcTemplate) : BasketStore {
    override fun lockParticipant(participantId: Long): String? = jdbc.query(
        "SELECT status::text FROM participant WHERE id = :id FOR UPDATE",
        mapOf("id" to participantId),
    ) { rs, _ -> rs.getString(1) }.firstOrNull()

    override fun adultAge(participantId: Long): Int? = jdbc.query(
        "SELECT age FROM participant WHERE id = :id AND status = 'ACTIVE'",
        mapOf("id" to participantId),
    ) { rs, _ -> rs.getInt(1).takeUnless { rs.wasNull() } }.firstOrNull()

    override fun actualProduct(productId: Long): BasketProductState? = jdbc.query(
        "SELECT count FROM product WHERE id = :id AND status = 'ACTIVE' AND availability <> 'GIVEAWAY'",
        mapOf("id" to productId),
    ) { rs, _ -> BasketProductState(rs.getInt(1).takeUnless { rs.wasNull() }) }.firstOrNull()

    override fun item(participantId: Long, productId: Long): BasketLine? = jdbc.query(
        "SELECT product_id, count FROM product_basket WHERE participant_id = :owner AND product_id = :product",
        mapOf("owner" to participantId, "product" to productId),
    ) { rs, _ -> BasketLine(rs.getLong(1), rs.getInt(2)) }.firstOrNull()

    override fun items(participantId: Long): List<BasketLine> = jdbc.query(
        "SELECT product_id, count FROM product_basket WHERE participant_id = :owner ORDER BY id",
        mapOf("owner" to participantId),
    ) { rs, _ -> BasketLine(rs.getLong(1), rs.getInt(2)) }

    override fun insert(participantId: Long, productId: Long, count: Int) {
        jdbc.update(
            "INSERT INTO product_basket(participant_id, product_id, count) VALUES (:owner, :product, :count)",
            mapOf("owner" to participantId, "product" to productId, "count" to count),
        )
    }

    override fun update(participantId: Long, productId: Long, count: Int): Int = jdbc.update(
        "UPDATE product_basket SET count = :count WHERE participant_id = :owner AND product_id = :product",
        mapOf("owner" to participantId, "product" to productId, "count" to count),
    )

    override fun delete(participantId: Long, productId: Long) {
        jdbc.update(
            "DELETE FROM product_basket WHERE participant_id = :owner AND product_id = :product",
            mapOf("owner" to participantId, "product" to productId),
        )
    }

    override fun find(participantId: Long, criteria: ProductSearchCriteria, includeAdult: Boolean): List<BasketProduct> {
        val page = criteria.effectivePage
        val sort = page.sortBy ?: SearchSort.DATE_DESC
        val params = MapSqlParameterSource().addValue("owner", participantId).addValue("limit", page.size)
        val filters = mutableListOf("p.status = 'ACTIVE'", "p.availability <> 'GIVEAWAY'")
        if (!includeAdult) filters += """NOT EXISTS (
            SELECT 1 FROM product_category adult_pc JOIN category adult_c ON adult_c.id = adult_pc.category_id
            WHERE adult_pc.product_id = p.id AND adult_c.slug = 'nsfw_adult')"""
        criteria.name?.let {
            filters += """(p.name ILIKE '%' || :name || '%' OR EXISTS (
                SELECT 1 FROM product_category name_pc JOIN category name_c ON name_c.id = name_pc.category_id
                WHERE name_pc.product_id = p.id AND name_c.name ILIKE '%' || :name || '%'
                ${if (criteria.categoryId != null) "AND name_pc.category_id = :nameCategory" else ""}))"""
            params.addValue("name", it)
            if (criteria.categoryId != null) params.addValue("nameCategory", criteria.categoryId)
        }
        criteria.categoryId?.let {
            filters += "EXISTS (SELECT 1 FROM product_category pc WHERE pc.product_id = p.id AND pc.category_id = :category)"
            params.addValue("category", it)
        }
        criteria.originality?.let { filters += "p.originality = :originality"; params.addValue("originality", it) }
        criteria.participantId?.let { filters += "p.participant_id = :seller"; params.addValue("seller", it) }
        criteria.priceRange?.let {
            filters += "p.price >= :minPrice AND p.price <= :maxPrice"
            params.addValue("minPrice", it.minPrice).addValue("maxPrice", it.maxPrice)
        }
        criteria.dateRange?.start?.let { filters += "p.created_at >= :from"; params.addValue("from", it) }
        criteria.dateRange?.end?.let { filters += "p.created_at <= :to"; params.addValue("to", it) }
        criteria.preorderFilter?.let { filters += "(p.availability = 'PREORDER') = :preorder"; params.addValue("preorder", it) }
        criteria.usedFilter?.let { filters += "p.used = :used"; params.addValue("used", it) }
        when (sort) {
            SearchSort.DATE_DESC -> page.lastCreatedAt?.let {
                filters += "(p.created_at < :lastCreated OR (p.created_at = :lastCreated AND p.id < :lastId))"
                params.addValue("lastCreated", it).addValue("lastId", page.lastId)
            }
            SearchSort.PRICE_ASC -> page.lastPrice?.let {
                filters += "(p.price > :lastPrice OR (p.price = :lastPrice AND p.id > :lastId))"
                params.addValue("lastPrice", it).addValue("lastId", page.lastId)
            }
            SearchSort.PRICE_DESC -> page.lastPrice?.let {
                filters += "(p.price < :lastPrice OR (p.price = :lastPrice AND p.id < :lastId))"
                params.addValue("lastPrice", it).addValue("lastId", page.lastId)
            }
        }
        val ordering = when (sort) {
            SearchSort.DATE_DESC -> "p.created_at DESC, p.id DESC"
            SearchSort.PRICE_ASC -> "p.price ASC, p.id DESC"
            SearchSort.PRICE_DESC -> "p.price DESC, p.id DESC"
        }
        val sql = PAGE.replace("/* filters */", filters.joinToString(" AND "))
            .replace("/* page order */", ordering)
            .replace("/* result order */", ordering.replace("p.", "page."))
        val rows = jdbc.query(sql, params) { rs, _ ->
            val product = ProductSearchRow(
                id = rs.getLong("id"), name = rs.getString("name"),
                count = rs.getInt("available_count").takeUnless { rs.wasNull() },
                price = rs.getFloat("price"), prepaymentAmount = rs.getFloat("prepayment_amount").takeUnless { rs.wasNull() },
                currency = rs.getString("currency"), imageId = rs.getLong("image_id").takeUnless { rs.wasNull() },
                sellerId = rs.getLong("participant_id"), expirationDate = rs.getTimestamp("expiration_date")?.toInstant(),
                status = rs.getString("status"), availability = rs.getString("availability"),
                used = rs.getBoolean("used").takeUnless { rs.wasNull() }, sellerLogin = rs.getString("seller_login") ?: "unknown",
                sellerRating = rs.getFloat("seller_rating"), totalReviews = rs.getInt("total_reviews"),
                createdAt = rs.getTimestamp("created_at")?.toInstant(),
            )
            BasketProduct(product, rs.getInt("basket_count"))
        }
        if (rows.isEmpty()) return rows
        val categories = jdbc.query(
            """SELECT pc.product_id, c.id, c.name FROM product_category pc JOIN category c ON c.id = pc.category_id
                WHERE pc.product_id IN (:ids) ORDER BY pc.product_id, c.id""",
            mapOf("ids" to rows.map { it.product.id }),
        ) { rs, _ -> rs.getLong(1) to SearchCategory(rs.getLong(2), rs.getString(3)) }
            .groupBy({ it.first }, { it.second })
        return rows.map { it.copy(product = it.product.copy(categories = categories[it.product.id].orEmpty())) }
    }

    companion object {
        private const val PAGE = """WITH page AS (
            SELECT p.id, p.name, p.count AS available_count, p.price, p.prepayment_amount,
                   p.currency::text AS currency, p.participant_id, p.expiration_date,
                   p.status::text AS status, p.availability::text AS availability,
                   p.used, p.created_at, b.count AS basket_count
            FROM product_basket b JOIN product p ON p.id = b.product_id
            WHERE b.participant_id = :owner AND /* filters */
            ORDER BY /* page order */ LIMIT :limit
        ), main_image AS (
            SELECT DISTINCT ON (i.entity_id) i.entity_id, i.id AS image_id
            FROM image i JOIN page ON page.id = i.entity_id
            WHERE i.tag = 'PRODUCT' AND i.status = 'ACTIVE'
            ORDER BY i.entity_id, i.created_at, i.id
        )
        SELECT page.*, main_image.image_id, seller.login AS seller_login,
               COALESCE(rating.average_rating, 0) AS seller_rating,
               COALESCE(rating.total_reviews, 0) AS total_reviews
        FROM page
        LEFT JOIN main_image ON main_image.entity_id = page.id
        LEFT JOIN participant seller ON seller.id = page.participant_id
        LEFT JOIN seller_rating rating ON rating.seller_id = page.participant_id
        ORDER BY /* result order */"""
    }
}
