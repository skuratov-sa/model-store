package com.model_store.modern.identity.participant.infrastructure

import com.model_store.modern.identity.participant.application.ParticipantImagePort
import com.model_store.modern.identity.participant.domain.ParticipantImageNotFound
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcParticipantImagePort(private val jdbc: JdbcTemplate) : ParticipantImagePort {
    override fun replace(participantId: Long, imageId: Long) {
        val claimed = jdbc.update(
            """UPDATE image SET entity_id = ? WHERE id = ? AND tag = 'PARTICIPANT'
                AND status = 'ACTIVE' AND (entity_id IS NULL OR entity_id = ?)""",
            participantId, imageId, participantId,
        )
        if (claimed != 1) throw ParticipantImageNotFound(imageId)
        jdbc.update("UPDATE image SET status = 'DELETE' WHERE entity_id = ? AND tag = 'PARTICIPANT' AND id <> ? AND status = 'ACTIVE'", participantId, imageId)
    }
}
