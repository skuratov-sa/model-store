package com.model_store.modern.identity.auth

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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
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
    properties = [
        "spring.main.web-application-type=reactive",
        "app.public-key-path=keys/test_public_key.pem",
        "app.private-key-path=keys/test_private_key.pem",
    ],
)
@ActiveProfiles("legacy")
class AuthLegacyHttpCompatibilityTest {
    @field:MockitoBean lateinit var s3: AmazonS3
    @field:MockitoBean lateinit var mail: JavaMailSender
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var legacyJwt: JwtServiceImpl
    @Value("\${local.server.port}") private var port: Int = 0
    private val client = HttpClient.newHttpClient()

    @Test
    fun `legacy HTTP login refresh and profile retain their response contract`() {
        val user = account("ACTIVE")
        val login = post("/auth/login", """{"mail":"${user.mail}","password":"password"}""")
        assertEquals(200, login.statusCode())
        val issued = json.readTree(login.body())
        assertEquals(setOf("access_token", "refresh_token"), issued.fieldNames().asSequence().toSet())
        val access = issued["access_token"].asText()
        val refresh = issued["refresh_token"].asText()
        val profile = getProfile(access)
        assertEquals(200, profile.statusCode())
        val claims = json.readTree(profile.body())
        assertEquals(legacyJwt.parseAccessToken(access).keys, claims.fieldNames().asSequence().toSet())
        assertEquals(user.id, claims["id"].asLong())
        assertEquals(user.login, claims["sub"].asText())
        assertEquals(user.mail, claims["email"].asText())
        assertEquals("USER", claims["role"].asText())
        assertEquals("access", claims["type"].asText())
        assertTrue(claims["exp"].asLong() > System.currentTimeMillis() / 1000)

        val renewed = post("/auth/refresh", null, "X-Refresh-Token" to refresh)
        assertEquals(200, renewed.statusCode())
        assertEquals("access", legacyJwt.parseAccessToken(renewed.body())["type"])
        assertEquals(401, getProfile(refresh).statusCode())
        assertEquals(401, getProfile("not-a-token").statusCode())
        val expired = legacyJwt.generateAccessToken(user.details, Duration.ofMinutes(-2))
        assertEquals(401, getProfile(expired).statusCode())
    }

    @Test
    fun `legacy login reports credential and participant statuses`() {
        val active = account("ACTIVE")
        val wrong = post("/auth/login", """{"mail":"${active.mail}","password":"wrong"}""")
        assertEquals(401, wrong.statusCode(), wrong.body())
        assertEquals("BAD_CREDENTIALS", json.readTree(wrong.body())["code"].asText())
        for ((status, expectedHttp, expectedCode) in listOf(
            Triple("WAITING_VERIFY", 401, "WAITING_VERIFY"),
            Triple("BLOCKED", 403, "ACCOUNT_LOCKED"),
            Triple("DELETED", 403, "ACCOUNT_LOCKED"),
        )) {
            val user = account(status)
            val response = post("/auth/login", """{"mail":"${user.mail}","password":"password"}""")
            assertEquals(expectedHttp, response.statusCode(), response.body())
            assertEquals(expectedCode, json.readTree(response.body())["code"].asText())
        }
    }

    private fun account(status: String): TestAccount {
        val marker = UUID.randomUUID().toString().replace("-", "")
        val login = "user$marker"
        val email = "$login@example.test"
        val hash = BCryptPasswordEncoder().encode("password")
        val id = jdbc.queryForObject(
            """INSERT INTO participant(login, mail, full_name, password, role, status, deadline_sending, deadline_payment)
               VALUES (?, ?, 'Full Name', ?, 'USER', ?::participant_status, 1, 1) RETURNING id""",
            Long::class.java, login, email, hash, status,
        )!!
        val details = CustomUserDetails.builder().id(id).login(login).email(email).fullName("Full Name")
            .role("USER").password(hash).status(ParticipantStatus.valueOf(status)).build()
        return TestAccount(id, login, email, details)
    }

    private fun getProfile(token: String) = request("GET", "/auth/profile", null, "Authorization" to "Bearer $token")

    private fun post(path: String, body: String?, header: Pair<String, String>? = null) =
        request("POST", path, body, header)

    private fun request(method: String, path: String, body: String?, header: Pair<String, String>?) : HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        if (header != null) builder.header(header.first, header.second)
        if (body != null) builder.header("Content-Type", "application/json")
        val request = if (method == "GET") builder.GET().build()
            else builder.POST(HttpRequest.BodyPublishers.ofString(body ?: "")).build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private data class TestAccount(val id: Long, val login: String, val mail: String, val details: CustomUserDetails)

    companion object {
        private val postgres = EmbeddedPostgres.start()

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val port = postgres.port
            val jdbcUrl = "jdbc:postgresql://localhost:$port/postgres"
            registry.add("spring.datasource.url") { jdbcUrl }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.url") { jdbcUrl }
            registry.add("spring.flyway.user") { "postgres" }
            registry.add("spring.flyway.password") { "" }
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://localhost:$port/postgres" }
            registry.add("spring.r2dbc.username") { "postgres" }
            registry.add("spring.r2dbc.password") { "" }
        }
    }
}
