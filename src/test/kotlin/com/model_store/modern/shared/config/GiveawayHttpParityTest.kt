package com.model_store.modern.shared.config

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
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
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.sql.Timestamp
import java.util.Base64
import java.util.Date

/** The same real HTTP and migrated PostgreSQL contract is exercised in both application profiles. */
private class GiveawayHttpContract(private val port: Int, private val jdbc: JdbcTemplate, private val json: ObjectMapper) {
    private val client = HttpClient.newHttpClient()
    private val fields = setOf("productId", "name", "description", "imageIds", "telegramUrl",
        "startAt", "endAt", "winnersCount", "rules", "homeText", "status")

    fun exercise(rejectVerify: Boolean) {
        val owner = jdbc.queryForObject("""INSERT INTO participant(login,mail,password,status,role,deadline_sending,deadline_payment)
            VALUES ('giveawayParity','giveawayParity@example.test','hash','ACTIVE','USER',3,7) RETURNING id""", Long::class.java)!!
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        fun product(name: String, availability: String, status: String, enabled: Boolean,
                    start: Instant, end: Instant, description: String? = "Prize"): Long =
            jdbc.queryForObject("""INSERT INTO product(name,description,price,currency,participant_id,status,
                availability,count,giveaway_enabled,giveaway_telegram_url,giveaway_start_at,giveaway_end_at,
                giveaway_winners_count,giveaway_rules,giveaway_home_text)
                VALUES (?,?,0,'RUB',?,?,?,1,?,'https://t.me/prize',?,?,2,'Rules','Home') RETURNING id""",
                Long::class.java, name, description, owner, status, availability, enabled,
                Timestamp.from(start), Timestamp.from(end))!!
        val runningStart = now.minus(1, ChronoUnit.DAYS)
        val runningEnd = now.plus(1, ChronoUnit.DAYS)
        val running = product("Running", "GIVEAWAY", "AWAITING_GIVEAWAY", true, runningStart, runningEnd)
        val scheduledStart = now.plus(2, ChronoUnit.DAYS)
        val scheduledEnd = now.plus(3, ChronoUnit.DAYS)
        val scheduled = product("Scheduled", "GIVEAWAY", "ACTIVE", true, scheduledStart, scheduledEnd, null)
        val ended = product("Ended", "GIVEAWAY", "ACTIVE", true,
            now.minus(3, ChronoUnit.DAYS), now.minus(2, ChronoUnit.DAYS))
        val disabled = product("Disabled", "GIVEAWAY", "ACTIVE", false, runningStart, runningEnd)
        val ordinary = product("Ordinary", "PURCHASABLE", "ACTIVE", false, runningStart, runningEnd)
        val older = jdbc.queryForObject("""INSERT INTO image(tag,status,entity_id,created_at)
            VALUES ('PRODUCT','ACTIVE',?,?) RETURNING id""", Long::class.java, running,
            Timestamp.from(now.minus(4, ChronoUnit.HOURS)))!!
        val newer = jdbc.queryForObject("""INSERT INTO image(tag,status,entity_id,created_at)
            VALUES ('PRODUCT','ACTIVE',?,?) RETURNING id""", Long::class.java, running,
            Timestamp.from(now.minus(2, ChronoUnit.HOURS)))!!
        jdbc.update("""INSERT INTO image(tag,status,entity_id,created_at) VALUES
            ('PRODUCT','TEMPORARY',?,?),('PARTICIPANT','ACTIVE',?,?)""",
            running, Timestamp.from(now), running, Timestamp.from(now))

        fun assertBody(response: HttpResponse<String>, id: Long, name: String, description: String?,
                       images: List<Long>, start: Instant, end: Instant, status: String) {
            assertEquals(200, response.statusCode(), response.body())
            assertEquals("noindex, nofollow", response.headers().firstValue("X-Robots-Tag").orElse(null))
            val body = json.readTree(response.body())
            assertEquals(fields, body.fieldNames().asSequence().toSet())
            assertEquals(id, body["productId"].asLong())
            assertEquals(name, body["name"].asText())
            assertEquals(description, body["description"].takeUnless { it.isNull }?.asText())
            assertEquals(images, body["imageIds"].map { it.asLong() })
            assertEquals("https://t.me/prize", body["telegramUrl"].asText())
            assertEquals(start, Instant.parse(body["startAt"].asText()))
            assertEquals(end, Instant.parse(body["endAt"].asText()))
            assertEquals(2, body["winnersCount"].asInt())
            assertEquals("Rules", body["rules"].asText())
            assertEquals("Home", body["homeText"].asText())
            assertEquals(status, body["status"].asText())
        }

        assertBody(request("GET", "/giveaways/active"), running, "Running", "Prize",
            listOf(older, newer), runningStart, runningEnd, "ACTIVE")
        assertBody(request("GET", "/giveaways/products/$running", token("access")), running,
            "Running", "Prize", listOf(older, newer), runningStart, runningEnd, "ACTIVE")
        assertBody(request("GET", "/giveaways/products/$scheduled"), scheduled,
            "Scheduled", null, emptyList(), scheduledStart, scheduledEnd, "AWAITING_GIVEAWAY")
        val forged = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        for (path in listOf("/giveaways/active", "/giveaways/products/$running")) {
            assertEquals(200, request("GET", path).statusCode(), path)
            assertEquals(200, request("GET", path, token("access")).statusCode(), path)
            assertEquals(if (rejectVerify) 401 else 200, request("GET", path, token("verify")).statusCode(), path)
            for (invalid in listOf(token("refresh"), token("access", forged),
                token("access", expiry = Instant.now().minusSeconds(60)))) {
                assertEquals(401, request("GET", path, invalid).statusCode(), path)
            }
        }
        listOf(ended, disabled, ordinary, Long.MAX_VALUE).forEach { id ->
            val response = request("GET", "/giveaways/products/$id")
            assertEquals(404, response.statusCode(), "product $id: ${response.body()}")
            assertFalse(response.headers().firstValue("X-Robots-Tag").isPresent)
        }
        jdbc.update("UPDATE product SET giveaway_enabled = false WHERE id = ?", running)
        assertBody(request("GET", "/giveaways/active"), scheduled,
            "Scheduled", null, emptyList(), scheduledStart, scheduledEnd, "AWAITING_GIVEAWAY")
        jdbc.update("UPDATE product SET giveaway_enabled = false WHERE id = ?", scheduled)
        assertEquals(404, request("GET", "/giveaways/active").statusCode())
    }

