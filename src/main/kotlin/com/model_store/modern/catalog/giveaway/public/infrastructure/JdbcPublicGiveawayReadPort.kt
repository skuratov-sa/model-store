package com.model_store.modern.catalog.giveaway.`public`.infrastructure

import com.model_store.modern.catalog.giveaway.`public`.application.PublicGiveawayReadPort
import com.model_store.modern.catalog.giveaway.`public`.domain.PublicGiveaway
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

@Repository
class JdbcPublicGiveawayReadPort(private val jdbc: NamedParameterJdbcTemplate) : PublicGiveawayReadPort {
    override fun active(): PublicGiveaway? = find(
        """SELECT p.id, p.name, p.description, p.giveaway_telegram_url, p.giveaway_start_at,
            p.giveaway_end_at, p.giveaway_winners_count, p.giveaway_rules, p.giveaway_home_text
            FROM product p WHERE p.availability = 'GIVEAWAY'
              AND p.status IN ('ACTIVE', 'AWAITING_GIVEAWAY')
              AND p.giveaway_enabled AND p.giveaway_end_at > CURRENT_TIMESTAMP
            ORDER BY CASE WHEN p.giveaway_start_at <= CURRENT_TIMESTAMP THEN 0 ELSE 1 END,
                     p.giveaway_start_at, p.id LIMIT 1""", emptyMap<String, Any>())

    override fun byProductId(productId: Long): PublicGiveaway? = find(
        """SELECT p.id, p.name, p.description, p.giveaway_telegram_url, p.giveaway_start_at,
            p.giveaway_end_at, p.giveaway_winners_count, p.giveaway_rules, p.giveaway_home_text
            FROM product p WHERE p.id = :productId AND p.availability = 'GIVEAWAY'
              AND p.status IN ('ACTIVE', 'AWAITING_GIVEAWAY')
              AND p.giveaway_enabled AND p.giveaway_end_at > CURRENT_TIMESTAMP""",
        mapOf("productId" to productId))

    private fun find(sql: String, params: Map<String, *>): PublicGiveaway? {
        val row = jdbc.query(sql, params) { rs, _ -> rs.toGiveawayRow() }.firstOrNull() ?: return null
        val images = jdbc.query("""SELECT id FROM image WHERE entity_id = :id AND tag = 'PRODUCT'
            AND status = 'ACTIVE' ORDER BY created_at""", mapOf("id" to row.id)) { rs, _ -> rs.getLong(1) }
        // Legacy computes this from the start date, even if the stored status is stale.
        val status = if (row.startAt.isAfter(Instant.now())) "AWAITING_GIVEAWAY" else "ACTIVE"
        return PublicGiveaway(row.id, row.name, row.description, images, row.telegramUrl,
            row.startAt, row.endAt, row.winnersCount, row.rules, row.homeText, status)
    }

    private fun ResultSet.toGiveawayRow() = GiveawayRow(getLong("id"), getString("name"),
        getString("description"), getString("giveaway_telegram_url"),
        getTimestamp("giveaway_start_at").toInstant(), getTimestamp("giveaway_end_at").toInstant(),
        getInt("giveaway_winners_count").takeUnless { wasNull() }, getString("giveaway_rules"),
        getString("giveaway_home_text"))

    private data class GiveawayRow(val id: Long, val name: String?, val description: String?,
        val telegramUrl: String?, val startAt: Instant, val endAt: Instant,
        val winnersCount: Int?, val rules: String?, val homeText: String?)
}
