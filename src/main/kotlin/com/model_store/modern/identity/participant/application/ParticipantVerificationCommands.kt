package com.model_store.modern.identity.participant.application

import com.model_store.modern.identity.participant.domain.ParticipantStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Outcomes that the verification context can map to its own HTTP errors. */
sealed class ParticipantVerificationFailure(val participantId: Long, reason: String) : RuntimeException(reason) {
    class NotFound(id: Long) : ParticipantVerificationFailure(id, "Participant $id not found")
    class AlreadyActive(id: Long) : ParticipantVerificationFailure(id, "Participant $id already active")
    class Blocked(id: Long) : ParticipantVerificationFailure(id, "Participant $id blocked")
    class Deleted(id: Long) : ParticipantVerificationFailure(id, "Participant $id deleted")
}

interface ParticipantVerificationCommands {
    fun activate(participantId: Long): Long
    fun resetPassword(participantId: Long, newPassword: String): Long
}

@Service
class ParticipantVerificationService(
    private val store: ParticipantStore,
    private val passwords: ParticipantPasswords,
) : ParticipantVerificationCommands {
    @Transactional("transactionManager")
    override fun activate(participantId: Long): Long {
        val participant = store.findForUpdate(participantId)
            ?: throw ParticipantVerificationFailure.NotFound(participantId)
        when (participant.status) {
            ParticipantStatus.WAITING_VERIFY -> store.save(participant.copy(status = ParticipantStatus.ACTIVE))
            ParticipantStatus.ACTIVE -> throw ParticipantVerificationFailure.AlreadyActive(participantId)
            ParticipantStatus.BLOCKED -> throw ParticipantVerificationFailure.Blocked(participantId)
            ParticipantStatus.DELETED -> throw ParticipantVerificationFailure.Deleted(participantId)
        }
        return participantId
    }
    @Transactional("transactionManager")
    override fun resetPassword(participantId: Long, newPassword: String): Long {
        require(newPassword.isNotEmpty()) { "Новый пароль обязателен" }
        val participant = store.findForUpdate(participantId)
            ?: throw ParticipantVerificationFailure.NotFound(participantId)
        when (participant.status) {
            ParticipantStatus.WAITING_VERIFY, ParticipantStatus.ACTIVE ->
                store.save(participant.copy(status = ParticipantStatus.ACTIVE, passwordHash = passwords.hash(newPassword)))
            ParticipantStatus.BLOCKED -> throw ParticipantVerificationFailure.Blocked(participantId)
            ParticipantStatus.DELETED -> throw ParticipantVerificationFailure.Deleted(participantId)
        }
        return participantId
    }
}
