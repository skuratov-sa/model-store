package com.model_store.modern.catalog.product.search.infrastructure

import com.model_store.modern.catalog.product.search.application.ProductSearchReadPort
import com.model_store.modern.catalog.product.search.application.ProductSearchRow
import com.model_store.modern.catalog.product.search.application.SearchCategory
import com.model_store.modern.catalog.product.search.domain.ProductSearchCriteria
import com.model_store.modern.catalog.product.search.domain.SearchSort
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp

@Repository
class JdbcProductSearchReadPort(private val jdbc: NamedParameterJdbcTemplate) : ProductSearchReadPort {
    override fun adultAge(participantId: Long): Int? = jdbc.query(
        "SELECT age FROM participant WHERE id = :id AND status = 'ACTIVE'",
        mapOf("id" to participantId),
    ) { rs, _ -> rs.getInt("age").takeUnless { rs.wasNull() } }.firstOrNull()

    override fun findProducts(criteria: ProductSearchCriteria, includeAdult: Boolean): List<ProductSearchRow> {
        val page = criteria.effectivePage
        val sort = page.sortBy ?: SearchSort.DATE_DESC
        val params = MapSqlParameterSource().addValue("limit", page.size)
        val filters = mutableListOf(BASE_VISIBILITY)
        if (!includeAdult) filters += ADULT_VISIBILITY
        criteria.name?.let {
            filters += if (criteria.categoryId == null) NAME_MATCH else NAME_MATCH_WITH_CATEGORY
            params.addValue("name", it)
            if (criteria.categoryId != null) params.addValue("nameCategoryId", criteria.categoryId)
        }
        criteria.categoryId?.let {
            filters += CATEGORY_MATCH
            params.addValue("categoryId", it)
        }
        criteria.originality?.let { filters += "p.originality = :originality"; params.addValue("originality", it) }
        criteria.participantId?.let { filters += "p.participant_id = :participantId"; params.addValue("participantId", it) }
        criteria.priceRange?.let {
            filters += "p.price >= :minPrice AND p.price <= :maxPrice"
            params.addValue("minPrice", it.minPrice).addValue("maxPrice", it.maxPrice)
        }
        criteria.dateRange?.start?.let { filters += "p.created_at >= :dateFrom"; params.addValue("dateFrom", it) }
        criteria.dateRange?.end?.let { filters += "p.created_at <= :dateTo"; params.addValue("dateTo", it) }
        criteria.preorderFilter?.let {
            filters += "(p.availability = 'PREORDER') = :preorder"
            params.addValue("preorder", it)
        }
        criteria.usedFilter?.let { filters += "p.used = :used"; params.addValue("used", it) }
        when (sort) {
            SearchSort.DATE_DESC -> page.lastCreatedAt?.let {
                filters += "(p.created_at < :lastCreatedAt OR (p.created_at = :lastCreatedAt AND p.id < :lastId))"
                params.addValue("lastCreatedAt", Timestamp.from(it)).addValue("lastId", page.lastId)
            }
            SearchSort.PRICE_ASC -> page.lastPrice?.let {
                // Preserve the legacy cursor even though id DESC makes equal-price pages repeat/skip rows.
                filters += "(p.price > :lastPrice OR (p.price = :lastPrice AND p.id > :lastId))"
                params.addValue("lastPrice", it).addValue("lastId", page.lastId)
            }
            SearchSort.PRICE_DESC -> page.lastPrice?.let {
                filters += "(p.price < :lastPrice OR (p.price = :lastPrice AND p.id < :lastId))"
                params.addValue("lastPrice", it).addValue("lastId", page.lastId)
            }
        }
        val sql = PRODUCT_PAGE.replace("/* predicates */", filters.joinToString("\n AND "))
            .replace("/* page ordering */", SORT_ORDER.getValue(sort))
            .replace("/* result ordering */", SORT_ORDER.getValue(sort).replace("p.", "page."))
        val products = jdbc.query(sql, params) { rs, _ -> mapProduct(rs) }
        if (products.isEmpty()) return products

        // One bulk lookup retains the legacy category ordering without multiplying page rows.
        val categories = jdbc.query(
            CATEGORIES,
            mapOf("ids" to products.map { it.id }),
        ) { rs, _ -> rs.getLong("product_id") to SearchCategory(rs.getLong("category_id"), rs.getString("category_name")) }
            .groupBy({ it.first }, { it.second })
        return products.map { it.copy(categories = categories[it.id].orEmpty()) }
    }

