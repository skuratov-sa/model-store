package com.model_store.modern.identity.verification.application

import com.model_store.modern.identity.participant.application.ParticipantVerificationCommands
import com.model_store.modern.identity.participant.application.ParticipantVerificationFailure
import com.model_store.modern.identity.participant.application.ParticipantRegistrationMail
import com.model_store.modern.identity.verification.domain.VerificationAccountUnavailable
import com.model_store.modern.identity.verification.domain.VerificationCodeInvalid
import com.model_store.modern.identity.verification.domain.VerificationMailNotFound
import com.model_store.modern.identity.verification.domain.VerificationParticipantNotFound
import com.model_store.modern.identity.verification.domain.VerificationStatus
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/** Mail is external I/O; no database transaction spans any of these methods. */
@Service
@Profile("modern")
class VerificationUseCases(
    private val participants: VerificationParticipants,
    private val participantCommands: ParticipantVerificationCommands,
    private val mail: VerificationMail,
    private val codes: VerificationCodes,
    private val secrets: VerificationSecrets,
) : ParticipantRegistrationMail {
    // A fixed set of locks serializes mail issuance, code replacement, consumption and reset per participant.
    // It does not create a database transaction around SMTP and only protects this JVM.
    private val participantLocks = Array(256) { Any() }
    private fun lockFor(id: Long) = participantLocks[(id.hashCode() and Int.MAX_VALUE) % participantLocks.size]

    fun resend(email: String): Long {
        val initial = participants.byMail(email) ?: throw VerificationMailNotFound()
        return synchronized(lockFor(initial.id)) {
            val participant = participants.byMail(email) ?: throw VerificationMailNotFound()
            if (participant.id != initial.id) throw VerificationMailNotFound()
            if (participant.status == VerificationStatus.WAITING_VERIFY) {
                codes.enforceSendLimit(participant.id)
                val code = secrets.code()
                mail.sendVerification(participant.mail, code)
                codes.store(participant.id, code)
            }
            participant.id
        }
    }

    /** Registration calls this after its participant transaction has committed. */
    fun sendRegistration(email: String): Long {
        val participant = participants.byMail(email) ?: throw VerificationMailNotFound()
        return synchronized(lockFor(participant.id)) {
            val current = participants.byMail(email) ?: throw VerificationMailNotFound()
            if (current.id != participant.id) throw VerificationMailNotFound()
            val code = secrets.code()
            mail.sendVerification(current.mail, code)
            codes.store(current.id, code)
            current.id
        }
    }

    override fun send(participantId: Long) = synchronized(lockFor(participantId)) {
        val participant = participants.byId(participantId) ?: throw VerificationParticipantNotFound()
        val code = secrets.code()
        mail.sendVerification(participant.mail, code)
        codes.store(participant.id, code)
    }

    fun verify(userId: Long, code: String) {
        synchronized(lockFor(userId)) {
            // Preserve the legacy order: a valid code is consumed even when activation fails.
            codes.consume(userId, code)
            try {
                participantCommands.activate(userId)
            } catch (_: ParticipantVerificationFailure) {
                throw VerificationCodeInvalid()
            }
        }
    }

    fun resetPassword(email: String) {
        val participant = participants.byMail(email) ?: throw VerificationMailNotFound()
        synchronized(lockFor(participant.id)) {
            val current = participants.byMail(email) ?: throw VerificationMailNotFound()
            if (current.id != participant.id) throw VerificationMailNotFound()
            codes.enforceSendLimit(current.id)
            val password = secrets.temporaryPassword()
            try {
                participantCommands.resetPassword(current.id, password)
            } catch (_: ParticipantVerificationFailure.NotFound) {
                throw VerificationParticipantNotFound()
            } catch (failure: ParticipantVerificationFailure) {
                throw VerificationAccountUnavailable(when (failure) {
                    is ParticipantVerificationFailure.Blocked -> "Аккаунт заблокирован"
                    is ParticipantVerificationFailure.Deleted -> "Аккаунт удален"
                    else -> "Учетная запись недоступна"
                })
            }
            codes.revoke(current.id)
            // Legacy updates the password before mailing. A mail error cannot roll back that write.
            mail.sendPasswordReset(current.mail, password)
        }
    }
}
