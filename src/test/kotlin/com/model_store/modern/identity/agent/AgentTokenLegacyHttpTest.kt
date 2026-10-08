package com.model_store.modern.identity.agent

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
import java.time.Instant
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.main.web-application-type=reactive",
        "app.private-key-path=keys/test_private_key.pem",
        "app.public-key-path=keys/test_public_key.pem",
    ],
)
@ActiveProfiles("legacy")
class AgentTokenLegacyHttpTest {
    @field:MockitoBean lateinit var s3: AmazonS3
    @field:MockitoBean lateinit var mail: JavaMailSender
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jwt: JwtServiceImpl
    @Value("\${local.server.port}") private var port: Int = 0
    private val client = HttpClient.newHttpClient()

    @Test
    fun `legacy HTTP response and errors define agent token contract`() {
        val admin = person("ADMIN", false, "ACTIVE")
        val agent = person("USER", true, "ACTIVE")
        val user = person("USER", false, "ACTIVE")
        val adminAccess = jwt.generateAccessToken(admin, Duration.ofMinutes(30))
        val userAccess = jwt.generateAccessToken(user, Duration.ofMinutes(30))
        val issued = issue(agent.id, adminAccess)
        assertEquals(200, issued.statusCode(), issued.body())
        val body = json.readTree(issued.body())
        assertEquals(setOf("accessToken", "accessTokenExpiresAt", "refreshToken", "refreshTokenExpiresAt"), body.fieldNames().asSequence().toSet())
        val access = jwt.parseAccessToken(body["accessToken"].asText())
        val refresh = jwt.parseAccessToken(body["refreshToken"].asText())
        assertEquals(setOf("sub", "iat", "exp", "type", "issuedBy", "id", "login", "email", "fullName", "imageId", "role"), access.keys)
        assertEquals(setOf("sub", "exp", "type", "issuedBy"), refresh.keys)
        assertEquals(agent.id, (access["id"] as Number).toLong())
        assertEquals(agent.login, access.subject)
        assertEquals("agent_access", access["type"])
        assertEquals("admin", access["issuedBy"])
        assertEquals("USER", access["role"])
        assertEquals(0L, (access["imageId"] as Number).toLong())
        assertEquals(agent.email, refresh.subject)
        assertEquals("refresh", refresh["type"])
        assertEquals("admin", refresh["issuedBy"])
        assertTrue(kotlin.math.abs(Duration.ofMinutes(30).seconds - Duration.between(Instant.now(),
            Instant.parse(body["accessTokenExpiresAt"].asText())).seconds) <= 3)
        assertError(issue(user.id, adminAccess), 404, "PARTICIPANT_NOT_FOUND", "Бот не найден")
        jdbc.update("UPDATE participant SET status = 'BLOCKED'::participant_status WHERE id = ?", agent.id)
        assertError(issue(agent.id, adminAccess), 403, "ACCESS_DENIED", "Бот не активен")
        assertError(issue(agent.id, adminAccess, "accessTokenTtlMinutes=14"), 400,
            "INVALID_REQUEST", "accessTokenTtlMinutes должен быть в диапазоне 15-1440 минут")
        assertError(issue(agent.id, adminAccess, "refreshTokenTtlDays=366"), 400,
            "INVALID_REQUEST", "refreshTokenTtlDays должен быть в диапазоне 30-365 дней")
        assertEquals(403, issue(agent.id, userAccess).statusCode())
        assertEquals(401, issue(agent.id, null).statusCode())
    }

    private fun person(role: String, isAgent: Boolean, status: String): CustomUserDetails {
        val login = "agentlegacy${UUID.randomUUID().toString().replace("-", "")}"
        val email = "$login@example.test"
        val id = jdbc.queryForObject(
            """INSERT INTO participant(login, mail, full_name, password, role, status, is_agent, deadline_sending, deadline_payment)
                VALUES (?, ?, 'Agent Test', 'unused', ?::participant_role, ?::participant_status, ?, 1, 1) RETURNING id""",
            Long::class.java, login, email, role, status, isAgent,
        )!!
        return CustomUserDetails.builder().id(id).login(login).email(email).fullName("Agent Test")
            .role(role).password("unused").status(ParticipantStatus.valueOf(status)).build()
    }

    private fun issue(id: Long, token: String?, query: String = ""): HttpResponse<String> {
        val path = "/admin/actions/agents/$id/token${if (query.isBlank()) "" else "?$query"}"
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        if (token != null) builder.header("Authorization", "Bearer $token")
        return client.send(builder.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun assertError(response: HttpResponse<String>, status: Int, code: String, message: String) {
        assertEquals(status, response.statusCode(), response.body())
        val error = json.readTree(response.body())
        assertEquals(setOf("code", "message", "status", "timestamp", "details"), error.fieldNames().asSequence().toSet())
        assertEquals(code, error["code"].asText())
        assertEquals(message, error["message"].asText())
        assertEquals(status, error["status"].asInt())
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
