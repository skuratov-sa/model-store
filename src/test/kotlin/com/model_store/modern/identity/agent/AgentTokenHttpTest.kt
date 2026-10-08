package com.model_store.modern.identity.agent

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.modern.identity.auth.application.AuthUseCases
import com.model_store.modern.identity.agent.application.VerifyAgentAccess
import com.model_store.modern.identity.agent.domain.AgentTokenFailure
import com.model_store.modern.identity.verification.application.VerificationMail
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType
import com.model_store.configuration.property.ApplicationProperties
import com.model_store.model.CustomUserDetails
import com.model_store.model.constant.ParticipantStatus
import com.model_store.repository.ParticipantRepository
import com.model_store.service.impl.JwtServiceImpl
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.core.userdetails.ReactiveUserDetailsService
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.mockito.Mockito.mock
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.PublicKey
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("modern")
class AgentTokenHttpTest {
    @field:MockitoBean lateinit var s3: AmazonS3
    @field:MockitoBean lateinit var mail: VerificationMail
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var decoder: JwtDecoder
    @Autowired lateinit var auth: AuthUseCases
    @Autowired lateinit var verifyAgent: VerifyAgentAccess
    @Value("\${local.server.port}") private var port: Int = 0
    private val client = HttpClient.newHttpClient()

    @Test
    fun `admin issues exact legacy token shape and refresh works over HTTP`() {
        val admin = participant("ADMIN", "ACTIVE", false)
        val agent = participant("USER", "ACTIVE", true)
        val adminAccess = auth.login(admin.mail, "password").getValue("access_token")
        val response = issue(agent.id, adminAccess)
        assertEquals(200, response.statusCode(), response.body())
        val body = json.readTree(response.body())
        assertEquals(setOf("accessToken", "accessTokenExpiresAt", "refreshToken", "refreshTokenExpiresAt"),
            body.fieldNames().asSequence().toSet())
        val accessRaw = body["accessToken"].asText()
        val refreshRaw = body["refreshToken"].asText()
        val access = decoder.decode(accessRaw)
        val rawClaims = Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(accessRaw).payload
        assertEquals(setOf("sub", "iat", "exp", "type", "issuedBy", "id", "login", "email", "fullName", "imageId", "role"), rawClaims.keys)
        assertEquals(setOf("sub", "iat", "exp", "type", "issuedBy", "id", "login", "email", "fullName", "imageId", "role"), access.claims.keys)
        assertEquals("RS256", access.headers["alg"])
        assertEquals(agent.id, (access.claims["id"] as Number).toLong())
        assertEquals(agent.login, access.subject)
        assertEquals(agent.mail, access.claims["email"])
        assertEquals("USER", access.claims["role"])
        assertEquals("agent_access", access.claims["type"])
        assertEquals("admin", access.claims["issuedBy"])
        assertEquals(0L, (access.claims["imageId"] as Number).toLong())
        val agentActor = Actor(agent.id, agent.login, "USER", TokenType.AGENT_ACCESS)
        assertEquals(agent.id, verifyAgent.requireActiveAgent(agentActor))
        assertThrows(AgentTokenFailure.InvalidAgentAccess::class.java) {
            verifyAgent.requireActiveAgent(Actor(agent.id, agent.login, "USER", TokenType.ACCESS))
        }
        val accessExpiry = Instant.parse(body["accessTokenExpiresAt"].asText())
        val refreshExpiry = Instant.parse(body["refreshTokenExpiresAt"].asText())
        assertTrue(kotlin.math.abs(accessExpiry.epochSecond - access.expiresAt!!.epochSecond) <= 1)
        assertTrue(kotlin.math.abs(Duration.ofMinutes(30).seconds - Duration.between(Instant.now(), accessExpiry).seconds) <= 3)
        val refresh = Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(refreshRaw).payload
        assertEquals(setOf("sub", "exp", "type", "issuedBy"), refresh.keys)
        assertEquals(agent.mail, refresh.subject)
        assertEquals("refresh", refresh["type"])
        assertEquals("admin", refresh["issuedBy"])
        assertTrue(kotlin.math.abs(refreshExpiry.epochSecond - refresh.expiration.time / 1000) <= 1)
        assertTrue(kotlin.math.abs(Duration.ofDays(90).seconds - Duration.between(Instant.now(), refreshExpiry).seconds) <= 3)
        assertEquals(200, get("/auth/profile", accessRaw).statusCode())
        assertEquals(401, get("/auth/profile", refreshRaw).statusCode())
        val renewed = post("/auth/refresh", "", null, "X-Refresh-Token" to refreshRaw)
        assertEquals(200, renewed.statusCode(), renewed.body())
        val renewedAccess = decoder.decode(renewed.body())
        assertEquals("agent_access", renewedAccess.claims["type"])
        assertEquals("admin", renewedAccess.claims["issuedBy"])
        assertTrue(kotlin.math.abs(Duration.ofHours(24).seconds - Duration.between(Instant.now(), renewedAccess.expiresAt).seconds) <= 3)
    }