    override fun findNames(search: String?): List<String> = jdbc.query(
        NAMES,
        MapSqlParameterSource("search", search),
    ) { rs, _ -> rs.getString("name") }

    private fun mapProduct(rs: ResultSet): ProductSearchRow = ProductSearchRow(
        id = rs.getLong("id"),
        name = rs.getString("name"),
        count = rs.getInt("count").takeUnless { rs.wasNull() },
        price = rs.getFloat("price"),
        prepaymentAmount = rs.getFloat("prepayment_amount").takeUnless { rs.wasNull() },
        currency = rs.getString("currency"),
        imageId = rs.getLong("image_id").takeUnless { rs.wasNull() },
        sellerId = rs.getLong("participant_id"),
        expirationDate = rs.getTimestamp("expiration_date")?.toInstant(),
        status = rs.getString("status"),
        availability = rs.getString("availability"),
        used = rs.getBoolean("used").takeUnless { rs.wasNull() },
        sellerLogin = rs.getString("seller_login") ?: "unknown",
        sellerRating = rs.getFloat("seller_rating"),
        totalReviews = rs.getInt("total_reviews"),
        createdAt = rs.getTimestamp("created_at")?.toInstant(),
    )

    companion object {
        private const val BASE_VISIBILITY = "p.status = 'ACTIVE' AND p.availability <> 'GIVEAWAY' AND (p.count IS NULL OR p.count > 0)"
        private const val ADULT_VISIBILITY = """NOT EXISTS (
            SELECT 1 FROM product_category adult_pc
            JOIN category adult_c ON adult_c.id = adult_pc.category_id
            WHERE adult_pc.product_id = p.id AND adult_c.slug = 'nsfw_adult'
        )"""
        private const val NAME_MATCH = """(p.name ILIKE '%' || :name || '%' OR EXISTS (
            SELECT 1 FROM product_category name_pc
            JOIN category name_c ON name_c.id = name_pc.category_id
            WHERE name_pc.product_id = p.id AND name_c.name ILIKE '%' || :name || '%'
        ))"""
        private const val NAME_MATCH_WITH_CATEGORY = """(p.name ILIKE '%' || :name || '%' OR EXISTS (
            SELECT 1 FROM product_category name_pc
            JOIN category name_c ON name_c.id = name_pc.category_id
            WHERE name_pc.product_id = p.id AND name_c.name ILIKE '%' || :name || '%'
              AND name_pc.category_id = :nameCategoryId
        ))"""
        private const val CATEGORY_MATCH = """EXISTS (
            SELECT 1 FROM product_category category_pc
            WHERE category_pc.product_id = p.id AND category_pc.category_id = :categoryId
        )"""
        private val SORT_ORDER = mapOf(
            SearchSort.DATE_DESC to "p.created_at DESC, p.id DESC",
            SearchSort.PRICE_ASC to "p.price ASC, p.id DESC",
            SearchSort.PRICE_DESC to "p.price DESC, p.id DESC",
        )
        private const val PRODUCT_PAGE = """WITH page AS (
            SELECT p.id, p.name, p.count, p.price, p.prepayment_amount,
                   p.currency::text AS currency, p.participant_id, p.expiration_date,
                   p.status::text AS status, p.availability::text AS availability,
                   p.used, p.created_at
            FROM product p
            WHERE /* predicates */
            ORDER BY /* page ordering */
            LIMIT :limit
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
        ORDER BY /* result ordering */"""
        private const val CATEGORIES = """SELECT pc.product_id, c.id AS category_id, c.name AS category_name
            FROM product_category pc JOIN category c ON c.id = pc.category_id
            WHERE pc.product_id IN (:ids)
            ORDER BY pc.product_id, c.id"""
        private const val NAMES = """SELECT DISTINCT result.name FROM (
            SELECT p.name FROM product p
            WHERE (p.name ILIKE '%' || :search || '%' OR similarity(p.name, :search) > 0.25)
              AND p.status = 'ACTIVE' AND p.availability <> 'GIVEAWAY'
              AND (p.count IS NULL OR p.count > 0)
            UNION
            SELECT c.name FROM category c
            WHERE c.name ILIKE '%' || :search || '%' OR similarity(c.name, :search) > 0.25
        ) AS result ORDER BY result.name LIMIT 10"""
    }
}
