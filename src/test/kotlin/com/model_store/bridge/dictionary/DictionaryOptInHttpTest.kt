package com.model_store.bridge.dictionary

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.modern.shared.config.MigrationScenarioProperties
import com.model_store.modern.catalog.dictionary.domain.DictionaryType
import com.model_store.modern.catalog.dictionary.infrastructure.JdbcDictionaryReadPort
import com.model_store.modern.catalog.dictionary.api.DictionaryController as ModernDictionaryController
import com.model_store.service.DictionaryService
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationContext
import org.springframework.core.io.ClassPathResource
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.mockito.Mockito.doAnswer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.main.web-application-type=reactive",
    "app.public-key-path=keys/test_public_key.pem",
    "app.private-key-path=keys/test_private_key.pem",
    "app.migration.scenarios.dictionary=modern",
])
@ActiveProfiles("legacy")
class DictionaryOptInHttpTest {
    @Value("\${local.server.port}") private var port: Int = 0
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var scenarios: MigrationScenarioProperties
    @Autowired lateinit var dictionary: DictionaryService
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender
    @MockitoSpyBean lateinit var readPort: JdbcDictionaryReadPort

    @Test
    fun `HTTP contract uses modern dictionary with existing role filter`() {
        assertEquals(MigrationScenarioProperties.Implementation.MODERN, scenarios.implementationFor("dictionary"))
        assertTrue(dictionary is DictionaryServiceBridge)
        assertEquals(listOf("PURCHASABLE", "PREORDER"), values(get("/dictionary?type=PRODUCT_AVAILABILITY")))
        assertEquals(listOf("PURCHASABLE", "PREORDER"), values(get("/dictionary?type=PRODUCT_AVAILABILITY", "USER")))
        assertEquals(setOf("PURCHASABLE", "PREORDER", "EXTERNAL_PRODUCT", "GIVEAWAY"),
            values(get("/dictionary?type=PRODUCT_AVAILABILITY", "ADMIN")).toSet())
        assertEquals(setOf("ALL", "PREORDER", "NON_PREORDER", "USED"),
            values(get("/dictionary?type=CATALOG_FLAGS")).toSet())
        assertEquals(400, get("/dictionary?type=UNKNOWN").statusCode())
    }

    @Test
    fun `neighbor routes and legacy scheduling remain in legacy graph`() {
        assertEquals(200, get("/categories").statusCode())
        assertTrue(context.containsBean("imageCleanupScheduler"))
        assertTrue(context.containsBean("productUpdateScheduler"))
        assertTrue(context.containsBean("productCleanupScheduler"))
        assertTrue(context.getBeansOfType(ModernDictionaryController::class.java).isEmpty())
    }

    @Test
    fun `JDBC runs on boundedElastic inside a JPA read transaction`() {
        val worker = AtomicReference<String>()
        val inTransaction = AtomicReference<Boolean>()
        doAnswer { invocation ->
            worker.set(Thread.currentThread().name)
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive())
            invocation.callRealMethod()
        }.`when`(readPort).findByType(DictionaryType.CURRENCY)

        assertEquals(200, get("/dictionary?type=CURRENCY").statusCode())
        assertTrue(worker.get().startsWith("boundedElastic-"), worker.get())
        assertEquals(true, inTransaction.get())
    }

    private fun values(response: HttpResponse<String>): List<String> {
        assertEquals(200, response.statusCode(), response.body())
        return json.readTree(response.body()).map { it["value"].asText() }
    }

    private fun get(path: String, role: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        if (role != null) builder.header("Authorization", "Bearer ${token(role)}")
        return HttpClient.newHttpClient().send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private val postgres = EmbeddedPostgres.start()

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val port = postgres.port
            val url = "jdbc:postgresql://localhost:$port/postgres"
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.url") { url }
            registry.add("spring.flyway.user") { "postgres" }
            registry.add("spring.flyway.password") { "" }
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://localhost:$port/postgres" }
            registry.add("spring.r2dbc.username") { "postgres" }
            registry.add("spring.r2dbc.password") { "" }
        }

        @JvmStatic @AfterAll fun close() = postgres.close()

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
