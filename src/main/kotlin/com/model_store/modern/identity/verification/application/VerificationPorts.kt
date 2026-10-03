package com.model_store.modern.identity.verification.application

import com.model_store.modern.identity.verification.domain.VerificationParticipant

interface VerificationParticipants {
    fun byMail(mail: String): VerificationParticipant?
}

interface VerificationMail {
    fun sendVerification(mail: String, code: String)
    fun sendPasswordReset(mail: String, password: String)
}

interface VerificationCodes {
    fun enforceSendLimit(id: Long)
    fun store(id: Long, code: String)
    fun consume(id: Long, code: String)
    fun revoke(id: Long)
}

interface VerificationSecrets {
    fun code(): String
    fun temporaryPassword(): String
}

/** Implemented by identity/auth in task 06, using the legacy JWT claim contract. */
interface VerificationTokenIssuer {
    fun issue(participantId: Long): Map<String, String>
}
