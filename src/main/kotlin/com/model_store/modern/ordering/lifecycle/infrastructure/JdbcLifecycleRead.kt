package com.model_store.modern.ordering.lifecycle.infrastructure

import com.model_store.modern.ordering.lifecycle.application.*
import com.model_store.modern.ordering.lifecycle.domain.LifecycleFailure
import com.model_store.modern.ordering.lifecycle.domain.LifecycleProblem
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

internal fun ResultSet.nullFloat(column: String): Float? = getFloat(column).takeUnless { wasNull() }

/** Scalar SQL read models reproduce the legacy enrichers without importing foreign entities or services. */
@Repository
@Profile("modern")
class JdbcLifecycleRead(private val jdbc: NamedParameterJdbcTemplate) : LifecycleRead, SelectedOrderRead {
    override fun orders(participantId: Long, seller: Boolean): List<OrderView> {
        val column = if (seller) "seller_id" else "customer_id"
        return jdbc.query("""SELECT * FROM "order" WHERE $column=:id""", mapOf("id" to participantId)) { rs, _ ->
            project(rs, seller)
        }
    }

    override fun ordersByIds(ids: List<Long>, seller: Boolean): List<OrderView> {
        require(ids.size <= 100) { "Selected order projection is limited to 100 IDs" }
        if (ids.isEmpty()) return emptyList()
        return jdbc.query("""SELECT * FROM "order" WHERE id IN (:ids)""", mapOf("ids" to ids)) { rs, _ ->
            project(rs, seller)
        }
    }

    private fun project(rs: ResultSet, seller: Boolean): OrderView {
        val id = rs.getLong("id")
        val owner = rs.getObject("seller_id", Long::class.javaObjectType)
        val counterparty = rs.getObject(if (seller) "customer_id" else "seller_id", Long::class.javaObjectType)
            ?: throw LifecycleFailure(LifecycleProblem.PARTICIPANT_NOT_FOUND)
        val productId = rs.getObject("product_id", Long::class.javaObjectType)
            ?: throw LifecycleFailure(LifecycleProblem.PRODUCT_NOT_FOUND)
        val transferId = rs.getObject("transfer_id", Long::class.javaObjectType)
            ?: throw LifecycleFailure(LifecycleProblem.TRANSFER_NOT_FOUND)
        val addressId = rs.getObject("address_id", Long::class.javaObjectType)
            ?: throw LifecycleFailure(LifecycleProblem.TRANSFER_NOT_FOUND)
        val history = jdbc.query("""SELECT status,comment,changed_at FROM order_status_history
            WHERE order_id=:id ORDER BY changed_at""", mapOf("id" to id)) { h, _ ->
            HistoryView(h.getString(1), h.getString(2), h.getTimestamp(3)?.toInstant()) }
        return OrderView(id, rs.getString("status"), rs.nullFloat("total_price"),
            rs.nullFloat("prepayment_amount"), owner, rs.getTimestamp("created_at")?.toInstant()?.toString(),
            user(counterparty), product(productId, owner, rs), transfer(transferId, addressId),
            images(id, "ORDER"), rs.getObject("image_payment_proof_id", Long::class.javaObjectType), rs.getString("delivery_url"), history)
    }

    private fun images(entity: Long, tag: String): List<Long> = jdbc.query("""SELECT id FROM image
        WHERE entity_id=:id AND tag::text=:tag AND status='ACTIVE' ORDER BY created_at""",
        mapOf("id" to entity, "tag" to tag)) { rs, _ -> rs.getLong(1) }

    private fun mainImage(entity: Long, tag: String): Long? {
        // Legacy participant enrichment consumes findActualIdsByEntity().next(): no ID tie-break
        // or SQL LIMIT. Product enrichment uses the separate created_at,id main-image query.
        if (tag == "PARTICIPANT") return images(entity, tag).firstOrNull()
        return jdbc.query("""SELECT id FROM image
        WHERE entity_id=:id AND tag::text=:tag AND status='ACTIVE' ORDER BY created_at,id LIMIT 1""",
        mapOf("id" to entity, "tag" to tag)) { rs, _ -> rs.getLong(1) }.firstOrNull()
    }