    fun request(method: String, path: String, bearer: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        return client.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString())
    }
}

private val testPrivateKey: PrivateKey by lazy {
    val pem = ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
        .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
        .replace(Regex("\\s+"), "")
    KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
}

private fun token(type: String, signer: PrivateKey = testPrivateKey, expiry: Instant = Instant.now().plusSeconds(3600)) =
    Jwts.builder().claim("id", 1L).claim("login", "giveawayParity").claim("role", "USER")
        .claim("type", type).expiration(Date.from(expiry)).signWith(signer).compact()

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem"])
@ActiveProfiles("modern")
class ModernGiveawayHttpParityTest {
    @Value("\${local.server.port}") var port: Int = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    @Test fun `modern real HTTP serves the legacy public giveaway body and visibility`() =
        GiveawayHttpContract(port, jdbc, json).exercise(rejectVerify = true)

    @Test fun `modern real HTTP keeps token validation methods and neighboring paths closed`() {
        val http = GiveawayHttpContract(port, jdbc, json)
        val forged = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        val invalid = listOf(token("refresh"), token("verify"), token("access", forged),
            token("access", expiry = Instant.now().minusSeconds(60)))
        for (path in listOf("/giveaways/active", "/giveaways/products/42")) {
            assertEquals(404, http.request("GET", path).statusCode())
            invalid.forEach { assertEquals(401, http.request("GET", path, it).statusCode(), path) }
        }
        for ((method, path) in listOf("POST" to "/giveaways/active", "PUT" to "/giveaways/active",
            "DELETE" to "/giveaways/active", "POST" to "/giveaways/products/42",
            "PUT" to "/giveaways/products/42", "DELETE" to "/giveaways/products/42",
            "GET" to "/giveaways/active/", "GET" to "/giveaways/active/extra",
            "GET" to "/giveaways/products/", "GET" to "/giveaways/products/42/",
            "GET" to "/giveaways/products/42/extra", "GET" to "/giveaways/history",
            "GET" to "/giveaways/admin")) {
            assertEquals(401, http.request(method, path).statusCode(), "$method $path")
        }
        for (path in listOf("/giveaways/products//42", "/giveaways/products/42;mode=admin",
            "/giveaways/products/42%2Fextra", "/giveaways/products/42%5Cextra",
            "/giveaways/products/42%252Fextra")) {
            assertTrue(http.request("GET", path).statusCode() in setOf(400, 401), path)
        }
    }

    companion object {
        private val postgres = EmbeddedPostgres.start()
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) = databaseProperties(registry, postgres)
        @JvmStatic @AfterAll fun close() = postgres.close()
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem"])
@ActiveProfiles("legacy")
class LegacyGiveawayHttpParityTest {
    @Value("\${local.server.port}") var port: Int = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    @Test fun `legacy real reactive HTTP establishes public giveaway body and visibility`() =
        GiveawayHttpContract(port, jdbc, json).exercise(rejectVerify = false)

    @Test fun `legacy reactive decoder behavior is observed on both guest routes`() {
        val http = GiveawayHttpContract(port, jdbc, json)
        val forged = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        for (path in listOf("/giveaways/active", "/giveaways/products/42")) {
            assertEquals(404, http.request("GET", path).statusCode())
            assertEquals(404, http.request("GET", path, token("access")).statusCode())
            assertEquals(404, http.request("GET", path, token("verify")).statusCode())
            for (invalid in listOf(token("refresh"), token("access", forged),
                token("access", expiry = Instant.now().minusSeconds(60)))) {
                assertEquals(401, http.request("GET", path, invalid).statusCode(), path)
            }
        }
    }

    companion object {
        private val postgres = EmbeddedPostgres.start()
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) = databaseProperties(registry, postgres)
        @JvmStatic @AfterAll fun close() = postgres.close()
    }
}

private fun databaseProperties(registry: DynamicPropertyRegistry, postgres: EmbeddedPostgres) {
    val jdbc = "jdbc:postgresql://localhost:${postgres.port}/postgres"
    registry.add("spring.datasource.url") { jdbc }
    registry.add("spring.datasource.username") { "postgres" }
    registry.add("spring.datasource.password") { "" }
    registry.add("spring.flyway.url") { jdbc }
    registry.add("spring.flyway.user") { "postgres" }
    registry.add("spring.flyway.password") { "" }
    registry.add("spring.r2dbc.url") { "r2dbc:postgresql://localhost:${postgres.port}/postgres" }
    registry.add("spring.r2dbc.username") { "postgres" }
    registry.add("spring.r2dbc.password") { "" }
}
