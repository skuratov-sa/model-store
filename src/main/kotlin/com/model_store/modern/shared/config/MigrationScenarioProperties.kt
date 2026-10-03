package com.model_store.modern.shared.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Per-use-case implementation selection for transition adapters. Unconfigured cases stay legacy. */
@ConfigurationProperties("app.migration")
data class MigrationScenarioProperties(
    val scenarios: Map<String, Implementation> = emptyMap(),
) {
    fun implementationFor(scenario: String): Implementation = scenarios[scenario] ?: Implementation.LEGACY

    enum class Implementation {
        LEGACY,
        MODERN,
    }
}
