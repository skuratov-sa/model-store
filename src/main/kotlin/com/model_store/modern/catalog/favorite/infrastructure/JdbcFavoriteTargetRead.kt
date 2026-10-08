package com.model_store.modern.catalog.favorite.infrastructure

import com.model_store.modern.catalog.favorite.application.*
import com.model_store.modern.catalog.favorite.domain.FavoriteCriteria
import com.model_store.modern.catalog.favorite.domain.FavoriteSort
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp

@Repository
class JdbcFavoriteTargetRead(private val jdbc: NamedParameterJdbcTemplate) : FavoriteTargetRead {
    override fun activeParticipant(ownerId: Long): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM participant WHERE id = :id AND status = 'ACTIVE')",
        mapOf("id" to ownerId), Boolean::class.java,
    ) == true

    override fun adultAge(ownerId: Long): Int? = jdbc.query(
        "SELECT age FROM participant WHERE id = :id AND status = 'ACTIVE'",
        mapOf("id" to ownerId),
    ) { rs, _ -> rs.getInt(1).takeUnless { rs.wasNull() } }.firstOrNull()

    override fun actualProduct(productId: Long): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM product WHERE id = :id AND status = 'ACTIVE' AND availability <> 'GIVEAWAY')",
        mapOf("id" to productId), Boolean::class.java,
    ) == true

    override fun visibleFavorites(ownerId: Long, criteria: FavoriteCriteria, includeAdult: Boolean): List<FavoriteProduct> {
        val page = criteria.effectivePage
        val sort = page.sortBy ?: FavoriteSort.DATE_DESC
        val params = MapSqlParameterSource("ownerId", ownerId).addValue("limit", page.size)
        val filters = mutableListOf(
            "p.status = 'ACTIVE'", "p.availability <> 'GIVEAWAY'",
            "(p.count IS NULL OR p.count > 0)",
            "EXISTS (SELECT 1 FROM product_favorite f WHERE f.participant_id = :ownerId AND f.product_id = p.id)",
        )
        if (!includeAdult) filters += """NOT EXISTS (
            SELECT 1 FROM product_category adult_pc JOIN category adult_c ON adult_c.id = adult_pc.category_id
            WHERE adult_pc.product_id = p.id AND adult_c.slug = 'nsfw_adult')"""
        criteria.name?.let {
            filters += """(p.name ILIKE '%' || :name || '%' OR EXISTS (
                SELECT 1 FROM product_category name_pc JOIN category name_c ON name_c.id = name_pc.category_id
                WHERE name_pc.product_id = p.id AND name_c.name ILIKE '%' || :name || '%'
                ${if (criteria.categoryId == null) "" else "AND name_pc.category_id = :categoryId"}))"""
            params.addValue("name", it)
        }
        criteria.categoryId?.let {
            filters += "EXISTS (SELECT 1 FROM product_category pc WHERE pc.product_id = p.id AND pc.category_id = :categoryId)"
            params.addValue("categoryId", it)
        }
        criteria.originality?.let { filters += "p.originality = :originality"; params.addValue("originality", it) }
        criteria.participantId?.let { filters += "p.participant_id = :sellerId"; params.addValue("sellerId", it) }
        criteria.priceRange?.let {
            filters += "p.price BETWEEN :minPrice AND :maxPrice"
            params.addValue("minPrice", it.minPrice).addValue("maxPrice", it.maxPrice)
        }
        criteria.dateRange?.start?.let { filters += "p.created_at >= :dateFrom"; params.addValue("dateFrom", it) }
        criteria.dateRange?.end?.let { filters += "p.created_at <= :dateTo"; params.addValue("dateTo", it) }
        criteria.preorderFilter?.let { filters += "(p.availability = 'PREORDER') = :preorder"; params.addValue("preorder", it) }
        criteria.usedFilter?.let { filters += "p.used = :used"; params.addValue("used", it) }
        when (sort) {
            FavoriteSort.DATE_DESC -> page.lastCreatedAt?.let {
                filters += "(p.created_at < :lastCreatedAt OR (p.created_at = :lastCreatedAt AND p.id < :lastId))"
                params.addValue("lastCreatedAt", Timestamp.from(it)).addValue("lastId", page.lastId)
            }
            FavoriteSort.PRICE_ASC -> page.lastPrice?.let {
                filters += "(p.price > :lastPrice OR (p.price = :lastPrice AND p.id > :lastId))"
                params.addValue("lastPrice", it).addValue("lastId", page.lastId)
            }
            FavoriteSort.PRICE_DESC -> page.lastPrice?.let {
                filters += "(p.price < :lastPrice OR (p.price = :lastPrice AND p.id < :lastId))"
                params.addValue("lastPrice", it).addValue("lastId", page.lastId)
            }
        }
        val order = when (sort) {
            FavoriteSort.DATE_DESC -> "p.created_at DESC, p.id DESC"
            FavoriteSort.PRICE_ASC -> "p.price ASC, p.id DESC"
            FavoriteSort.PRICE_DESC -> "p.price DESC, p.id DESC"
        }
        val sql = PAGE.replace("/* predicates */", filters.joinToString(" AND "))
            .replace("/* page ordering */", order)
            .replace("/* result ordering */", order.replace("p.", "page."))
        val products = jdbc.query(sql, params) { rs, _ -> product(rs) }
        if (products.isEmpty()) return products
        val categories = jdbc.query(CATEGORIES, mapOf("ids" to products.map { it.id })) { rs, _ ->
            rs.getLong("product_id") to FavoriteCategory(rs.getLong("category_id"), rs.getString("category_name"))
        }.groupBy({ it.first }, { it.second })
        return products.map { it.copy(categories = categories[it.id].orEmpty()) }
    }

    private fun product(rs: ResultSet) = FavoriteProduct(
        id = rs.getLong("id"), name = rs.getString("name"),
        count = rs.getInt("count").takeUnless { rs.wasNull() },
        price = rs.getFloat("price"),
        prepaymentAmount = rs.getFloat("prepayment_amount").takeUnless { rs.wasNull() },
        currency = rs.getString("currency"), categories = emptyList(),
        imageId = rs.getLong("image_id").takeUnless { rs.wasNull() },
        sellerId = rs.getLong("participant_id"), expirationDate = rs.getTimestamp("expiration_date")?.toInstant(),
        status = rs.getString("status"), availability = rs.getString("availability"),
        used = rs.getBoolean("used").takeUnless { rs.wasNull() },
        sellerLogin = rs.getString("seller_login") ?: "unknown",
        sellerRating = rs.getFloat("seller_rating"), totalReviews = rs.getInt("total_reviews"),
        createdAt = rs.getTimestamp("created_at")?.toInstant(),
    )

    companion object {
        private const val PAGE = """WITH page AS (
            SELECT p.id, p.name, p.count, p.price, p.prepayment_amount,
                   p.currency::text AS currency, p.participant_id, p.expiration_date,
                   p.status::text AS status, p.availability::text AS availability,
                   p.used, p.created_at
            FROM product p WHERE /* predicates */
            ORDER BY /* page ordering */ LIMIT :limit
        ), main_image AS (
            SELECT DISTINCT ON (i.entity_id) i.entity_id, i.id AS image_id
            FROM image i JOIN page ON page.id = i.entity_id
            WHERE i.tag = 'PRODUCT' AND i.status = 'ACTIVE'
            ORDER BY i.entity_id, i.created_at, i.id
        )
        SELECT page.*, main_image.image_id, seller.login AS seller_login,
               COALESCE(rating.average_rating, 0) AS seller_rating,
               COALESCE(rating.total_reviews, 0) AS total_reviews
        FROM page LEFT JOIN main_image ON main_image.entity_id = page.id
        LEFT JOIN participant seller ON seller.id = page.participant_id
        LEFT JOIN seller_rating rating ON rating.seller_id = page.participant_id
        ORDER BY /* result ordering */"""
        private const val CATEGORIES = """SELECT pc.product_id, c.id AS category_id, c.name AS category_name
            FROM product_category pc JOIN category c ON c.id = pc.category_id
            WHERE pc.product_id IN (:ids) ORDER BY pc.product_id, c.id"""
    }
}
