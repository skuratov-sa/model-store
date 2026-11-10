package com.model_store.modern.ordering.`case`.admin.infrastructure

import com.model_store.modern.ordering.`case`.admin.application.*
import com.model_store.modern.ordering.`case`.admin.domain.*
import com.model_store.modern.ordering.`case`.user.application.*
import com.model_store.modern.ordering.lifecycle.application.LifecycleTransaction
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
@Profile("modern")
class JdbcAdminOrderCases(private val jdbc: NamedParameterJdbcTemplate,
    private val transaction: LifecycleTransaction, private val orders: UserOrderCaseStore) : AdminOrderCaseStore {
    override fun find(caseId: Long): AdminCase? = select(caseId, false)
    override fun lock(caseId: Long): AdminCase? {
        transaction.requireWritableJpa()
        return select(caseId, true)
    }
    private fun select(id: Long, lock: Boolean): AdminCase? = jdbc.query(
        "SELECT id,order_id,version,kind,state,previous_order_status FROM order_case WHERE id=:id ${if (lock) "FOR UPDATE" else ""}",
        mapOf("id" to id)) { r, _ -> AdminCase(r.getLong("id"), r.getLong("order_id"), r.getLong("version"),
            r.getString("kind"), r.getString("state"), r.getString("previous_order_status")) }.firstOrNull()

    override fun queue(state: CaseState, limit: Int, offset: Long): List<AdminCaseSummary> = jdbc.query(
        """SELECT c.id,c.order_id,c.kind,c.state,c.opened_by,c.opening_comment,c.created_at,
            o.status::text AS order_status FROM order_case c JOIN "order" o ON o.id=c.order_id
            WHERE c.state=:state ORDER BY c.created_at DESC,c.id DESC LIMIT :limit OFFSET :offset""",
        mapOf("state" to state.name, "limit" to limit, "offset" to offset)) { r, _ ->
        AdminCaseSummary(r.getLong("id"), r.getLong("order_id"), r.getString("kind"), r.getString("state"),
            r.getObject("opened_by", Long::class.javaObjectType), r.getString("opening_comment"),
            r.getTimestamp("created_at")?.toInstant(), r.getString("order_status"))
    }

    override fun detail(caseId: Long): CaseDetailView? {
        // A detail request must not load every other case and its evidence for the order.
        val case = jdbc.query("SELECT * FROM order_case WHERE id=:id", mapOf("id" to caseId)) { r, _ ->
            CaseView(r.getLong("id"), r.getLong("version"), r.getLong("order_id"), r.getString("kind"), r.getString("state"),
                r.getObject("opened_by", Long::class.javaObjectType), r.getString("opening_comment"), r.getString("previous_order_status"),
                r.getString("telegram_url"), r.getObject("resolved_by", Long::class.javaObjectType), r.getString("outcome"),
                r.getString("resolution_comment"), r.getTimestamp("created_at")?.toInstant(), r.getTimestamp("resolved_at")?.toInstant())
        }.firstOrNull() ?: return null
        val order = orders.order(case.orderId) ?: return null
        val images = jdbc.query("""SELECT id FROM image WHERE entity_id=:id AND tag='ORDER' AND status='ACTIVE' ORDER BY created_at""",
            mapOf("id" to order.id)) { r, _ -> r.getLong(1) }
        val evidence = jdbc.query("SELECT image_id FROM order_case_image WHERE case_id=:id ORDER BY image_id",
            mapOf("id" to caseId)) { r, _ -> r.getLong(1) }
        return CaseDetailView(case, order, images, evidence)
    }

    override fun telegram(case: AdminCase, url: String): Boolean {
        transaction.requireWritableJpa()
        return jdbc.update("""UPDATE order_case SET telegram_url=:url,version=version+1
            WHERE id=:id AND order_id=:order AND state='OPEN' AND version=:version""",
            mapOf("id" to case.id, "order" to case.orderId, "version" to case.version, "url" to url)) == 1
    }
    override fun transition(orderId: Long, expected: String, target: CaseResolutionTarget, comment: String): Boolean {
        transaction.requireWritableJpa()
        return jdbc.update("""UPDATE "order" SET status=CAST(:target AS order_status),comment=:comment
            WHERE id=:id AND status=CAST(:expected AS order_status)""",
            mapOf("id" to orderId, "expected" to expected, "target" to target.name, "comment" to comment)) == 1
    }
    override fun resolve(case: AdminCase, adminId: Long, outcome: CaseOutcome, comment: String): Boolean {
        transaction.requireWritableJpa()
        return jdbc.update("""UPDATE order_case SET state='RESOLVED',resolved_by=:admin,outcome=:outcome,
            resolution_comment=:comment,resolved_at=now(),version=version+1
            WHERE id=:id AND order_id=:order AND state='OPEN' AND version=:version""",
            mapOf("id" to case.id, "order" to case.orderId, "version" to case.version,
                "admin" to adminId, "outcome" to outcome.name, "comment" to comment)) == 1
    }
}
