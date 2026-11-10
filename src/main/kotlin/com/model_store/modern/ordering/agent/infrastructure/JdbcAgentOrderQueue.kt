package com.model_store.modern.ordering.agent.infrastructure

import com.model_store.modern.ordering.agent.application.*
import com.model_store.modern.ordering.lifecycle.application.SelectedOrderRead
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
@Profile("modern")
class JdbcAgentOrderQueue(private val jdbc: NamedParameterJdbcTemplate,
    private val selected: SelectedOrderRead) : AgentOrderQueue {
    override fun page(agentId: Long?, status: String?, limit: Int, offset: Long): List<AgentOrderView> {
        require(limit in 1..100 && offset >= 0)
        val entries = jdbc.query("""SELECT o.id,o.seller_id FROM "order" o
            JOIN participant p ON p.id=o.seller_id
            WHERE p.is_agent=true AND (CAST(:agent AS bigint) IS NULL OR o.seller_id=:agent)
              AND (CAST(:status AS text) IS NULL OR o.status::text=:status)
            ORDER BY o.created_at DESC,o.id DESC LIMIT :limit OFFSET :offset""",
            mapOf("agent" to agentId, "status" to status, "limit" to limit, "offset" to offset)) { rs, _ ->
                rs.getLong(1) to rs.getLong(2)
            }
        val orders = selected.ordersByIds(entries.map { it.first }, true).associateBy { it.orderId }
        // The global queue does not lock every seller. A different admin may transition/reassign
        // a selected order before projection under READ_COMMITTED; do not return a different owner
        // or a status outside the requested filter. Do not follow it to a new participant set.
        val retained = entries.mapNotNull { (id, owner) -> orders[id]?.takeIf {
            it.sellerId == owner && (status == null || it.actualStatus == status)
        } }
        if (retained.isEmpty()) return emptyList()
        // Legacy enriches seller login AFTER order enrichment, rather than at candidate selection.
        val logins = jdbc.query("""SELECT id,login FROM participant WHERE id IN (:ids) AND is_agent=true""",
            mapOf("ids" to retained.mapNotNull { it.sellerId }.distinct())) { rs, _ ->
                rs.getLong(1) to rs.getString(2)
            }.toMap()
        return retained.mapNotNull { order ->
            if (logins.containsKey(order.sellerId)) AgentOrderView(order, logins[order.sellerId]) else null
        }
    }
}
