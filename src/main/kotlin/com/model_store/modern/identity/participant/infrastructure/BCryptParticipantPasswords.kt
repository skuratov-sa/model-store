package com.model_store.modern.identity.participant.infrastructure

import com.model_store.modern.identity.participant.application.ParticipantPasswords
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component

@Component
class BCryptParticipantPasswords : ParticipantPasswords {
    private val encoder = BCryptPasswordEncoder()

    override fun hash(raw: String): String = requireNotNull(encoder.encode(raw))
    override fun matches(raw: String, hash: String): Boolean = encoder.matches(raw, hash)
}
