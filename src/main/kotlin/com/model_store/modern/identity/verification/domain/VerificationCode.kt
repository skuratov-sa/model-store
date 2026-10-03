package com.model_store.modern.identity.verification.domain

import java.time.Duration
import java.time.Instant

object VerificationPolicy {
    val ttl: Duration = Duration.ofMinutes(10)
    val cooldown: Duration = Duration.ofSeconds(10)
    val window: Duration = Duration.ofHours(24)
    const val dailyLimit = 100
    const val maximumAttempts = 5
}

data class VerificationCode(val digest: ByteArray, val createdAt: Instant, val attempts: Int = 0) {
    fun expired(now: Instant): Boolean = now.isAfter(createdAt.plus(VerificationPolicy.ttl))
    fun exhausted(): Boolean = attempts >= VerificationPolicy.maximumAttempts
}

enum class VerificationStatus { ACTIVE, WAITING_VERIFY, BLOCKED, DELETED }

data class VerificationParticipant(val id: Long, val mail: String, val status: VerificationStatus)

class VerificationRateLimited(val code: String, message: String) : RuntimeException(message)
class VerificationMailNotFound : RuntimeException("Пользователя с такой почтой не существует")
class VerificationParticipantNotFound : RuntimeException("Такого пользователя не существует")
class VerificationCodeInvalid : RuntimeException("Неверный код подтверждения")
class VerificationAccountUnavailable(message: String) : RuntimeException(message)
