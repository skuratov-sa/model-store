package com.model_store.modern.catalog.giveaway.admin

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Date

/** Run unchanged against both real HTTP stacks and their own migrated PostgreSQL. */
private class AdminGiveawayContract(private val port: Int, private val jdbc: JdbcTemplate,
                                    private val json: ObjectMapper, private val modern: Boolean) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build()

    fun exercise() {
        val admin = participant("giveaway22parityadmin", "ADMIN")
        val user = participant("giveaway22parityuser", "USER")
        val adminToken = token(admin, "ADMIN")
        val userToken = token(user, "USER")
        val historyPath = "/admin/actions/giveaways/history"
        assertEquals(401, request("GET", historyPath).statusCode())
        assertEquals(403, request("GET", historyPath, bearer = userToken).statusCode())
        assertEquals(401, request("GET", historyPath,
            bearer = token(admin, "ADMIN", type = "refresh")).statusCode())
        assertEquals(if (modern) 401 else 200, request("GET", historyPath,
            bearer = token(admin, "ADMIN", type = "verify")).statusCode())
        assertEquals(if (modern) 403 else 200, request("GET", historyPath,
            bearer = token(admin, "ADMIN", type = "agent_access", issuedBy = "admin")).statusCode())
        assertEquals(401, request("GET", historyPath,
            bearer = token(admin, "ADMIN", expiry = Instant.now().minusSeconds(60))).statusCode())
        val forged = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        assertEquals(401, request("GET", historyPath,
            bearer = token(admin, "ADMIN", signer = forged)).statusCode())
        val start = Instant.now().plusSeconds(10 * 86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val end = start.plusSeconds(3600)
        fun offset(instant: Instant) = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atOffset(ZoneOffset.ofHours(3)))
        fun body(name: String, first: Instant, last: Instant, url: String = "https://t.me/prize") =
            json.writeValueAsString(mapOf("name" to name, "availability" to "GIVEAWAY",
                "giveaway" to mapOf("telegramUrl" to url, "startAt" to offset(first),
                    "endAt" to offset(last), "winnersCount" to 2, "rules" to "Rules", "homeText" to "Home")))

        val firstBody = body("Giveaway22 parity", start, end)
        assertEquals(401, request("POST", "/products", firstBody).statusCode())
        val denied = request("POST", "/products", firstBody, userToken)
        checkError(denied, 403, "ACCESS_DENIED", "Этот тип товара доступен только администратору")
        val missingSettings = request("POST", "/products",
            """{"name":"Missing22","availability":"GIVEAWAY"}""", adminToken)
        checkError(missingSettings, 400, "INVALID_REQUEST", "Укажите настройки розыгрыша")
        val badUrl = request("POST", "/products", body("Invalid22", start, end, "https://evil.test/x"), adminToken)
        checkError(badUrl, 400, "INVALID_REQUEST", "Некорректные настройки розыгрыша")
        val created = request("POST", "/products", firstBody, adminToken)
        assertEquals(200, created.statusCode(), created.body())
        val id = created.body().toLong()
        checkError(request("PUT", "/product/$id", """{"name":"Forbidden edit"}""", userToken),
            404, "PRODUCT_NOT_FOUND", "Не удалось выполнить операцию: не достаточно прав или его не существует")
        val productEdit = request("PUT", "/product/$id",
            """{"name":"Giveaway22 product edit","giveaway":{"winnersCount":4}}""", adminToken)
        assertEquals(200, productEdit.statusCode(), productEdit.body())
        assertEquals("Giveaway22 product edit", jdbc.queryForObject(
            "SELECT name FROM product WHERE id=?", String::class.java, id))
        assertEquals(4, jdbc.queryForObject("SELECT giveaway_winners_count FROM product WHERE id=?",
            Int::class.java, id))
        val second = request("POST", "/products", body("Second22", end, end.plusSeconds(3600)), adminToken)
        assertEquals(200, second.statusCode(), second.body())
        val overlap = request("POST", "/products", body("Overlap22", start.plusSeconds(1), end.plusSeconds(1)), adminToken)
        checkError(overlap, 400, "GIVEAWAY_WINDOW_OVERLAP",
            "Период розыгрыша пересекается с другим запланированным розыгрышем")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM product WHERE name='Overlap22'", Int::class.java))

        val history = request("GET", "/admin/actions/giveaways/history", bearer = adminToken)
        assertEquals(200, history.statusCode(), history.body())
        val item = json.readTree(history.body()).first { it["id"].asLong() == id }
        assertEquals("AWAITING_GIVEAWAY", item["status"].asText())
        assertEquals(start, Instant.parse(item["giveawayStartAt"].asText()))
        assertEquals(end, Instant.parse(item["giveawayEndAt"].asText()))
        assertTrue(item["count"].isNull)
        assertTrue(item["prepaymentAmount"].isNull)
        assertEquals(4, item["giveawayWinnersCount"].asInt())
        assertEquals(22, item.size())
        val details = request("GET", "/admin/actions/giveaways/$id", bearer = adminToken)
        assertEquals(200, details.statusCode(), details.body())
        val node = json.readTree(details.body())
        assertEquals(id, node["product"]["id"].asLong())
        assertEquals(0, node["imageIds"].size())
        assertEquals(0, node["categories"].size())
        assertEquals(3, node.size())
        checkError(request("GET", "/admin/actions/giveaways/${Long.MAX_VALUE}", bearer = adminToken),
            404, "PRODUCT_NOT_FOUND", "Розыгрыш не найден")
        checkError(request("GET", "/admin/actions/giveaways/history?page=-1", bearer = adminToken),
            400, "INVALID_REQUEST", "Некорректная страница истории")
        val changed = request("PUT", "/admin/actions/giveaways/$id",
            """{"telegramUrl":" https://telegram.me/updated ","winnersCount":3}""", adminToken)
        assertEquals(200, changed.statusCode(), changed.body())
        assertEquals("https://telegram.me/updated", jdbc.queryForObject(
            "SELECT giveaway_telegram_url FROM product WHERE id=?", String::class.java, id))
        assertEquals(3, jdbc.queryForObject("SELECT giveaway_winners_count FROM product WHERE id=?", Int::class.java, id))
    }

    private fun checkError(response: HttpResponse<String>, status: Int, code: String, message: String) {
        assertEquals(status, response.statusCode(), response.body())
        val body = json.readTree(response.body())
        assertEquals(code, body["code"].asText())
        assertEquals(message, body["message"].asText())
        assertEquals(status, body["status"].asInt())
        assertTrue(body["details"].isNull)
        assertEquals(setOf("code", "message", "status", "timestamp", "details"),
            body.fieldNames().asSequence().toSet())
    }

    private fun request(method: String, path: String, body: String? = null,
                        bearer: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .timeout(Duration.ofSeconds(10))
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        if (body != null) builder.header("Content-Type", "application/json")
        return http.send(builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody()
            else HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun participant(login: String, role: String): Long = jdbc.queryForObject(
        """INSERT INTO participant(login,mail,password,status,role,deadline_sending,deadline_payment)
            VALUES (?,?,'hash','ACTIVE',CAST(? AS participant_role),3,7) RETURNING id""",
        Long::class.java, login, "$login@example.test", role)!!
}

private val privateKey: PrivateKey by lazy {
    val pem = ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
        .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
        .replace(Regex("\\s+"), "")
    KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
}

private fun token(id: Long, role: String, type: String = "access", issuedBy: String? = null,
                  expiry: Instant = Instant.now().plusSeconds(3600), signer: PrivateKey = privateKey): String {
    val builder = Jwts.builder().claim("id", id).claim("login", "giveaway22")
        .claim("role", role).claim("type", type)
    if (issuedBy != null) builder.claim("issuedBy", issuedBy)
    return builder.expiration(Date.from(expiry)).signWith(signer).compact()
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem"])
@ActiveProfiles("modern")
class ModernAdminGiveawayRealHttpTest {
    @Value("\${local.server.port}") var port: Int = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    @Test fun contract() = AdminGiveawayContract(port, jdbc, json, modern = true).exercise()

    companion object {
        private val postgres = EmbeddedPostgres.start()
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) = db(registry, postgres)
        @JvmStatic @AfterAll fun close() = postgres.close()
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem"])
@ActiveProfiles("legacy")
class LegacyAdminGiveawayRealHttpTest {
    @Value("\${local.server.port}") var port: Int = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    @Test fun contract() = AdminGiveawayContract(port, jdbc, json, modern = false).exercise()

    companion object {
        private val postgres = EmbeddedPostgres.start()
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) = db(registry, postgres)
        @JvmStatic @AfterAll fun close() = postgres.close()
    }
}

private fun db(registry: DynamicPropertyRegistry, postgres: EmbeddedPostgres) {
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
