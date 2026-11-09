package com.model_store.modern.catalog.product.agent.infrastructure

import com.model_store.modern.catalog.product.agent.application.AgentProductReadStore
import com.model_store.modern.catalog.product.agent.application.AgentProductSnapshot
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
@Profile("modern")
class JdbcAgentProductReadStore(private val jdbc: NamedParameterJdbcTemplate) : AgentProductReadStore {
    // The legacy administrative reads deliberately include every stored status and raw schedule status.
    override fun products(agentId: Long): List<AgentProductSnapshot> = jdbc.query(
        "SELECT * FROM product WHERE participant_id=:id", mapOf("id" to agentId), ::row)

    // Legacy's plural /products/{id} reads any product, including a non-agent owner's product.
    override fun product(productId: Long): AgentProductSnapshot? = jdbc.query(
        "SELECT * FROM product WHERE id=:id", mapOf("id" to productId), ::row).firstOrNull()

    private fun row(rs: ResultSet, ignored: Int) = AgentProductSnapshot(
        id = rs.getLong("id"), name = rs.getString("name"), description = rs.getString("description"),
        price = rs.getFloat("price"), prepaymentAmount = rs.getFloat("prepayment_amount").takeUnless { rs.wasNull() },
        count = rs.getInt("count").takeUnless { rs.wasNull() }, currency = rs.getString("currency"),
        originality = rs.getString("originality"), participantId = rs.getLong("participant_id"),
        status = rs.getString("status"), expirationDate = rs.getTimestamp("expiration_date").toInstant(),
        availability = rs.getString("availability"), used = rs.getBoolean("used").takeUnless { rs.wasNull() },
        externalUrl = rs.getString("external_url"), giveawayEnabled = rs.getBoolean("giveaway_enabled"),
        giveawayTelegramUrl = rs.getString("giveaway_telegram_url"),
        giveawayStartAt = rs.getTimestamp("giveaway_start_at")?.toInstant(),
        giveawayEndAt = rs.getTimestamp("giveaway_end_at")?.toInstant(),
        giveawayWinnersCount = rs.getInt("giveaway_winners_count").takeUnless { rs.wasNull() },
        giveawayRules = rs.getString("giveaway_rules"), giveawayHomeText = rs.getString("giveaway_home_text"),
        createdAt = rs.getTimestamp("created_at")?.toInstant())
}
