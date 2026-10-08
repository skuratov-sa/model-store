package com.model_store.modern.catalog.product.command.infrastructure

import com.model_store.modern.catalog.product.command.application.SellerEligibilityPort
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/** Read-only seller and identity facts, addressed by participant ID. */
@Repository
@Profile("modern")
class JdbcSellerEligibilityPort(private val jdbc: NamedParameterJdbcTemplate) : SellerEligibilityPort {
    override fun hasActiveTransfer(ownerId: Long) = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM transfer WHERE participant_id=:id AND status='ACTIVE')",
        mapOf("id" to ownerId), Boolean::class.java) == true

    override fun hasSocialNetwork(ownerId: Long) = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM social_network WHERE participant_id=:id)",
        mapOf("id" to ownerId), Boolean::class.java) == true

    override fun isAgent(ownerId: Long) = jdbc.queryForObject(
        "SELECT COALESCE((SELECT is_agent FROM participant WHERE id=:id), false)",
        mapOf("id" to ownerId), Boolean::class.java) == true
}
