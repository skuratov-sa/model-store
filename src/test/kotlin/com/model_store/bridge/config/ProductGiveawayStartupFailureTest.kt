package com.model_store.bridge.config

import com.amazonaws.services.s3.AmazonS3
import com.model_store.ModelStoreApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestContextManager
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.concurrent.atomic.AtomicInteger

/** Real application startup must fail before it can accept an unsafe opt-in or write business rows. */
@Execution(ExecutionMode.SAME_THREAD)
class ProductGiveawayStartupFailureTest {
    @Test
    @Timeout(90)
    fun `safe lazy legacy reaches readiness and the probe observes it`() {
        StartupReadinessProbe.ready.set(0)
        StartupReadinessProbe.acceptingTraffic.set(0)
        val instance = SafeLazyLegacyStartup()
        val manager = TestContextManager(instance.javaClass)
        manager.beforeTestClass()
        try {
            manager.prepareTestInstance(instance)
            assertEquals(1, StartupReadinessProbe.ready.get())
            assertEquals(1, StartupReadinessProbe.acceptingTraffic.get())
            assertTrue(manager.testContext.applicationContext.containsBean("legacyScenarioSafetyGuard"))
        } finally {
            manager.afterTestClass()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["product", "giveaway", "lazy-product", "lazy-giveaway", "lazy-category"])
    @Timeout(90)
    fun `unsupported opt-in fails real legacy startup without business writes`(variant: String) {
        val scenario = variant.removePrefix("lazy-")
        Flyway.configure().dataSource(postgres.postgresDatabase).load().migrate()
        val jdbc = JdbcTemplate(postgres.postgresDatabase)
        seed(jdbc, variant)
        val tables = listOf("product", "\"order\"", "order_status_history", "product_basket", "product_favorite",
            "product_category", "category", "image", "participant", "address", "transfer")
        fun snapshot() = tables.associateWith { jdbc.queryForList("SELECT * FROM $it ORDER BY id") }
        val before = snapshot()
        StartupReadinessProbe.ready.set(0)
        StartupReadinessProbe.acceptingTraffic.set(0)
        val instance = when (variant) {
            "product" -> UnsafeProductLegacyStartup()
            "giveaway" -> UnsafeGiveawayLegacyStartup()
            "lazy-product" -> UnsafeLazyProductLegacyStartup()
            "lazy-giveaway" -> UnsafeLazyGiveawayLegacyStartup()
            else -> UnsafeLazyCategoryLegacyStartup()
        }
        val manager = TestContextManager(instance.javaClass)
        manager.beforeTestClass()
        try {
            val failure = assertThrows(IllegalStateException::class.java) { manager.prepareTestInstance(instance) }
            val causes = generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n")
            val diagnostic = if (scenario == "category") "is unsafe in legacy mode" else "is unsupported in legacy mode"
            assertTrue(causes.contains("app.migration.scenarios.$scenario=modern $diagnostic"), causes)
            assertEquals(0, StartupReadinessProbe.ready.get(), "ApplicationReadyEvent must not be published")
            assertEquals(0, StartupReadinessProbe.acceptingTraffic.get(), "Readiness must never accept traffic")
            assertEquals(before, snapshot())
        } finally {
            manager.afterTestClass()
        }
    }

    private fun seed(jdbc: JdbcTemplate, variant: String) {
        val owner = jdbc.queryForObject("""INSERT INTO participant(login,mail,password,status,role,deadline_sending,deadline_payment)
            VALUES (?,?,'hash','ACTIVE','USER',3,7) RETURNING id""", Long::class.java, "startup23-$variant", "$variant@example.test")!!
        val item = jdbc.queryForObject("""INSERT INTO product(name,price,currency,participant_id,status,count,expiration_date)
            VALUES (?,10,'RUB',?,'ACTIVE',2,NOW()+INTERVAL '365 days') RETURNING id""", Long::class.java, "Startup23 $variant", owner)!!
        val category = jdbc.queryForObject("INSERT INTO category(name) VALUES (?) RETURNING id", Long::class.java, "Startup23 $variant")!!
        jdbc.update("INSERT INTO product_category(product_id,category_id) VALUES (?,?)", item, category)
        jdbc.update("INSERT INTO product_basket(product_id,participant_id,count) VALUES (?,?,1)", item, owner)
        jdbc.update("INSERT INTO product_favorite(product_id,participant_id) VALUES (?,?)", item, owner)
        jdbc.update("INSERT INTO image(tag,status,entity_id,uploaded_by) VALUES ('PRODUCT','ACTIVE',?,?)", item, owner)
        val address = jdbc.queryForObject("INSERT INTO address(country,city) VALUES ('RU','Moscow') RETURNING id", Long::class.java)!!
        val transfer = jdbc.queryForObject("""INSERT INTO transfer(sending,price,currency,participant_id)
            VALUES ('PRODUCT_PICKUP',0,'RUB',?) RETURNING id""", Long::class.java, owner)!!
        jdbc.update("""INSERT INTO "order"(seller_id,customer_id,product_id,count,status,address_id,transfer_id,
            product_name,product_unit_price,product_currency,product_availability)
            VALUES (?,?,?,1,'BOOKED',?,?,'Startup23',10,'RUB','PURCHASABLE')""", owner, owner, item, address, transfer)
    }

    companion object {
        val postgres: EmbeddedPostgres = EmbeddedPostgres.start()
        @JvmStatic @AfterAll fun close() = postgres.close()
    }
}

@ActiveProfiles("legacy")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ContextConfiguration(initializers = [StartupReadinessProbe::class])
@TestPropertySource(properties = ["server.address=127.0.0.1", "spring.config.import="])
abstract class UnsafeLegacyStartup {
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    companion object {
        @JvmStatic @DynamicPropertySource fun db(registry: DynamicPropertyRegistry) =
            ProductGiveawayRuntimeContract.database(registry, ProductGiveawayStartupFailureTest.postgres)
    }
}

@SpringBootTest(classes = [ModelStoreApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem",
        "app.migration.scenarios.product=modern"])
class UnsafeProductLegacyStartup : UnsafeLegacyStartup()

@SpringBootTest(classes = [ModelStoreApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem",
        "app.migration.scenarios.giveaway=modern"])
class UnsafeGiveawayLegacyStartup : UnsafeLegacyStartup()

@SpringBootTest(classes = [ModelStoreApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem",
        "app.migration.scenarios.product=modern", "spring.main.lazy-initialization=true"])
class UnsafeLazyProductLegacyStartup : UnsafeLegacyStartup()

@SpringBootTest(classes = [ModelStoreApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem",
        "app.migration.scenarios.giveaway=modern", "spring.main.lazy-initialization=true"])
class UnsafeLazyGiveawayLegacyStartup : UnsafeLegacyStartup()

@SpringBootTest(classes = [ModelStoreApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem",
        "app.migration.scenarios.category=modern", "spring.main.lazy-initialization=true"])
class UnsafeLazyCategoryLegacyStartup : UnsafeLegacyStartup()

@SpringBootTest(classes = [ModelStoreApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem",
        "spring.main.lazy-initialization=true", "app.migration.scenarios.product=legacy",
        "app.migration.scenarios.giveaway=legacy", "app.migration.scenarios.category=legacy",
        "app.migration.scenarios.dictionary=modern"])
class SafeLazyLegacyStartup : UnsafeLegacyStartup()

class StartupReadinessProbe : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(context: ConfigurableApplicationContext) {
        context.addApplicationListener(ApplicationListener<ApplicationEvent> { event ->
            if (event is ApplicationReadyEvent) ready.incrementAndGet()
            if (event is AvailabilityChangeEvent<*> && event.state == ReadinessState.ACCEPTING_TRAFFIC)
                acceptingTraffic.incrementAndGet()
        })
    }

    companion object {
        val ready = AtomicInteger()
        val acceptingTraffic = AtomicInteger()
    }
}
