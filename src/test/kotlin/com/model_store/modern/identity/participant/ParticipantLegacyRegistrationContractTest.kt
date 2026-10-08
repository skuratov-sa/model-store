package com.model_store.modern.identity.participant

import com.amazonaws.services.s3.AmazonS3
import com.model_store.model.CreateParticipantRequest
import com.model_store.service.impl.EmailServiceImpl
import com.model_store.service.impl.ParticipantServiceImpl
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.util.AopTestUtils
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.Properties
import java.util.UUID

@SpringBootTest(properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem"])
@ActiveProfiles("legacy")
class ParticipantLegacyRegistrationContractTest {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var participants: ParticipantServiceImpl
    @Autowired lateinit var emailService: EmailServiceImpl
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    @Test
    fun `legacy registration service mails original address and rejects every existing status`() {
        `when`(mail.createMimeMessage()).thenAnswer { MimeMessage(Session.getInstance(Properties())) }
        val service = AopTestUtils.getTargetObject<ParticipantServiceImpl>(participants)
        for (status in listOf("WAITING_VERIFY", "ACTIVE", "BLOCKED", "DELETED")) {
            val email = "Legacy-${status}-${UUID.randomUUID()}@Example.Test"
            val id = service.createParticipant(request(email)).block() as Long
            assertEquals(id, emailService.sendVerificationWithoutLimitCode(email).block())
            val sent = mockingDetails(mail).invocations.last { it.method.name == "send" }.arguments[0] as MimeMessage
            assertEquals(email, sent.allRecipients.single().toString())
            assertEquals("WAITING_VERIFY", jdbc.queryForObject("SELECT status::text FROM participant WHERE id = ?", String::class.java, id))
            if (status != "WAITING_VERIFY")
                jdbc.update("UPDATE participant SET status = ?::participant_status WHERE id = ?", status, id)
            val sendsBefore = mockingDetails(mail).invocations.count { it.method.name == "send" }
            val failure = assertThrows(Exception::class.java) { service.createParticipant(request(email)).block() }
            assertTrue(failure.message.orEmpty().contains("Пользователь с таким email уже зарегистрирован"))
            assertEquals(sendsBefore, mockingDetails(mail).invocations.count { it.method.name == "send" })
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM participant WHERE mail = ?", Int::class.java, email))
        }
    }

    private fun request(email: String) = CreateParticipantRequest().apply {
        mail = email
        password = "secret"
        age = 21
    }

    companion object {
        private val postgres = EmbeddedPostgres.start()

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val port = postgres.port
            registry.add("spring.datasource.url") { "jdbc:postgresql://localhost:$port/postgres" }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.url") { "jdbc:postgresql://localhost:$port/postgres" }
            registry.add("spring.flyway.user") { "postgres" }
            registry.add("spring.flyway.password") { "" }
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://localhost:$port/postgres" }
            registry.add("spring.r2dbc.username") { "postgres" }
            registry.add("spring.r2dbc.password") { "" }
        }
    }
}
