package com.model_store.modern.catalog.dictionary

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
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
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem"],
)
@ActiveProfiles("legacy")
class DictionaryLegacyHttpTest {
    @Value("\${local.server.port}") var port: Int = 0
    @Autowired lateinit var json: ObjectMapper
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    @Test
    fun `guest user and admin retain legacy availability contract`() {
        assertEquals(listOf("PURCHASABLE", "PREORDER"), values(request(null)))
        assertEquals(listOf("PURCHASABLE", "PREORDER"), values(request("USER")))
        assertEquals(setOf("PURCHASABLE", "PREORDER", "EXTERNAL_PRODUCT", "GIVEAWAY"), values(request("ADMIN")).toSet())
    }

    private fun values(response: HttpResponse<String>): List<String> {
        assertEquals(200, response.statusCode(), response.body())
        return json.readTree(response.body()).map { it["value"].asText() }
    }

    private fun request(role: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port/dictionary?type=PRODUCT_AVAILABILITY"))
        if (role != null) builder.header("Authorization", "Bearer ${token(role)}")
        return HttpClient.newHttpClient().send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private val postgres = EmbeddedPostgres.start()

        @JvmStatic
        @DynamicPropertySource
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

        @JvmStatic
        @AfterAll
        fun close() = postgres.close()

        private fun token(role: String): String {
            val pem = ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s+"), "")
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
            return Jwts.builder().claim("id", 1L).claim("login", "pilot").claim("role", role)
                .claim("type", "access").expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(key).compact()
        }
    }
}