    private fun user(id: Long): UserView = jdbc.query("SELECT login,phone_number,mail FROM participant WHERE id=:id AND status='ACTIVE'",
        mapOf("id" to id)) { rs, _ -> UserView(id, mainImage(id, "PARTICIPANT"), rs.getString("login"),
        rs.getString("phone_number"), rs.getString("mail")) }.firstOrNull() ?: UserView(id)

    private fun product(id: Long, sellerId: Long?, order: ResultSet): ProductView {
        val current = jdbc.query("""SELECT p.*,u.login AS owner_login,
            COALESCE(r.average_rating,0) AS rating,COALESCE(r.total_reviews,0) AS reviews
            FROM product p LEFT JOIN participant u ON u.id=p.participant_id
            LEFT JOIN seller_rating r ON r.seller_id=p.participant_id WHERE p.id=:id""", mapOf("id" to id)) { rs, _ ->
            val categories = jdbc.query("""SELECT c.id,c.name FROM product_category pc JOIN category c ON c.id=pc.category_id
                WHERE pc.product_id=:id ORDER BY c.id""", mapOf("id" to id)) { c, _ -> CategoryView(c.getLong(1), c.getString(2)) }
            val effectiveStatus = if (rs.getString("availability") == "GIVEAWAY" && rs.getBoolean("giveaway_enabled") &&
                rs.getString("status") in setOf("ACTIVE", "AWAITING_GIVEAWAY")) {
                val now = java.time.Instant.now()
                when {
                    rs.getTimestamp("giveaway_end_at")?.toInstant()?.isAfter(now) == false -> "TIME_EXPIRED"
                    rs.getTimestamp("giveaway_start_at")?.toInstant()?.isAfter(now) == true -> "AWAITING_GIVEAWAY"
                    else -> "ACTIVE"
                }
            } else rs.getString("status")
            ProductView(id, null, null, null, rs.nullFloat("prepayment_amount"), null, categories,
                mainImage(id, "PRODUCT"), rs.getObject("participant_id", Long::class.javaObjectType), rs.getTimestamp("expiration_date")?.toInstant(),
                effectiveStatus, null, rs.getObject("used", Boolean::class.javaObjectType), null,
                rs.getString("owner_login") ?: "unknown", rs.getFloat("rating"), rs.getInt("reviews"), rs.getTimestamp("created_at")?.toInstant())
        }.firstOrNull() ?: ProductView(id, null, null, null, currency = null, sellerId = sellerId, status = "DELETED", availability = null)
        return current.copy(name = order.getString("product_name"), count = order.getObject("count", Int::class.javaObjectType),
            price = order.nullFloat("product_unit_price"), currency = order.getString("product_currency"),
            availability = order.getString("product_availability"))
    }

    private fun transfer(id: Long, address: Long): TransferView = jdbc.query("""SELECT t.*,
        a.country,a.city,a.street,a.house_number,a.apartment_number,a."index" FROM transfer t CROSS JOIN address a
        WHERE t.id=:id AND a.id=:address""", mapOf("id" to id, "address" to address)) { rs, _ ->
        val imageId = when (rs.getString("sending")) {
            "RUSSIAN_POST" -> 1L; "PRODUCT_PICKUP" -> 2L; "TRANSPORT_COMPANY" -> 3L; "FREE_POST" -> 4L
            else -> null
        }
        val fullAddress = "${rs.getString("country")} г. ${rs.getString("city")} ул. ${rs.getString("street")} ${rs.getString("house_number")} кв. ${rs.getString("apartment_number")}, индекс: ${rs.getObject("index")}"
        TransferView(id, address, imageId, fullAddress, rs.nullFloat("price"), rs.getString("currency"))
    }.firstOrNull() ?: TransferView(id, address)
}
