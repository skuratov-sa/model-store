package com.model_store.modern.identity.verification.infrastructure

import com.model_store.modern.identity.verification.application.VerificationParticipants
import com.model_store.modern.identity.verification.domain.*
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
@Profile("modern")
class JdbcVerificationParticipantRead(private val jdbc: JdbcTemplate) : VerificationParticipants {
    override fun byId(id: Long): VerificationParticipant? = jdbc.query(
        "SELECT id, mail, status::text FROM participant WHERE id = ?",
        { row, _ -> VerificationParticipant(row.getLong(1), row.getString(2), VerificationStatus.valueOf(row.getString(3))) },
        id,
    ).firstOrNull()

    override fun byMail(mail: String): VerificationParticipant? = jdbc.query(
        "SELECT id, mail, status::text FROM participant WHERE mail = ?",
        { row, _ -> VerificationParticipant(row.getLong(1), row.getString(2), VerificationStatus.valueOf(row.getString(3))) },
        mail,
    ).firstOrNull()

}
