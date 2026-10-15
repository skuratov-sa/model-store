package com.model_store.bridge.config

import com.model_store.modern.shared.config.MigrationScenarioProperties
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** Rejects an opt-in whose R2DBC product write cannot share the category JPA transaction. */
@Component
@Profile("!modern")
class LegacyScenarioSafetyGuard(scenarios: MigrationScenarioProperties) {
    init {
        if (scenarios.implementationFor("category") == MigrationScenarioProperties.Implementation.MODERN) {
            throw IllegalStateException(
                "app.migration.scenarios.category=modern is unsafe in legacy mode: " +
                    "ProductServiceImpl writes through R2DBC while CategoryServiceBridge writes through JPA; " +
                    "keep category=legacy until the product/category transaction boundary is integrated",
            )
        }
    }
}
