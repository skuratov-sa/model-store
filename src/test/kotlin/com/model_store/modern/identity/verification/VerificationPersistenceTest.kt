package com.model_store.modern.identity.verification

import com.amazonaws.services.s3.AmazonS3
import com.model_store.modern.identity.verification.application.VerificationMail
import com.model_store.modern.identity.verification.application.VerificationUseCases
import com.model_store.modern.identity.verification.domain.VerificationStatus
import com.model_store.modern.identity.verification.domain.VerificationCodeInvalid
import com.model_store.modern.identity.verification.domain.VerificationAccountUnavailable
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringBootTest
@ActiveProfiles("modern")
class VerificationPersistenceTest {
    @field:MockitoBean lateinit var s3: AmazonS3
    @field:MockitoBean lateinit var jwtDecoder: JwtDecoder
    @field:MockitoBean lateinit var mail: VerificationMail
    @Autowired lateinit var useCases: VerificationUseCases
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `registration, resend, and verify use existing schema`() {
        val id = participant("verify-${System.nanoTime()}@example.test")
        val email = jdbc.queryForObject("SELECT mail FROM participant WHERE id = ?", String::class.java, id)!!
        assertEquals(id, useCases.sendRegistration(email))
        verify(mail).sendVerification(eq(email) ?: email, matches("[0-9]{5}") ?: "")
        assertEquals(id, useCases.resend(email))
        val code = mockingDetails(mail).invocations.last { it.method.name == "sendVerification" }.arguments[1] as String
        useCases.verify(id, code)
        assertThrows(VerificationCodeInvalid::class.java) { useCases.verify(id, code) }
        val status = jdbc.queryForObject("SELECT status::text FROM participant WHERE id = ?", String::class.java, id)
        assertEquals(VerificationStatus.ACTIVE.name, status)
    }

    @Test
    fun `mail failure leaves password committed as in legacy`() {
        val email = "reset-${System.nanoTime()}@example.test"
        val id = participant(email)
        useCases.sendRegistration(email)
        val code = mockingDetails(mail).invocations.last { it.method.name == "sendVerification" }.arguments[1] as String
        doThrow(IllegalStateException("smtp unavailable")).`when`(mail)
            .sendPasswordReset(eq(email) ?: email, anyString() ?: "")
        assertThrows(IllegalStateException::class.java) { useCases.resetPassword(email) }
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM participant WHERE id = ?", String::class.java, id))
        val sentPassword = mockingDetails(mail).invocations.last { it.method.name == "sendPasswordReset" }.arguments[1] as String
        val storedHash = jdbc.queryForObject("SELECT password FROM participant WHERE id = ?", String::class.java, id)!!
        assertTrue(BCryptPasswordEncoder().matches(sentPassword, storedHash))
        assertNotEquals(sentPassword, storedHash)
        assertThrows(VerificationCodeInvalid::class.java) { useCases.verify(id, code) }
    }

    @Test
    fun `blocked account is not reset or mailed`() {
        val email = "blocked-${System.nanoTime()}@example.test"
        val id = participant(email)
        jdbc.update("UPDATE participant SET status = 'BLOCKED'::participant_status WHERE id = ?", id)
        assertThrows(VerificationAccountUnavailable::class.java) { useCases.resetPassword(email) }
        assertEquals("old-password-hash", jdbc.queryForObject("SELECT password FROM participant WHERE id = ?", String::class.java, id))
        verify(mail, never()).sendPasswordReset(eq(email) ?: email, anyString() ?: "")
    }

    @Test
    fun `blocked participant cannot activate with a valid code and code is consumed`() {
        val email = "blocked-verify-${System.nanoTime()}@example.test"
        val id = participant(email)
        useCases.sendRegistration(email)
        val code = mockingDetails(mail).invocations.last { it.method.name == "sendVerification" }.arguments[1] as String
        jdbc.update("UPDATE participant SET status = 'BLOCKED'::participant_status WHERE id = ?", id)
        assertThrows(VerificationCodeInvalid::class.java) { useCases.verify(id, code) }
        assertThrows(VerificationCodeInvalid::class.java) { useCases.verify(id, code) }
        assertEquals("BLOCKED", jdbc.queryForObject("SELECT status::text FROM participant WHERE id = ?", String::class.java, id))
    }

    @Test
    fun `failed verification mail does not install a new code`() {
        val email = "failed-mail-${System.nanoTime()}@example.test"
        val id = participant(email)
        doThrow(IllegalStateException("smtp unavailable")).`when`(mail)
            .sendVerification(eq(email) ?: email, anyString() ?: "")
        assertThrows(IllegalStateException::class.java) { useCases.sendRegistration(email) }
        assertThrows(VerificationCodeInvalid::class.java) { useCases.verify(id, "12345") }
        assertEquals("WAITING_VERIFY", jdbc.queryForObject("SELECT status::text FROM participant WHERE id = ?", String::class.java, id))
    }

    private fun participant(email: String): Long = jdbc.queryForObject(
        """INSERT INTO participant(login, mail, password, role, status, deadline_sending, deadline_payment)
           VALUES (?, ?, 'old-password-hash', 'USER', 'WAITING_VERIFY', 1, 1) RETURNING id""",
        Long::class.java, "verify-${System.nanoTime()}", email,
    )!!

    companion object {
        private val postgres = EmbeddedPostgres.start()

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val url = "jdbc:postgresql://localhost:${postgres.port}/postgres"
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.url") { url }
            registry.add("spring.flyway.user") { "postgres" }
            registry.add("spring.flyway.password") { "" }
        }
    }
}
