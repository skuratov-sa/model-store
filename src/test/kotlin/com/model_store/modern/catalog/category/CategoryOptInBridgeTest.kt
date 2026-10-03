package com.model_store.modern.catalog.category

import com.amazonaws.services.s3.AmazonS3
import com.model_store.modern.shared.config.MigrationScenarioProperties
import com.model_store.service.CategoryService
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.mockito.Mockito.`when`

@SpringBootTest(properties = [
    "app.public-key-path=keys/test_public_key.pem",
    "app.private-key-path=keys/test_private_key.pem",
])
@ActiveProfiles("legacy")
class CategoryOptInBridgeTest {
    @Autowired lateinit var categories: CategoryService
    @Autowired lateinit var jdbc: JdbcTemplate
    @MockitoBean lateinit var scenarios: MigrationScenarioProperties
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    @BeforeEach
    fun enableAdapterBranch() {
        `when`(scenarios.implementationFor("category")).thenReturn(MigrationScenarioProperties.Implementation.MODERN)
    }

    @Test
    fun `adapter covers category reads writes and product links when opted in`() {
        val parent = categories.createCategory("opt-in root", null).block()!!
        categories.updateCategory(parent, "opt-in renamed").block()
        assertTrue(categories.getCategories().block()!!.any { it.id == parent && it.name == "opt-in renamed" })

        val sellerId = jdbc.queryForObject(
            "insert into participant (login, password, status) values ('category-opt-in', 'x', 'ACTIVE') returning id",
            Long::class.java,
        )!!
        val productId = jdbc.queryForObject(
            "insert into product (name, price, currency, participant_id) values ('opt-in', 10, 'RUB', ?) returning id",
            Long::class.java, sellerId,
        )!!
        categories.addLinkProductAndCategories(listOf(parent), productId).block()
        assertEquals(listOf(parent), categories.findByProductId(productId).map { it.id }.collectList().block())
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
