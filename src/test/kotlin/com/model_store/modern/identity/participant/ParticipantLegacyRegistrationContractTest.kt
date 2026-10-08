package com.model_store.modern.identity.participant

import com.amazonaws.services.s3.AmazonS3
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Properties
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem"],
)
@ActiveProfiles("legacy")
class ParticipantLegacyRegistrationContractTest {
    @Value("\${local.server.port}") var port: Int = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    @Test
    fun `legacy HTTP registration mails original address and rejects every existing status`() {
        `when`(mail.createMimeMessage()).thenAnswer { MimeMessage(Session.getInstance(Properties())) }
        for (status in listOf("WAITING_VERIFY", "ACTIVE", "BLOCKED", "DELETED")) {
            val email = "Legacy-${status}-${UUID.randomUUID()}@Example.Test"
            val first = register(email)
            assertEquals(200, first.statusCode(), first.body())
            val id = first.body().toLong()
            val sent = mockingDetails(mail).invocations.last { it.method.name == "send" }.arguments[0] as MimeMessage
            assertEquals(email, sent.allRecipients.single().toString())
            assertEquals("WAITING_VERIFY", jdbc.queryForObject("SELECT status::text FROM participant WHERE id = ?", String::class.java, id))
            if (status != "WAITING_VERIFY")
                jdbc.update("UPDATE participant SET status = ?::participant_status WHERE id = ?", status, id)
            val sendsBefore = mockingDetails(mail).invocations.count { it.method.name == "send" }
            val repeat = register(email)
            assertEquals(404, repeat.statusCode(), repeat.body())
            assertTrue(repeat.body().contains("PARTICIPANT_ALREADY_EXISTS"))
            assertEquals(sendsBefore, mockingDetails(mail).invocations.count { it.method.name == "send" })
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM participant WHERE mail = ?", Int::class.java, email))
        }
    }

    private fun register(email: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/participant"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"mail":"$email","password":"secret","age":21}"""))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private val postgres = EmbeddedPostgres.start()
        private val client = HttpClient.newHttpClient()

        @JvmStatic @AfterAll
        fun close() {
            client.close()
            postgres.close()
        }

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