    @Test
    fun `only verified admin may issue and ttl bounds retain legacy errors`() {
        val admin = participant("ADMIN", "ACTIVE", false)
        val user = participant("USER", "ACTIVE", false)
        val agent = participant("USER", "ACTIVE", true)
        val adminAccess = auth.login(admin.mail, "password").getValue("access_token")
        val ordinaryTokens = auth.login(user.mail, "password")
        val userAccess = ordinaryTokens.getValue("access_token")
        val ordinaryRenewed = post("/auth/refresh", "", null,
            "X-Refresh-Token" to ordinaryTokens.getValue("refresh_token"))
        assertEquals(200, ordinaryRenewed.statusCode())
        assertEquals("access", decoder.decode(ordinaryRenewed.body()).claims["type"])
        assertEquals(403, issue(agent.id, ordinaryRenewed.body()).statusCode())
        assertEquals(401, issue(agent.id, null).statusCode())
        assertEquals(403, issue(agent.id, userAccess).statusCode())
        assertEquals(401, issue(agent.id, tamper(adminAccess)).statusCode())
        val expired = signedAccess(admin.id, "ADMIN", Instant.now().minusSeconds(60))
        assertEquals(401, issue(agent.id, expired).statusCode())
        val wrongTypes = listOf("refresh", "verify", "unknown")
        wrongTypes.forEach { type ->
            val raw = Jwts.builder().subject(admin.login).claim("id", admin.id).claim("role", "ADMIN")
                .claim("type", type).expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(privateKey).compact()
            assertEquals(401, issue(agent.id, raw).statusCode(), type)
        }
        val fakeAgentAccess = Jwts.builder().subject(admin.login).claim("id", admin.id).claim("role", "ADMIN")
            .claim("type", "agent_access").expiration(Date.from(Instant.now().plusSeconds(3600)))
            .signWith(privateKey).compact()
        assertEquals(401, issue(agent.id, fakeAgentAccess).statusCode())
        val signedAgentAdmin = Jwts.builder().subject(admin.login).claim("id", admin.id).claim("role", "ADMIN")
            .claim("type", "agent_access").claim("issuedBy", "admin")
            .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(privateKey).compact()
        assertError(issue(agent.id, signedAgentAdmin), 403, "ACCESS_DENIED", "Доступ запрещён")
        val missingId = Jwts.builder().subject(admin.login).claim("role", "ADMIN").claim("type", "access")
            .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(privateKey).compact()
        assertEquals(401, issue(agent.id, missingId).statusCode())
        val blankRole = Jwts.builder().subject(admin.login).claim("id", admin.id).claim("role", "")
            .claim("type", "access").expiration(Date.from(Instant.now().plusSeconds(3600)))
            .signWith(privateKey).compact()
        assertEquals(401, issue(agent.id, blankRole).statusCode())
        val forgedAdminRole = signedAccess(user.id, "ADMIN", Instant.now().plusSeconds(3600))
        assertError(issue(agent.id, forgedAdminRole), 403, "ACCESS_DENIED", "Доступ запрещён")
        val notAgent = issue(user.id, adminAccess)
        assertError(notAgent, 404, "PARTICIPANT_NOT_FOUND", "Бот не найден")
        assertError(issue(Long.MAX_VALUE, adminAccess), 404, "PARTICIPANT_NOT_FOUND", "Бот не найден")
        listOf("WAITING_VERIFY", "BLOCKED", "DELETED").forEach { status ->
            val inactive = participant("USER", status, true)
            assertError(issue(inactive.id, adminAccess), 403, "ACCESS_DENIED", "Бот не активен")
        }
        for ((query, message) in listOf(
            "accessTokenTtlMinutes=14" to "accessTokenTtlMinutes должен быть в диапазоне 15-1440 минут",
            "accessTokenTtlMinutes=1441" to "accessTokenTtlMinutes должен быть в диапазоне 15-1440 минут",
            "refreshTokenTtlDays=29" to "refreshTokenTtlDays должен быть в диапазоне 30-365 дней",
            "refreshTokenTtlDays=366" to "refreshTokenTtlDays должен быть в диапазоне 30-365 дней",
        )) assertError(issue(agent.id, adminAccess, query), 400, "INVALID_REQUEST", message)
        assertEquals(200, issue(agent.id, adminAccess, "accessTokenTtlMinutes=15&refreshTokenTtlDays=30").statusCode())
        assertEquals(200, issue(agent.id, adminAccess, "accessTokenTtlMinutes=1440&refreshTokenTtlDays=365").statusCode())
        assertEquals(405, get("/admin/actions/agents/${agent.id}/token", adminAccess).statusCode())
    }

