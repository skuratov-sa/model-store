package com.model_store.modern.ordering.create.infrastructure

import com.model_store.modern.ordering.create.application.*
import com.model_store.modern.ordering.create.domain.BookingDraft
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
@Profile("modern")
class JdbcOrderCreationStore(private val jdbc: NamedParameterJdbcTemplate) : OrderCreationRead, OrderCreationWrite {
    override fun productOwners(productIds: List<Long>): List<Long> = if (productIds.isEmpty()) emptyList() else jdbc.query(
        """SELECT DISTINCT participant_id FROM product WHERE id IN (:ids)
           AND status='ACTIVE' AND availability <> 'GIVEAWAY' ORDER BY participant_id""",
        mapOf("ids" to productIds),
    ) { rs, _ -> rs.getLong(1) }

    override fun isAgent(participantId: Long): Boolean = jdbc.queryForObject(
        "SELECT COALESCE((SELECT is_agent FROM participant WHERE id=:id), false)",
        mapOf("id" to participantId), Boolean::class.java,
    ) == true

    override fun actualProductOwner(productId: Long): Long? = jdbc.query(
        """SELECT participant_id FROM product WHERE id=:id AND status='ACTIVE'
           AND availability <> 'GIVEAWAY'""",
        mapOf("id" to productId),
    ) { rs, _ -> rs.getLong(1) }.firstOrNull()

    override fun addresses(customerId: Long): List<OrderAddress> = jdbc.query(
        """SELECT a.id, a.country, a.city, a.street, a.house_number, a.apartment_number,
                  a."index", a.status::text AS status
           FROM participant_address pa JOIN address a ON a.id=pa.address_id
           WHERE pa.participant_id=:customer""",
        mapOf("customer" to customerId),
    ) { rs, _ -> OrderAddress(
        rs.getLong("id"), rs.getString("country"), rs.getString("city"), rs.getString("street"),
        rs.getString("house_number"), rs.getString("apartment_number"),
        rs.getInt("index").takeUnless { rs.wasNull() }, rs.getString("status"),
    ) }

    override fun transfers(sellerId: Long): List<OrderTransfer> = jdbc.query(
        """SELECT id, sending::text AS sending, price, currency::text AS currency,
                  participant_id, status::text AS status FROM transfer
           WHERE participant_id=:seller AND status='ACTIVE'""",
        mapOf("seller" to sellerId),
    ) { rs, _ -> OrderTransfer(
        rs.getLong("id"), rs.getString("sending"), rs.getInt("price"), rs.getString("currency"),
        rs.getLong("participant_id"), rs.getString("status"),
    ) }

    override fun validAddress(customerId: Long, addressId: Long): Boolean {
        val params = mapOf("customer" to customerId, "address" to addressId)
        // Address update/delete locks the address first, then delete removes the participant link.
        val address = jdbc.query("SELECT id FROM address WHERE id=:address FOR UPDATE", params) { rs, _ ->
            rs.getLong(1)
        }
        if (address.isEmpty()) return false
        val links = jdbc.query(
            """SELECT id FROM participant_address WHERE participant_id=:customer
               AND address_id=:address ORDER BY id FOR UPDATE""",
            params,
        ) { rs, _ -> rs.getLong(1) }
        return links.isNotEmpty()
    }

    override fun activeTransferIds(sellerId: Long): Set<Long> = jdbc.query(
        """SELECT id FROM transfer WHERE participant_id=:seller AND status='ACTIVE'
           ORDER BY id FOR UPDATE""",
        mapOf("seller" to sellerId),
    ) { rs, _ -> rs.getLong(1) }.toSet()

    override fun insert(draft: BookingDraft): Long = requireNotNull(jdbc.queryForObject(
            """INSERT INTO "order" (seller_id, customer_id, count, status, product_id,
                 product_name, product_unit_price, product_currency, product_availability,
                 address_id, transfer_id, total_price, prepayment_amount, comment)
               VALUES (:seller, :customer, :count, CAST('BOOKED' AS order_status), :product,
                 :name, :price, CAST(:currency AS currency), CAST(:availability AS product_availability),
                 :address, :transfer, :total, :prepayment, :comment) RETURNING id""",
            mapOf(
                "seller" to draft.sellerId, "customer" to draft.customerId, "count" to draft.count,
                "product" to draft.productId, "name" to draft.productName, "price" to draft.productUnitPrice,
                "currency" to draft.productCurrency, "availability" to draft.productAvailability,
                "address" to draft.addressId, "transfer" to draft.transferId,
                "total" to draft.totalPrice, "prepayment" to draft.prepaymentAmount,
                "comment" to draft.comment,
            ), Long::class.java,
        ))
}
