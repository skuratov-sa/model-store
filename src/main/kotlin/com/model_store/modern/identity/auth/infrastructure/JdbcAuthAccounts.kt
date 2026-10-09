package com.model_store.modern.identity.auth.infrastructure

import com.model_store.modern.identity.auth.application.AuthAccounts
import com.model_store.modern.identity.auth.application.AuthPasswords
import com.model_store.modern.identity.auth.domain.AuthAccount
import com.model_store.modern.identity.auth.domain.AuthStatus
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
@Profile("modern")
class JdbcAuthAccounts(private val jdbc: JdbcTemplate) : AuthAccounts {
    override fun byMail(mail: String): AuthAccount? = jdbc.query(SQL + " WHERE p.mail = ?", ::map, mail).firstOrNull()

    override fun byId(id: Long): AuthAccount? = jdbc.query(SQL + " WHERE p.id = ?", ::map, id).firstOrNull()

    private fun map(rs: ResultSet, ignored: Int) = AuthAccount(
        id = rs.getLong("id"), login = rs.getString("login"), mail = rs.getString("mail"),
        fullName = rs.getString("full_name"), passwordHash = rs.getString("password"),
        role = rs.getString("role"), status = AuthStatus.valueOf(rs.getString("status")),
        imageId = rs.getLong("image_id").let { if (rs.wasNull()) null else it },
        isAgent = rs.getBoolean("is_agent"),
    )

    private companion object {
        const val SQL = """SELECT p.id, p.login, p.mail, p.full_name, p.password,
            p.role::text AS role, p.status::text AS status, p.is_agent, i.id AS image_id
            FROM participant p LEFT JOIN LATERAL (
                SELECT id FROM image WHERE entity_id = p.id AND tag = 'PARTICIPANT'
                AND status = 'ACTIVE' ORDER BY created_at LIMIT 1
            ) i ON true"""
    }
}

@Repository
@Profile("modern")
class BCryptAuthPasswords : AuthPasswords {
    private val encoder = BCryptPasswordEncoder()
    private val unknownHash = requireNotNull(encoder.encode(java.util.UUID.randomUUID().toString()))
    override fun matches(raw: String, hash: String): Boolean = encoder.matches(raw, hash)
    override fun consumeUnknown(raw: String) { encoder.matches(raw, unknownHash) }
}