    @Test
    fun `admin must remain active and admin in database when issuing`() {
        val admin = participant("ADMIN", "ACTIVE", false)
        val agent = participant("USER", "ACTIVE", true)
        val token = auth.login(admin.mail, "password").getValue("access_token")
        assertEquals(200, issue(agent.id, token).statusCode())
        jdbc.update("UPDATE participant SET role = 'USER'::participant_role WHERE id = ?", admin.id)
        assertError(issue(agent.id, token), 403, "ACCESS_DENIED", "Доступ запрещён")
        jdbc.update("UPDATE participant SET role = 'ADMIN'::participant_role, status = 'BLOCKED'::participant_status WHERE id = ?", admin.id)
        assertError(issue(agent.id, token), 403, "ACCESS_DENIED", "Доступ запрещён")
        jdbc.update("UPDATE participant SET status = 'DELETED'::participant_status WHERE id = ?", admin.id)
        assertError(issue(agent.id, token), 403, "ACCESS_DENIED", "Доступ запрещён")
        jdbc.update("UPDATE participant SET status = 'ACTIVE'::participant_status, is_agent = true WHERE id = ?", admin.id)
        assertError(issue(agent.id, token), 403, "ACCESS_DENIED", "Доступ запрещён")
    }

    @Test
    fun `agent refresh rejects removed agent and inactive status`() {
        val admin = participant("ADMIN", "ACTIVE", false)
        val agent = participant("USER", "ACTIVE", true)
        val access = auth.login(admin.mail, "password").getValue("access_token")
        val issued = json.readTree(issue(agent.id, access).body())
        val refresh = issued["refreshToken"].asText()
        jdbc.update("UPDATE participant SET is_agent = false WHERE id = ?", agent.id)
        assertError(post("/auth/refresh", "", null, "X-Refresh-Token" to refresh), 401,
            "TOKEN_INVALID_OR_EXPIRED", "Token недействителен или срок его действия истек")
        assertThrows(AgentTokenFailure.InvalidAgentAccess::class.java) {
            verifyAgent.requireActiveAgent(Actor(agent.id, agent.login, "USER", TokenType.AGENT_ACCESS))
        }
        val regular = participant("USER", "ACTIVE", false)
        assertThrows(AgentTokenFailure.InvalidAgentAccess::class.java) {
            verifyAgent.requireActiveAgent(Actor(regular.id, regular.login, "USER", TokenType.AGENT_ACCESS))
        }
        assertThrows(AgentTokenFailure.InvalidAgentAccess::class.java) {
            verifyAgent.requireActiveAgent(Actor(Long.MAX_VALUE, "missing", "USER", TokenType.AGENT_ACCESS))
        }
        jdbc.update("UPDATE participant SET is_agent = true, status = 'BLOCKED'::participant_status WHERE id = ?", agent.id)
        assertEquals(401, post("/auth/refresh", "", null, "X-Refresh-Token" to refresh).statusCode())
        assertThrows(AgentTokenFailure.InvalidAgentAccess::class.java) {
            verifyAgent.requireActiveAgent(Actor(agent.id, agent.login, "USER", TokenType.AGENT_ACCESS))
        }
    }

