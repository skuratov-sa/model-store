package com.model_store.modern.catalog.category

import com.amazonaws.services.s3.AmazonS3
import io.jsonwebtoken.Jwts
import com.model_store.modern.catalog.category.application.CategoryUseCases
import com.model_store.modern.catalog.category.api.CategoryController
import com.model_store.modern.shared.domain.Actor
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.server.ResponseStatusException
import org.springframework.core.io.ClassPathResource
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
class CategoryPersistenceTest {
    @Autowired lateinit var categories: CategoryUseCases
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var controller: CategoryController
    @Value("\${local.server.port}") var port: Int = 0
    @MockitoBean lateinit var s3: AmazonS3

    @Test
    fun `creates child under existing parent and rejects absent parent without writing`() {
        val before = categories.tree().flatMap { it.children }.size
        val parent = categories.create("Pilot root", null)
        val child = categories.create("Pilot child", parent)
        assertEquals(child, categories.tree().first { it.id == parent }.children.single().id)
        assertThrows(IllegalArgumentException::class.java) { categories.create("orphan", Long.MAX_VALUE) }
        assertEquals(before + 1, categories.tree().flatMap { it.children }.size)
    }

    @Test
    fun `writes product links only after all categories are valid`() {
        val sellerId = jdbc.queryForObject(
            "insert into participant (login, password, status) values ('category-pilot', 'x', 'ACTIVE') returning id",
            Long::class.java,
        )!!
        val productId = jdbc.queryForObject(
            "insert into product (name, price, currency, participant_id) values ('pilot', 10, 'RUB', ?) returning id",
            Long::class.java,
            sellerId,
        )!!
        val categoryId = categories.create("Pilot link", null)
        assertThrows(IllegalArgumentException::class.java) {
            categories.addLinks(productId, listOf(categoryId, Long.MAX_VALUE))
        }
        assertEquals(emptyList<Long>(), categories.byProduct(productId).map { it.id })
        categories.addLinks(productId, listOf(categoryId))
        assertEquals(listOf(categoryId), categories.byProduct(productId).map { it.id })
    }

    @Test
    fun `ordinary actor cannot change categories`() {
        val user = Actor(1, "user", "USER")
        assertThrows(ResponseStatusException::class.java) { controller.create(user, "forbidden", null) }
        assertThrows(ResponseStatusException::class.java) { controller.rename(user, 1, "forbidden") }
    }

    @Test
    fun `MVC tree keeps childs JSON and admin route rejects user`() {
        val client = HttpClient.newHttpClient()
        val get = HttpRequest.newBuilder(URI("http://localhost:$port/categories"))
            .header("Authorization", "Bearer ${token("ADMIN")}").GET().build()
        val tree = client.send(get, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, tree.statusCode())
        assertTrue(tree.body().contains("\"childs\""))

        val post = HttpRequest.newBuilder(URI("http://localhost:$port/admin/actions/categories?name=blocked"))
            .header("Authorization", "Bearer ${token("USER")}")
            .POST(HttpRequest.BodyPublishers.noBody()).build()
        assertEquals(403, client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode())

        val adminPost = HttpRequest.newBuilder(URI("http://localhost:$port/admin/actions/categories?name=http-pilot"))
            .header("Authorization", "Bearer ${token("ADMIN")}")
            .POST(HttpRequest.BodyPublishers.noBody()).build()
        val created = client.send(adminPost, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, created.statusCode())
        val categoryId = created.body().trim().toLong()
        val adminPut = HttpRequest.newBuilder(
            URI("http://localhost:$port/admin/actions/categories?categoryId=$categoryId&name=http-renamed"),
        ).header("Authorization", "Bearer ${token("ADMIN")}")
            .PUT(HttpRequest.BodyPublishers.noBody()).build()
        assertEquals(200, client.send(adminPut, HttpResponse.BodyHandlers.ofString()).statusCode())
        assertTrue(client.send(get, HttpResponse.BodyHandlers.ofString()).body().contains("http-renamed"))
    }

    companion object {
        private val postgres = EmbeddedPostgres.start()

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val jdbc = "jdbc:postgresql://localhost:${postgres.port}/postgres"
            registry.add("spring.datasource.url") { jdbc }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.url") { jdbc }
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
