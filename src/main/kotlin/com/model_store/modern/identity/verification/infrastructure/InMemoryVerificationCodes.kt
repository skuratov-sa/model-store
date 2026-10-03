package com.model_store.modern.identity.verification.infrastructure

import com.model_store.modern.identity.verification.application.VerificationCodes
import com.model_store.modern.identity.verification.application.VerificationSecrets
import com.model_store.modern.identity.verification.domain.*
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
@Profile("modern")
class SecureVerificationSecrets : VerificationSecrets {
    private val random = SecureRandom()
    private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

    override fun code(): String = (random.nextInt(90_000) + 10_000).toString()
    override fun temporaryPassword(): String = buildString {
        repeat(12) { append(alphabet[random.nextInt(alphabet.length)]) }
    }
}

@Component
@Profile("modern")
class InMemoryVerificationCodes(private val clock: Clock = Clock.systemUTC()) : VerificationCodes {
    private data class Rate(var lastSent: Instant, var windowStart: Instant, var count: Int)
    private val entries = ConcurrentHashMap<Long, VerificationCode>()
    private val rates = ConcurrentHashMap<Long, Rate>()
    private val pepper = ByteArray(32).also { SecureRandom().nextBytes(it) }

    override fun enforceSendLimit(id: Long) {
        val now = clock.instant()
        rates.compute(id) { _, previous ->
            val rate = previous ?: Rate(now.minus(VerificationPolicy.cooldown), now, 0)
            if (!now.isBefore(rate.windowStart.plus(VerificationPolicy.window))) {
                rate.windowStart = now
                rate.count = 0
            }
            val remaining = VerificationPolicy.cooldown.toMillis() - java.time.Duration.between(rate.lastSent, now).toMillis()
            if (remaining > 0) {
                val seconds = (remaining + 999) / 1000
                throw VerificationRateLimited("VERIFICATION_COOLDOWN", "Слишком много запросов. Попробуйте снова через $seconds сек.")
            }
            if (rate.count >= VerificationPolicy.dailyLimit)
                throw VerificationRateLimited("VERIFICATION_DAILY_LIMIT", "Превышен лимит запросов на день (${VerificationPolicy.dailyLimit})")
            rate.lastSent = now
            rate.count++
            rate
        }
    }

    override fun store(id: Long, code: String) {
        entries[id] = VerificationCode(digest(id, code), clock.instant())
    }

    override fun consume(id: Long, code: String) {
        var valid = false
        entries.compute(id) { _, entry ->
            if (entry == null || entry.expired(clock.instant()) || entry.exhausted()) return@compute null
            if (MessageDigest.isEqual(entry.digest, digest(id, code))) {
                valid = true
                null
            } else {
                entry.copy(attempts = entry.attempts + 1).takeUnless { it.exhausted() }
            }
        }
        if (!valid) throw VerificationCodeInvalid()
    }

    override fun revoke(id: Long) {
        entries.remove(id)
    }

    private fun digest(id: Long, code: String): ByteArray = MessageDigest.getInstance("SHA-256").apply {
        update(pepper)
        update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(id).array())
        update(code.toByteArray(StandardCharsets.UTF_8))
    }.digest()
}
