package com.model_store.modern.catalog.product.command.infrastructure

import com.model_store.modern.catalog.product.command.application.*
import com.model_store.modern.catalog.product.command.domain.*
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
@Profile("modern")
class JdbcProductCommandStore(private val jdbc: NamedParameterJdbcTemplate) : ProductCommandStore {
    override fun lockProduct(id: Long, mode: LockMode): ProductRecord? {
        val predicate = when (mode) {
            LockMode.ACTIVE_ORDINARY -> "AND status = 'ACTIVE' AND availability <> 'GIVEAWAY'"
            LockMode.EXTENDABLE -> "AND status IN ('ACTIVE','TIME_EXPIRED') AND availability <> 'GIVEAWAY'"
            LockMode.NON_DELETED -> "AND status <> 'DELETED'"
        }
        return jdbc.query(
            """SELECT id, participant_id, name, description, price, prepayment_amount, count,
                      currency::text AS currency, originality, status::text AS status, expiration_date,
                      availability::text AS availability, used, external_url, giveaway_enabled
               FROM product WHERE id = :id $predicate FOR UPDATE""",
            mapOf("id" to id), ::record,
        ).firstOrNull()
    }

    override fun create(record: ProductRecord): Long = requireNotNull(jdbc.queryForObject(
        """INSERT INTO product(name,description,price,prepayment_amount,count,currency,originality,
                               participant_id,status,expiration_date,availability,used,external_url)
           VALUES (:name,:description,:price,:prepayment,:count,CAST(:currency AS currency),:originality,
                   :owner,CAST(:status AS product_status),:expiration,CAST(:availability AS product_availability),
                   :used,:externalUrl) RETURNING id""",
        parameters(record), Long::class.java,
    ))

    override fun update(record: ProductRecord) {
        val changed = jdbc.update(
            """UPDATE product SET name=:name, description=:description, price=:price,
                 prepayment_amount=:prepayment, count=:count, currency=CAST(:currency AS currency),
                 originality=:originality, status=CAST(:status AS product_status), expiration_date=:expiration,
                 availability=CAST(:availability AS product_availability), used=:used, external_url=:externalUrl,
                 giveaway_enabled=:giveawayEnabled WHERE id=:id""",
            parameters(record) + ("id" to record.id),
        )
        check(changed == 1) { "Product disappeared while locked" }
    }

    override fun replaceCategories(id: Long, categoryIds: List<Long>) {
        jdbc.update("DELETE FROM product_category WHERE product_id=:id", mapOf("id" to id))
        categoryIds.forEach { categoryId ->
            jdbc.update("INSERT INTO product_category(product_id,category_id) VALUES (:product,:category)",
                mapOf("product" to id, "category" to categoryId))
        }
    }

    private fun parameters(p: ProductRecord): Map<String, Any?> = mapOf(
        "name" to p.name, "description" to p.description, "price" to p.price,
        "prepayment" to p.prepaymentAmount, "count" to p.count, "currency" to p.currency,
        "originality" to p.originality, "owner" to p.ownerId, "status" to p.status.name,
        "expiration" to OffsetDateTime.ofInstant(p.expirationDate, ZoneOffset.UTC),
        "availability" to p.availability.name,
        "used" to p.used, "externalUrl" to p.externalUrl, "giveawayEnabled" to p.giveawayEnabled,
    )

    private fun record(rs: ResultSet, unused: Int) = ProductRecord(
        id = rs.getLong("id"), ownerId = rs.getLong("participant_id"), name = rs.getString("name"),
        description = rs.getString("description"), price = rs.getFloat("price"),
        prepaymentAmount = rs.getFloat("prepayment_amount").takeUnless { rs.wasNull() },
        count = rs.getInt("count").takeUnless { rs.wasNull() }, currency = rs.getString("currency"),
        originality = rs.getString("originality"), status = ProductState.valueOf(rs.getString("status")),
        expirationDate = rs.getTimestamp("expiration_date").toInstant(),
        availability = ProductAvailability.valueOf(rs.getString("availability")),
        used = rs.getBoolean("used"), externalUrl = rs.getString("external_url"),
        giveawayEnabled = rs.getBoolean("giveaway_enabled"),
    )
}
