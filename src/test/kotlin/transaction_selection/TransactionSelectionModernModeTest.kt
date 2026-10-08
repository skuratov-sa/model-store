package transaction_selection

import com.amazonaws.services.s3.AmazonS3
import com.model_store.ModelStoreApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@SpringBootTest(classes = [ModelStoreApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["spring.main.web-application-type=servlet"])
@ActiveProfiles("modern")
@Import(TransactionSelectionModernModeTest.ProbeConfiguration::class)
class TransactionSelectionModernModeTest {
    @field:MockitoBean lateinit var s3: AmazonS3
    @field:MockitoBean lateinit var jwtDecoder: JwtDecoder
    @Autowired lateinit var probe: JpaProbe
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `modern mode routes unqualified synchronous commands to JPA`() {
        jdbc.execute("CREATE TABLE transaction_selection_probe (marker text NOT NULL)")
        assertTrue(AopUtils.isAopProxy(probe))
        val committed = "modern-commit-${UUID.randomUUID()}"
        val rolledBack = "modern-rollback-${UUID.randomUUID()}"
        probe.commit(committed)
        assertEquals(1, count(committed))
        assertThrows(IllegalStateException::class.java) { probe.rollback(rolledBack) }
        assertEquals(0, count(rolledBack))
    }

    private fun count(marker: String): Int = jdbc.queryForObject(
        "SELECT count(*) FROM transaction_selection_probe WHERE marker = ?", Int::class.java, marker,
    )!!

    @TestConfiguration(proxyBeanMethods = false)
    @Profile("modern")
    class ProbeConfiguration {
        @Bean fun jpaProbe(entityManager: EntityManager) = JpaProbe(entityManager)
    }

    open class JpaProbe(private val entityManager: EntityManager) {
        @Transactional
        open fun commit(marker: String) {
            insert(marker)
        }

        @Transactional
        open fun rollback(marker: String) {
            insert(marker)
            throw IllegalStateException("rollback")
        }

        private fun insert(marker: String) {
            entityManager.createNativeQuery("INSERT INTO transaction_selection_probe(marker) VALUES (:marker)")
                .setParameter("marker", marker).executeUpdate()
        }
    }

    companion object {
        private val postgres = EmbeddedPostgres.start()

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val port = postgres.port
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://localhost:$port/postgres" }
            registry.add("spring.r2dbc.username") { "postgres" }
            registry.add("spring.r2dbc.password") { "" }
            registry.add("spring.datasource.url") { "jdbc:postgresql://localhost:$port/postgres" }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.url") { "jdbc:postgresql://localhost:$port/postgres" }
            registry.add("spring.flyway.user") { "postgres" }
            registry.add("spring.flyway.password") { "" }
        }
    }
}
