package com.model_store.modern.ordering.lifecycle.infrastructure

import com.model_store.modern.ordering.lifecycle.application.LifecycleStore
import com.model_store.modern.ordering.lifecycle.application.OrderPaymentCases
import com.model_store.modern.ordering.lifecycle.application.LifecycleTransaction
import com.model_store.modern.ordering.lifecycle.domain.*
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
@Profile("modern")
class JdbcLifecycleStore(private val jdbc: NamedParameterJdbcTemplate, private val transaction: LifecycleTransaction) : LifecycleStore, OrderPaymentCases {
    override fun observeOrder(orderId: Long): LifecycleOrder? = order(orderId, false)

    override fun lockParticipant(id: Long) {
        transaction.requireWritableJpa()
        jdbc.query("SELECT id FROM participant WHERE id=:id FOR UPDATE", mapOf("id" to id)) { rs, _ -> rs.getLong(1) }
    }
    override fun isAgent(id: Long): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM participant WHERE id=:id AND is_agent=true)", mapOf("id" to id), Boolean::class.javaObjectType) == true

    override fun lockOrder(orderId: Long): LifecycleOrder? {
        transaction.requireWritableJpa()
        return order(orderId, true)
    }

    private fun order(orderId: Long, lock: Boolean): LifecycleOrder? = jdbc.query(
        """SELECT * FROM "order" WHERE id=:id ${if (lock) "FOR UPDATE" else ""}""", mapOf("id" to orderId),
    ) { rs, _ -> LifecycleOrder(rs.getLong("id"), OrderParties(rs.getLong("seller_id"), rs.getLong("customer_id")),
        OrderStatus.valueOf(rs.getString("status")), rs.nullFloat("prepayment_amount"),
        rs.getObject("product_id", Long::class.javaObjectType), rs.getInt("count"), rs.getObject("image_payment_proof_id", Long::class.javaObjectType)) }.firstOrNull()

    override fun transition(order: LifecycleOrder, target: OrderStatus, comment: String?, proofId: Long?, deliveryUrl: String?, action: OrderAction): Boolean {
        transaction.requireWritableJpa()
        val extra = when (action) {
            OrderAction.PAY, OrderAction.PREPAY -> ", image_payment_proof_id=:proof"
            OrderAction.SHIP -> ", delivery_url=:url"
            else -> ""
        }
        // Existing trigger is the sole writer of status history, including comment and changed_at.
        return jdbc.update("""UPDATE "order" SET status=CAST(:target AS order_status), comment=:comment $extra
            WHERE id=:id AND status=CAST(:expected AS order_status)""", mapOf("id" to order.id,
            "target" to target.name, "expected" to order.status.name, "comment" to comment,
            "proof" to proofId, "url" to deliveryUrl)) == 1
    }

    override fun dismissCancellation(orderId: Long) {
        transaction.requireWritableJpa()
        jdbc.update("""UPDATE order_case SET state='RESOLVED', outcome='REJECTED',
            resolution_comment='Получено подтверждение оплаты', resolved_at=now(), version=version+1
            WHERE order_id=:id AND kind='CANCELLATION_REQUEST' AND state='OPEN'""", mapOf("id" to orderId))
    }
    override fun lockPaymentDispute(orderId: Long): PaymentCase? {
        transaction.requireWritableJpa()
        return jdbc.query("""SELECT id,kind,state,previous_order_status FROM order_case
        WHERE order_id=:id AND state='OPEN' FOR UPDATE""",
        mapOf("id" to orderId)) { rs, _ -> PaymentCase(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)) }.firstOrNull()
    }

    override fun disputedPayment(order: LifecycleOrder, proofId: Long?): Boolean {
        transaction.requireWritableJpa()
        return jdbc.update("""UPDATE "order"
        SET image_payment_proof_id=:proof WHERE id=:id AND status='DISPUTED' AND image_payment_proof_id=:previous""",
        mapOf("id" to order.id, "proof" to proofId, "previous" to order.paymentProofId)) == 1
    }

    override fun addCaseEvidence(caseId: Long, imageId: Long?, uploaderId: Long): Boolean {
        transaction.requireWritableJpa()
        return jdbc.update(
        "INSERT INTO order_case_image(case_id,image_id,uploaded_by) VALUES (:case,:image,:uploader)",
        mapOf("case" to caseId, "image" to imageId, "uploader" to uploaderId)) == 1
    }
}
