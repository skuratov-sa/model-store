package com.model_store.modern.catalog.category

import com.amazonaws.services.s3.AmazonS3
import com.model_store.service.CategoryService
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringBootTest(properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem"])
@ActiveProfiles("legacy")
class CategoryLegacyBridgeTest {
    @Autowired lateinit var categories: CategoryService
    @Autowired lateinit var jdbc: JdbcTemplate
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    @Test
    fun `default bridge keeps seeded legacy category tree`() {
        val roots = categories.getCategories().block().orEmpty()
        assertTrue(roots.any { it.name == "Каталог" })
    }

    @Test
    fun `default bridge delegates writes and product links to legacy`() {
        val categoryId = categories.createCategory("legacy pilot", null).block()!!
        categories.updateCategory(categoryId, "legacy renamed").block()
        assertTrue(categories.getCategories().block()!!.any { it.id == categoryId && it.name == "legacy renamed" })

        val sellerId = jdbc.queryForObject(
            "insert into participant (login, password, status) values ('category-legacy', 'x', 'ACTIVE') returning id",
            Long::class.java,
        )!!
        val productId = jdbc.queryForObject(
            "insert into product (name, price, currency, participant_id) values ('legacy', 10, 'RUB', ?) returning id",
            Long::class.java, sellerId,
        )!!
        categories.addLinkProductAndCategories(listOf(categoryId), productId).block()
        assertEquals(listOf(categoryId), categories.findByProductId(productId).map { it.id }.collectList().block())
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
    }
}
