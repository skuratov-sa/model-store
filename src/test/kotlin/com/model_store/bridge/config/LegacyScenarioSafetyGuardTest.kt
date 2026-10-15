package com.model_store.bridge.config

import com.model_store.modern.shared.config.MigrationScenarioProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class LegacyScenarioSafetyGuardTest {
    private fun runner(profile: String) = ApplicationContextRunner()
        .withInitializer { it.environment.setActiveProfiles(profile) }
        .withUserConfiguration(PropertiesConfiguration::class.java, LegacyScenarioSafetyGuard::class.java)

    @Test
    fun `legacy startup accepts default and explicit category legacy`() {
        runner("legacy").run { context ->
            assertNull(context.startupFailure)
            assertNotNull(context.getBean(LegacyScenarioSafetyGuard::class.java))
            assertEquals(MigrationScenarioProperties.Implementation.LEGACY,
                context.getBean(MigrationScenarioProperties::class.java).implementationFor("category"))
        }
        runner("legacy").withPropertyValues("app.migration.scenarios.category=legacy").run { context ->
            assertNull(context.startupFailure)
            assertNotNull(context.getBean(LegacyScenarioSafetyGuard::class.java))
        }
    }

    @Test
    fun `legacy startup rejects unsafe category modern before context is ready`() {
        runner("legacy").withPropertyValues("app.migration.scenarios.category=modern").run { context ->
            val failure = context.startupFailure
            assertNotNull(failure)
            val causes = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n")
            assertTrue(causes.contains("app.migration.scenarios.category=modern"), causes)
        }
    }

    @Test
    fun `dictionary modern remains allowed in legacy`() {
        runner("legacy").withPropertyValues("app.migration.scenarios.dictionary=modern").run { context ->
            assertNull(context.startupFailure)
            assertNotNull(context.getBean(LegacyScenarioSafetyGuard::class.java))
            assertEquals(MigrationScenarioProperties.Implementation.MODERN,
                context.getBean(MigrationScenarioProperties::class.java).implementationFor("dictionary"))
        }
    }

    @Test
    fun `modern profile does not register legacy guard`() {
        runner("modern").withPropertyValues("app.migration.scenarios.category=modern").run { context ->
            assertNull(context.startupFailure)
            assertTrue(context.getBeansOfType(LegacyScenarioSafetyGuard::class.java).isEmpty())
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MigrationScenarioProperties::class)
    class PropertiesConfiguration
}
