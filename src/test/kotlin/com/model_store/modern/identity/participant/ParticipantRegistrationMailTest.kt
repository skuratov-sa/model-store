package com.model_store.modern.identity.participant

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.modern.identity.verification.application.VerificationMail
import com.model_store.modern.identity.verification.application.VerificationUseCases
import com.model_store.modern.identity.verification.domain.VerificationCodeInvalid
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import jakarta.servlet.Filter
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

@SpringBootTest
@ActiveProfiles("modern")
class ParticipantRegistrationMailTest {
    @field:MockitoBean lateinit var s3: AmazonS3
    @field:MockitoBean lateinit var jwtDecoder: JwtDecoder
    @field:MockitoBean lateinit var mail: VerificationMail
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var verification: VerificationUseCases
    @Autowired lateinit var transactionManager: PlatformTransactionManager
    @Autowired lateinit var webContext: WebApplicationContext

    @BeforeEach
    fun clearMail() = reset(mail)

    @Test
    fun `HTTP registration sends to committed participant and returns Long JSON`() {
        val email = "Mixed-${UUID.randomUUID()}@Example.Test"
        var committedId: Long? = null
        doAnswer {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            val id = jdbc.queryForObject("SELECT id FROM participant WHERE mail = ?", Long::class.java, email)!!
            assertEquals("WAITING_VERIFY", jdbc.queryForObject("SELECT status::text FROM participant WHERE id = ?", String::class.java, id))
            committedId = id
            null
        }.`when`(mail).sendVerification(eq(email) ?: email, anyString() ?: "")

        val result = postRegistration(email)
        assertEquals(200, result.first)
        assertEquals(committedId.toString(), result.second)
        assertFalse(result.second.contains(email))
        assertFalse(result.second.contains("secret"))
        verify(mail, times(1)).sendVerification(eq(email) ?: email, matches("[0-9]{5}") ?: "")
        mvc().perform(get("/participant")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `outer JPA transaction is suspended until registration mail is sent`() {
        val email = "outer-${UUID.randomUUID()}@example.test"
        var mailedId: Long? = null
        doAnswer {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            mailedId = jdbc.queryForObject("SELECT id FROM participant WHERE mail = ?", Long::class.java, email)
            null
        }.`when`(mail).sendVerification(eq(email) ?: email, anyString() ?: "")
        TransactionTemplate(transactionManager).executeWithoutResult {
            val response = postRegistration(email)
            assertEquals(200, response.first)
            assertEquals(mailedId.toString(), response.second)
        }
        verify(mail, times(1)).sendVerification(eq(email) ?: email, anyString() ?: "")
    }

    @Test
    fun `repeat waiting registration and other statuses keep legacy error without mail`() {
        for (status in listOf("WAITING_VERIFY", "ACTIVE", "BLOCKED", "DELETED")) {
            val email = "repeat-${status}-${UUID.randomUUID()}@example.test"
            val first = postRegistration(email)
            assertEquals(200, first.first)
            val id = first.second.toLong()
            if (status != "WAITING_VERIFY")
                jdbc.update("UPDATE participant SET status = ?::participant_status WHERE id = ?", status, id)
            clearInvocations(mail)
            val repeat = postRegistration(email)
            assertEquals(404, repeat.first)
            assertTrue(repeat.second.contains("PARTICIPANT_ALREADY_EXISTS"))
            verify(mail, never()).sendVerification(anyString(), anyString())
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM participant WHERE mail = ?", Int::class.java, email))
        }
    }

    @Test
    fun `failed registration sends no mail and SMTP failure leaves participant committed`() {
        val badEmail = "x".repeat(300) + "@example.test"
        assertTrue(postRegistration(badEmail).first >= 400)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM participant WHERE mail = ?", Int::class.java, badEmail))
        verify(mail, never()).sendVerification(anyString(), anyString())

        val email = "smtp-${UUID.randomUUID()}@example.test"
        doThrow(IllegalStateException("smtp unavailable")).`when`(mail)
            .sendVerification(eq(email) ?: email, anyString() ?: "")
        val failure = postRegistration(email)
        assertEquals(500, failure.first)
        val body = json.readTree(failure.second)
        assertEquals("INTERNAL_ERROR", body["code"].asText())
        assertEquals("Внутренняя ошибка", body["message"].asText())
        assertEquals(500, body["status"].asInt())
        assertTrue(body["details"].isNull)
        assertEquals(setOf("code", "message", "status", "timestamp", "details"), body.fieldNames().asSequence().toSet())
        val id = jdbc.queryForObject("SELECT id FROM participant WHERE mail = ?", Long::class.java, email)
        assertNotNull(id)
        assertEquals("WAITING_VERIFY", jdbc.queryForObject("SELECT status::text FROM participant WHERE id = ?", String::class.java, id))
        verify(mail, times(1)).sendVerification(eq(email) ?: email, anyString() ?: "")
        val unsentCode = mockingDetails(mail).invocations.single { it.method.name == "sendVerification" }.arguments[1] as String
        assertThrows(VerificationCodeInvalid::class.java) { verification.verify(id!!, unsentCode) }
        assertFalse(failure.second.contains("secret"))
    }

    private fun postRegistration(email: String): Pair<Int, String> {
        val response = mvc()
            .perform(post("/participant").contentType(MediaType.APPLICATION_JSON)
                .content("""{"mail":"$email","password":"secret","age":21}"""))
            .andReturn().response
        return response.status to response.contentAsString
    }

    private fun mvc() = MockMvcBuilders.webAppContextSetup(webContext)
        .addFilters<DefaultMockMvcBuilder>(webContext.getBean("springSecurityFilterChain") as Filter)
        .build()

    companion object {
        private val postgres = EmbeddedPostgres.start()

        @JvmStatic @AfterAll
        fun close() = postgres.close()

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
