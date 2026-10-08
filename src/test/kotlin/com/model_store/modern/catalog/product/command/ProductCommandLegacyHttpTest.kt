package com.model_store.modern.catalog.product.command

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.util.Base64
import java.util.Date

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem"])
@ActiveProfiles("legacy")
class ProductCommandLegacyHttpTest {
    @Value("\${local.server.port}") private var port: Int = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var flyway: Flyway
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    @Test
    fun `legacy HTTP codes and body for ordinary and admin product commands`() {
        assertTrue(flyway.info().applied().isNotEmpty())
        val owner = participant("legacy16owner", "USER")
        val admin = participant("legacy16admin", "ADMIN")
        val ordinary = """{"name":"Legacy16 item","price":10,"currency":"RUB","availability":"PURCHASABLE"}"""
        val external = """{"name":"Legacy16 external","price":10,"currency":"RUB","availability":"EXTERNAL_PRODUCT",
                          "externalUrl":"https://example.test/item"}"""
        assertError(request("POST", "/products", ordinary, owner), 404, "TRANSFER_NOT_FOUND")
        assertError(request("POST", "/products", external, owner), 403, "ACCESS_DENIED")
        ready(owner)
        val created = request("POST", "/products", ordinary, owner)
        assertEquals(200, created.statusCode(), created.body())
        val id = created.body().toLong()
        assertEquals(owner, jdbc.queryForObject("SELECT participant_id FROM product WHERE id=?", Long::class.java, id))
        assertError(request("POST", "/products", ordinary, owner), 409, "PRODUCT_ALREADY_EXISTS")
        assertError(request("POST", "/products", """{"name":"Legacy16 count","price":10,"currency":"RUB",
            "availability":"PURCHASABLE","count":0}""", owner), 400, "INVALID_REQUEST")
        assertError(request("POST", "/products", """{"name":"Legacy16 price","currency":"RUB",
            "availability":"PURCHASABLE"}""", owner), 409, "DUPLICATE_KEY")
        assertError(request("POST", "/products", """{"name":"Legacy16 currency","price":10,
            "availability":"PURCHASABLE"}""", owner), 409, "DUPLICATE_KEY")
        assertEquals(200, request("PUT", "/product/$id", """{"name":"Legacy16 updated"}""", owner).statusCode())
        assertEquals("Legacy16 updated", jdbc.queryForObject("SELECT name FROM product WHERE id=?", String::class.java, id))
        assertEquals(403, request("PUT", "/admin/actions/product/$id?productStatus=BLOCKED", null, owner).statusCode())
        assertEquals(200, request("PUT", "/admin/actions/product/$id?productStatus=BLOCKED", null, admin, "ADMIN").statusCode())
        assertError(request("DELETE", "/product/$id", null, owner), 404, "PRODUCT_NOT_FOUND")
        assertEquals(200, request("PUT", "/admin/actions/product/$id?productStatus=ACTIVE", null, admin, "ADMIN").statusCode())
        assertEquals(200, request("DELETE", "/product/$id", null, owner).statusCode())
    }

    private fun assertError(response: HttpResponse<String>, status: Int, code: String) {
        assertEquals(status, response.statusCode(), response.body())
        assertEquals(code, json.readTree(response.body())["code"].asText())
    }

    private fun request(method: String, path: String, body: String?, actorId: Long,
                        role: String = "USER"): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("Authorization", "Bearer ${token(actorId, role)}")
        if (body != null) builder.header("Content-Type", "application/json")
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        return HttpClient.newHttpClient().send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun participant(login: String, role: String): Long = jdbc.queryForObject(
        """INSERT INTO participant(login,mail,password,status,role,deadline_sending,deadline_payment)
           VALUES (?,?,'hash','ACTIVE',?::participant_role,3,7) RETURNING id""",
        Long::class.java, login, "$login@example.test", role)!!

    private fun ready(owner: Long) {
        jdbc.update("INSERT INTO transfer(sending,price,currency,participant_id) VALUES ('PRODUCT_PICKUP',0,'RUB',?)", owner)
        jdbc.update("INSERT INTO social_network(type,login,participant_id) VALUES ('TELEGRAM','legacy16',?)", owner)
    }

    companion object {
        private val postgres = EmbeddedPostgres.start()
        private val privateKey: PrivateKey by lazy {
            val pem = ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s+"), "")
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        }

        private fun token(id: Long, role: String): String = Jwts.builder()
            .claim("id", id).claim("login", "legacy16").claim("role", role).claim("type", "access")
            .expiration(Date.from(Instant.now().plusSeconds(3600)))
            .signWith(privateKey).compact()

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

        @JvmStatic @AfterAll fun close() = postgres.close()
    }
}
