package com.model_store.modern.identity.verification

import com.model_store.modern.identity.verification.api.VerifyCodeRequest
import com.model_store.modern.identity.verification.domain.VerificationCodeInvalid
import com.model_store.modern.identity.verification.domain.VerificationRateLimited
import com.model_store.modern.identity.verification.infrastructure.InMemoryVerificationCodes
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VerificationCodesTest {
    @Test
    fun `request text never prints the code`() {
        assertFalse(VerifyCodeRequest(42, "12345").toString().contains("12345"))
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
    }

    @Test
    fun `code is one time, expires, and wrong guesses are bounded`() {
        val clock = MutableClock(Instant.parse("2026-10-03T10:00:00Z"))
        val codes = InMemoryVerificationCodes(clock)
        codes.store(1, "12345")
        codes.consume(1, "12345")
        assertThrows(VerificationCodeInvalid::class.java) { codes.consume(1, "12345") }

        codes.store(1, "12345")
        repeat(5) { assertThrows(VerificationCodeInvalid::class.java) { codes.consume(1, "00000") } }
        assertThrows(VerificationCodeInvalid::class.java) { codes.consume(1, "12345") }

        codes.store(1, "12345")
        clock.now = clock.now.plusSeconds(601)
        assertThrows(VerificationCodeInvalid::class.java) { codes.consume(1, "12345") }
    }

    @Test
    fun `send limit uses ten seconds and one hundred requests per moving day`() {
        val clock = MutableClock(Instant.parse("2026-10-03T10:00:00Z"))
        val codes = InMemoryVerificationCodes(clock)
        codes.enforceSendLimit(1)
        assertEquals("VERIFICATION_COOLDOWN", assertThrows(VerificationRateLimited::class.java) {
            codes.enforceSendLimit(1)
        }.code)
        repeat(99) { clock.now = clock.now.plusSeconds(10); codes.enforceSendLimit(1) }
        clock.now = clock.now.plusSeconds(10)
        assertEquals("VERIFICATION_DAILY_LIMIT", assertThrows(VerificationRateLimited::class.java) {
            codes.enforceSendLimit(1)
        }.code)
        clock.now = clock.now.plusSeconds(86400)
        assertDoesNotThrow { codes.enforceSendLimit(1) }
    }

    @Test
    fun `simultaneous verification consumes a code once`() {
        val codes = InMemoryVerificationCodes()
        codes.store(42, "12345")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val attempts = List(2) {
                pool.submit<Boolean> {
                    start.await()
                    try { codes.consume(42, "12345"); true } catch (_: VerificationCodeInvalid) { false }
                }
            }
            start.countDown()
            assertEquals(1, attempts.count { it.get(5, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
    }
}
