package com.model_store.modern.catalog.dictionary

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.modern.catalog.dictionary.domain.DictionaryType
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
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
    properties = ["app.public-key-path=keys/test_public_key.pem"],
)
@ActiveProfiles("modern")
class DictionaryHttpTest {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Value("\${local.server.port}") var port: Int = 0
    @MockitoBean lateinit var s3: AmazonS3

    @Test
    fun `enum, rows and read only schema match existing migrations`() {
        val labels = jdbc.queryForList(
            "select enumlabel from pg_enum where enumtypid = 'dictionary_type'::regtype order by enumsortorder",
            String::class.java,
        )
        assertEquals(
            listOf("SOCIAL_NETWORK", "CURRENCY", "SHOPPING_METHODS", "TRANSFER_MONEY", "DEADLINE_SENDING",
                "DEADLINE_PAYMENT", "SORT_BY", "PRODUCT_AVAILABILITY", "ORDER_STATUS", "CATALOG_FLAGS"),
            labels,
        )
        assertEquals(labels, DictionaryType.entries.map { it.name })
        assertEquals(labels.toSet(), com.model_store.model.constant.DictionaryType.values().map { it.name }.toSet())
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from pg_constraint where conrelid = 'dictionary'::regclass and contype = 'p'",
            Int::class.java,
        ))
        val response = request("CATALOG_FLAGS", "USER")
        assertEquals(200, response.statusCode())
        assertEquals(setOf("ALL", "PREORDER", "NON_PREORDER", "USED"), values(response).toSet())
        assertTrue(json.readTree(response.body()).all { it["type"].asText() == "CATALOG_FLAGS" && it["description"].asText().isNotBlank() })
    }

    @Test
    fun `user sees public availability and admin sees complete database list`() {
        val databaseValues = jdbc.queryForList(
            "select value from dictionary where type = 'PRODUCT_AVAILABILITY'::dictionary_type",
            String::class.java,
        )
        assertEquals(listOf("PURCHASABLE", "PREORDER"), values(request("PRODUCT_AVAILABILITY", "USER")))
        assertEquals(databaseValues, values(request("PRODUCT_AVAILABILITY", "ADMIN")))
        assertTrue(databaseValues.containsAll(listOf("EXTERNAL_PRODUCT", "GIVEAWAY")))
    }

    @Test
    fun `unknown type is rejected`() {
        assertEquals(400, request("UNKNOWN", "USER").statusCode())
    }

    private fun values(response: HttpResponse<String>): List<String> =
        json.readTree(response.body()).map { it["value"].asText() }

    private fun request(type: String, role: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port/dictionary?type=$type"))
        if (role != null) builder.header("Authorization", "Bearer ${token(role)}")
        return HttpClient.newHttpClient().send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private val postgres = EmbeddedPostgres.start()

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val url = "jdbc:postgresql://localhost:${postgres.port}/postgres"
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.url") { url }
            registry.add("spring.flyway.user") { "postgres" }
            registry.add("spring.flyway.password") { "" }
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
