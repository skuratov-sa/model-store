package com.model_store.modern.ordering.`case`.user.infrastructure

import com.model_store.modern.ordering.`case`.user.application.*
import com.model_store.modern.ordering.`case`.user.domain.*
import com.model_store.modern.ordering.lifecycle.application.LifecycleTransaction
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
@Profile("modern")
class JdbcUserOrderCases(private val jdbc: NamedParameterJdbcTemplate, private val transaction: LifecycleTransaction) : UserOrderCaseStore {
    override fun order(id: Long): CaseOrder? = jdbc.query("""SELECT * FROM "order" WHERE id=:id""", mapOf("id" to id)) { r, _ ->
        CaseOrder(r.getLong("id"), r.getLong("customer_id"), r.getLong("seller_id"), r.getString("status"),
            r.getObject("product_id", Long::class.javaObjectType), r.getObject("count", Int::class.javaObjectType),
            r.floatOrNull("total_price"), r.floatOrNull("prepayment_amount"), r.getString("product_currency"),
            r.getObject("image_payment_proof_id", Long::class.javaObjectType))
    }.firstOrNull()

    override fun hasOpen(orderId: Long): Boolean {
        transaction.requireWritableJpa()
        return jdbc.query("SELECT id FROM order_case WHERE order_id=:id AND state='OPEN' ORDER BY id FOR UPDATE",
            mapOf("id" to orderId)) { r, _ -> r.getLong(1) }.isNotEmpty()
    }
    override fun hasAppeal(orderId: Long): Boolean = exists(orderId, "kind='PAYMENT_APPEAL'")
    private fun exists(orderId: Long, predicate: String): Boolean {
        transaction.requireWritableJpa()
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM order_case WHERE order_id=:id AND $predicate)",
            mapOf("id" to orderId), Boolean::class.javaObjectType) == true
    }

    override fun markDisputed(order: CaseOrder, comment: String): Boolean {
        transaction.requireWritableJpa()
        return jdbc.update("""UPDATE "order" SET status='DISPUTED',comment=:comment
            WHERE id=:id AND status=CAST(:expected AS order_status)""",
            mapOf("id" to order.id, "expected" to order.status, "comment" to comment)) == 1
    }

    override fun create(order: CaseOrder, kind: CaseKind, actor: Long, comment: String): Long {
        transaction.requireWritableJpa()
        // Spring Data R2DBC @Version initializes the saved legacy version to 0.
        return jdbc.queryForObject("""INSERT INTO order_case(order_id,kind,state,opened_by,opening_comment,previous_order_status,created_at)
            VALUES (:order,:kind,'OPEN',:actor,:comment,CAST(:previous AS order_status),:now) RETURNING id""",
            mapOf("order" to order.id, "kind" to kind.name, "actor" to actor, "comment" to comment,
                "previous" to order.status, "now" to java.sql.Timestamp.from(java.time.Instant.now())), Long::class.javaObjectType)!!
    }

    override fun attach(caseId: Long, imageId: Long, actor: Long) {
        transaction.requireWritableJpa()
        jdbc.update("INSERT INTO order_case_image(case_id,image_id,uploaded_by) VALUES (:case,:image,:actor)",
            mapOf("case" to caseId, "image" to imageId, "actor" to actor))
    }

    override fun cases(order: CaseOrder): List<CaseDetailView> {
        val images = jdbc.query("""SELECT id FROM image WHERE entity_id=:id AND tag='ORDER' AND status='ACTIVE' ORDER BY created_at""",
            mapOf("id" to order.id)) { r, _ -> r.getLong(1) }
        return jdbc.query("SELECT * FROM order_case WHERE order_id=:id ORDER BY created_at DESC,id DESC", mapOf("id" to order.id)) { r, _ ->
            val id = r.getLong("id")
            val c = CaseView(id,r.getLong("version"),order.id,r.getString("kind"),r.getString("state"),
                r.getObject("opened_by",Long::class.javaObjectType),r.getString("opening_comment"),r.getString("previous_order_status"),
                r.getString("telegram_url"),r.getObject("resolved_by",Long::class.javaObjectType),r.getString("outcome"),
                r.getString("resolution_comment"),r.getTimestamp("created_at")?.toInstant(),r.getTimestamp("resolved_at")?.toInstant())
            CaseDetailView(c,order,images,jdbc.query("SELECT image_id FROM order_case_image WHERE case_id=:id ORDER BY image_id",
                mapOf("id" to id)) { image, _ -> image.getLong(1) })
        }
    }
    private fun ResultSet.floatOrNull(column: String): Float? = getFloat(column).takeUnless { wasNull() }
}
