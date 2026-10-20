package com.model_store.modern.catalog.giveaway.admin.infrastructure

import com.model_store.modern.catalog.giveaway.admin.application.AdminGiveawayStore
import com.model_store.modern.catalog.giveaway.admin.domain.AdminGiveaway
import com.model_store.modern.catalog.giveaway.admin.domain.GiveawayCategory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
@Profile("modern")
class JdbcAdminGiveawayStore(private val jdbc: NamedParameterJdbcTemplate) : AdminGiveawayStore {
    private val columns = """p.id, p.name, p.description, p.count, p.price, p.prepayment_amount,
        p.currency::text AS currency, p.originality, p.participant_id, p.status::text AS status,
        p.expiration_date, p.availability::text AS availability, p.used, p.external_url,
        p.giveaway_enabled, p.giveaway_telegram_url, p.giveaway_start_at, p.giveaway_end_at,
        p.giveaway_winners_count, p.giveaway_rules, p.giveaway_home_text, p.created_at"""

    override fun isGiveaway(id: Long): Boolean = jdbc.queryForObject(
        "SELECT COALESCE((SELECT availability='GIVEAWAY' FROM product WHERE id=:id),false)",
        mapOf("id" to id), Boolean::class.java) == true

    override fun lockProduct(id: Long): AdminGiveaway? = jdbc.query(
        "SELECT $columns FROM product p WHERE p.id=:id AND p.status <> 'DELETED' FOR UPDATE",
        mapOf("id" to id), ::row).firstOrNull()

    override fun history(adminId: Long, limit: Int, offset: Long): List<AdminGiveaway> = jdbc.query(
        """SELECT $columns FROM product p JOIN participant owner ON owner.id = p.participant_id
            WHERE p.giveaway_end_at IS NOT NULL AND p.status <> 'DELETED'
              AND (p.participant_id = :adminId OR owner.is_agent)
            ORDER BY p.giveaway_end_at DESC, p.id DESC LIMIT :limit OFFSET :offset""",
        mapOf("adminId" to adminId, "limit" to limit, "offset" to offset), ::row)

    override fun find(id: Long, adminId: Long): AdminGiveaway? = jdbc.query(
        """SELECT $columns FROM product p JOIN participant owner ON owner.id = p.participant_id
            WHERE p.id = :id AND p.status <> 'DELETED' AND p.giveaway_end_at IS NOT NULL
              AND (p.participant_id = :adminId OR owner.is_agent)""",
        mapOf("id" to id, "adminId" to adminId), ::row).firstOrNull()

    override fun details(id: Long): Pair<List<Long>, List<GiveawayCategory>> {
        val images = jdbc.query("""SELECT id FROM image WHERE entity_id=:id AND tag='PRODUCT'
            AND status='ACTIVE' ORDER BY created_at""", mapOf("id" to id)) { rs, _ -> rs.getLong(1) }
        val categories = jdbc.query("""SELECT c.id, c.name FROM product_category pc
            JOIN category c ON c.id=pc.category_id WHERE pc.product_id=:id ORDER BY pc.id""",
            mapOf("id" to id)) { rs, _ -> GiveawayCategory(rs.getLong(1), rs.getString(2)) }
        return images to categories
    }

    override fun lockActivation() {
        check(jdbc.query("""SELECT value FROM dictionary
            WHERE type='PRODUCT_AVAILABILITY' AND value='GIVEAWAY' FOR UPDATE""",
            emptyMap<String, Any>()) { rs, _ -> rs.getString(1) }.singleOrNull() != null)
    }

    override fun lockOwned(id: Long, adminId: Long): AdminGiveaway? = jdbc.query(
        """SELECT $columns FROM product p JOIN participant owner ON owner.id=p.participant_id
            WHERE p.id=:id AND p.status <> 'DELETED' AND p.giveaway_end_at IS NOT NULL
              AND (p.participant_id=:adminId OR owner.is_agent) FOR UPDATE OF p""",
        mapOf("id" to id, "adminId" to adminId), ::row).firstOrNull()

    override fun overlaps(excludeId: Long, start: java.time.Instant, end: java.time.Instant): Boolean =
        jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM product WHERE availability='GIVEAWAY'
            AND giveaway_enabled AND status IN ('ACTIVE','AWAITING_GIVEAWAY') AND id <> :id
            AND giveaway_start_at < :endAt AND giveaway_end_at > :startAt)""",
            mapOf("id" to excludeId, "startAt" to OffsetDateTime.ofInstant(start, ZoneOffset.UTC),
                "endAt" to OffsetDateTime.ofInstant(end, ZoneOffset.UTC)), Boolean::class.java) == true

    override fun hasActiveOrders(id: Long): Boolean = jdbc.queryForObject(
        """SELECT EXISTS(SELECT 1 FROM "order" WHERE product_id=:id
            AND status NOT IN ('COMPLETED','FAILED','CANCELLED'))""",
        mapOf("id" to id), Boolean::class.java) == true

    override fun saveSettings(product: AdminGiveaway) {
        check(jdbc.update("""UPDATE product SET availability='GIVEAWAY', count=NULL,
            prepayment_amount=NULL, giveaway_enabled=:enabled,
            giveaway_telegram_url=:telegramUrl, giveaway_start_at=:startAt, giveaway_end_at=:endAt,
            giveaway_winners_count=:winnersCount, giveaway_rules=:rules, giveaway_home_text=:homeText,
            status=CAST(:status AS product_status) WHERE id=:id""", mapOf(
            "id" to product.id, "enabled" to product.enabled, "telegramUrl" to product.telegramUrl,
            "startAt" to product.startAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
            "endAt" to product.endAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
            "winnersCount" to product.winnersCount, "rules" to product.rules,
            "homeText" to product.homeText, "status" to product.status)) == 1)
    }

    private fun row(rs: ResultSet, unused: Int) = AdminGiveaway(
        id = rs.getLong("id"), name = rs.getString("name"), description = rs.getString("description"),
        count = rs.getInt("count").takeUnless { rs.wasNull() }, price = rs.getFloat("price"),
        prepaymentAmount = rs.getFloat("prepayment_amount").takeUnless { rs.wasNull() },
        currency = rs.getString("currency"), originality = rs.getString("originality"),
        ownerId = rs.getLong("participant_id"), status = rs.getString("status"),
        expirationDate = rs.getTimestamp("expiration_date").toInstant(),
        availability = rs.getString("availability"),
        used = rs.getBoolean("used").takeUnless { rs.wasNull() }, externalUrl = rs.getString("external_url"),
        enabled = rs.getBoolean("giveaway_enabled"), telegramUrl = rs.getString("giveaway_telegram_url"),
        startAt = rs.getTimestamp("giveaway_start_at")?.toInstant(),
        endAt = rs.getTimestamp("giveaway_end_at")?.toInstant(),
        winnersCount = rs.getInt("giveaway_winners_count").takeUnless { rs.wasNull() },
        rules = rs.getString("giveaway_rules"), homeText = rs.getString("giveaway_home_text"),
        createdAt = rs.getTimestamp("created_at")?.toInstant(),
    )
}
