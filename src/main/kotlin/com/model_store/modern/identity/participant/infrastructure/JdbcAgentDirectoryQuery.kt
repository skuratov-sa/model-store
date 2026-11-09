package com.model_store.modern.identity.participant.infrastructure

import com.model_store.modern.identity.participant.application.*
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Profile("modern")
class JdbcAgentDirectoryQuery(private val jdbc: NamedParameterJdbcTemplate) : AgentDirectoryQuery {
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    override fun agents(callerAdminId: Long): List<AgentDirectoryEntry> {
        requireActiveAdmin(callerAdminId)
        return jdbc.query("SELECT id, login, status::text FROM participant WHERE is_agent ORDER BY id ASC",
            emptyMap<String, Any>()) { rs, _ -> AgentDirectoryEntry(rs.getLong(1), rs.getString(2), rs.getString(3)) }
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    override fun requireActiveAdmin(callerAdminId: Long) {
        val allowed = jdbc.query("SELECT role='ADMIN' AND status='ACTIVE' AND NOT is_agent FROM participant WHERE id=:id",
            mapOf("id" to callerAdminId)) { rs, _ -> rs.getBoolean(1) }.firstOrNull()
        if (allowed != true) throw AgentProfileFailure.AdminForbidden()
    }
}