    @Test
    fun `legacy Java agent tokens are accepted by modern HTTP and refresh`() {
        val agent = participant("USER", "ACTIVE", true)
        val properties = ApplicationProperties().apply {
            privateKeyPath = "src/test/resources/keys/test_private_key.pem"
            publicKeyPath = "src/test/resources/keys/test_public_key.pem"
        }
        val legacy = JwtServiceImpl(
            mock(ReactiveUserDetailsService::class.java), properties, mock(ParticipantRepository::class.java),
        )
        val details = CustomUserDetails.builder().id(agent.id).login(agent.login).email(agent.mail)
            .fullName("Agent Test").imageId(0L).role("USER").status(ParticipantStatus.ACTIVE).build()
        val legacyAccess = legacy.generateAgentToken(details, Duration.ofMinutes(30))
        val legacyRefresh = legacy.generateAgentRefreshToken(details, Duration.ofDays(90))
        assertEquals("agent_access", decoder.decode(legacyAccess).claims["type"])
        assertEquals(200, get("/auth/profile", legacyAccess).statusCode())
        val renewed = post("/auth/refresh", "", null, "X-Refresh-Token" to legacyRefresh)
        assertEquals(200, renewed.statusCode(), renewed.body())
        assertEquals("agent_access", decoder.decode(renewed.body()).claims["type"])
        assertEquals(401, get("/auth/profile", legacyRefresh).statusCode())
    }

    private fun participant(role: String, status: String, agent: Boolean): Person {
        val login = "agenttest${UUID.randomUUID().toString().replace("-", "")}"
        val mail = "$login@example.test"
        val id = jdbc.queryForObject(
            """INSERT INTO participant(login, mail, full_name, password, role, status, is_agent, deadline_sending, deadline_payment)
                VALUES (?, ?, 'Agent Test', ?, ?::participant_role, ?::participant_status, ?, 1, 1) RETURNING id""",
            Long::class.java, login, mail, org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("password"), role, status, agent,
        )!!
        return Person(id, login, mail)
    }

    private fun issue(id: Long, token: String?, query: String = ""): HttpResponse<String> =
        post("/admin/actions/agents/$id/token${if (query.isEmpty()) "" else "?$query"}", "", token)

    private fun post(path: String, body: String, token: String?, extra: Pair<String, String>? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (extra != null) builder.header(extra.first, extra.second)
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun get(path: String, token: String?) : HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        if (token != null) builder.header("Authorization", "Bearer $token")
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun assertError(response: HttpResponse<String>, status: Int, code: String, message: String) {
        assertEquals(status, response.statusCode(), response.body())
        val error = json.readTree(response.body())
        assertEquals(setOf("code", "message", "status", "timestamp", "details"), error.fieldNames().asSequence().toSet())
        assertEquals(code, error["code"].asText())
        assertEquals(message, error["message"].asText())
        assertEquals(status, error["status"].asInt())
        assertTrue(error["details"].isNull)
    }

    private fun signedAccess(id: Long, role: String, expiry: Instant) = Jwts.builder()
        .subject("admin").claim("id", id).claim("role", role).claim("type", "access")
        .expiration(Date.from(expiry)).signWith(privateKey).compact()

    private fun tamper(token: String): String {
        val parts = token.split('.')
        val first = if (parts[2][0] == 'A') 'B' else 'A'
        return "${parts[0]}.${parts[1]}.$first${parts[2].substring(1)}"
    }

    private data class Person(val id: Long, val login: String, val mail: String)

    companion object {
        private val postgres = EmbeddedPostgres.start()
        private val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(readKey("src/test/resources/keys/test_private_key.pem", "PRIVATE")))
        private val publicKey: PublicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(readKey("src/test/resources/keys/test_public_key.pem", "PUBLIC")))

        private fun readKey(path: String, kind: String): ByteArray = Base64.getDecoder().decode(
            Files.readString(Path.of(path)).replace("-----BEGIN $kind KEY-----", "")
                .replace("-----END $kind KEY-----", "").replace(Regex("\\s+"), ""),
        )

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val url = "jdbc:postgresql://localhost:${postgres.port}/postgres"
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.url") { url }
            registry.add("spring.flyway.user") { "postgres" }
            registry.add("spring.flyway.password") { "" }
            registry.add("app.private-key-path") { "keys/test_private_key.pem" }
            registry.add("app.public-key-path") { "keys/test_public_key.pem" }
        }
    }
}
