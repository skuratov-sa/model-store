package com.model_store.bridge.config

import com.model_store.modern.shared.config.MigrationScenarioProperties
import org.springframework.context.annotation.Profile
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/** Rejects opt-ins that cannot preserve the legacy R2DBC product transaction and call graph. */
@Component
@Profile("!modern")
@Lazy(false) // A global lazy-init setting must not defer a safety check beyond readiness.
class LegacyScenarioSafetyGuard(scenarios: MigrationScenarioProperties) {
    init {
        if (scenarios.implementationFor("category") == MigrationScenarioProperties.Implementation.MODERN) {
            throw IllegalStateException(
                "app.migration.scenarios.category=modern is unsafe in legacy mode: " +
                    "ProductServiceImpl writes through R2DBC while CategoryServiceBridge writes through JPA; " +
                    "keep category=legacy until the product/category transaction boundary is integrated",
            )
        }
        for (scenario in listOf("product", "giveaway")) {
            if (scenarios.implementationFor(scenario) == MigrationScenarioProperties.Implementation.MODERN) {
                throw IllegalStateException(
                    "app.migration.scenarios.$scenario=modern is unsupported in legacy mode: " +
                        "ProductServiceImpl is shared by product/giveaway HTTP, orders, basket and agent calls; " +
                        "R2DBC locks and writes cannot join a JPA transaction, and internal calls bypass a bridge; " +
                        "keep $scenario=legacy or start the full modern profile",
                )
            }
        }
    }
}
