package com.model_store.modern.seller.agent.profile

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.model.CustomUserDetails
import com.model_store.model.constant.ParticipantStatus
import com.model_store.service.impl.JwtServiceImpl
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
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
import java.time.Duration
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.main.web-application-type=reactive",
        "app.private-key-path=keys/test_private_key.pem", "app.public-key-path=keys/test_public_key.pem"],
)
@ActiveProfiles("legacy")
class AgentProfileLegacyHttpTest {
    @field:MockitoBean lateinit var s3: AmazonS3
    @field:MockitoBean lateinit var mail: JavaMailSender
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jwt: JwtServiceImpl
    @Value("\${local.server.port}") private var port: Int = 0
    private val client = HttpClient.newHttpClient()

    @Test
    fun `legacy profile and empty details establish response contract`() {
        val admin = person("ADMIN", false, "ACTIVE")
        val user = person("USER", false, "ACTIVE")
        val agent = person("USER", true, "BLOCKED")
        val adminToken = jwt.generateAccessToken(admin, Duration.ofMinutes(30))
        val userToken = jwt.generateAccessToken(user, Duration.ofMinutes(30))
        val agentToken = jwt.generateAgentToken(agent, Duration.ofMinutes(30))
        val root = "/admin/actions/agents/${agent.id}"
        val initial = call("GET", "$root/profile", adminToken)
        assertEquals(200, initial.statusCode(), initial.body())
        val initialJson = json.readTree(initial.body())
        assertEquals(setOf("id", "login", "fullName", "phoneNumber", "imageId", "deadlineSending", "deadlinePayment", "status"),
            initialJson.fieldNames().asSequence().toSet())
        assertEquals("BLOCKED", initialJson["status"].asText())
        val patched = call("PUT", "$root/profile", adminToken,
            """{"fullName":"Legacy Agent","deadlineSending":8}""")
        assertEquals(200, patched.statusCode(), patched.body())
        assertEquals("Legacy Agent", json.readTree(patched.body())["fullName"].asText())
        assertEquals("BLOCKED", json.readTree(patched.body())["status"].asText())
        assertEquals(200, call("GET", "$root/addresses", adminToken).statusCode())
        mapOf("accounts" to "ACCOUNT_NOT_FOUND", "transfers" to "TRANSFER_NOT_FOUND",
            "social-networks" to "SOCIAL_NETWORK_NOT_FOUND").forEach { (suffix, code) ->
            val empty = call("GET", "$root/$suffix", adminToken)
            assertEquals(404, empty.statusCode(), "$suffix: ${empty.body()}")
            assertEquals(code, json.readTree(empty.body())["code"].asText(), suffix)
        }
        assertEquals(403, call("GET", "$root/profile", userToken).statusCode())
        assertEquals(403, call("GET", "$root/profile", agentToken).statusCode())
        assertEquals(401, call("GET", "$root/profile", null).statusCode())
        val missing = call("GET", "/admin/actions/agents/${user.id}/profile", adminToken)
        assertEquals(404, missing.statusCode(), missing.body())
        assertEquals("PARTICIPANT_NOT_FOUND", json.readTree(missing.body())["code"].asText())
    }

    private fun person(role: String, isAgent: Boolean, status: String): CustomUserDetails {
        val login = "agentprofilelegacy${UUID.randomUUID().toString().replace("-", "")}"
        val email = "$login@example.test"
        val id = jdbc.queryForObject(
            """INSERT INTO participant(login, mail, full_name, password, role, status, is_agent, deadline_sending, deadline_payment)
                VALUES (?, ?, 'Agent Profile', 'unused', ?::participant_role, ?::participant_status, ?, 1, 1) RETURNING id""",
            Long::class.java, login, email, role, status, isAgent,
        )!!
        return CustomUserDetails.builder().id(id).login(login).email(email).fullName("Agent Profile")
            .role(role).password("unused").status(ParticipantStatus.valueOf(status)).build()
    }

    private fun call(method: String, path: String, token: String?, body: String = ""): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (body.isNotEmpty()) builder.header("Content-Type", "application/json")
        return client.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString())
    }

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
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://localhost:${postgres.port}/postgres" }
            registry.add("spring.r2dbc.username") { "postgres" }
            registry.add("spring.r2dbc.password") { "" }
        }
    }
}
