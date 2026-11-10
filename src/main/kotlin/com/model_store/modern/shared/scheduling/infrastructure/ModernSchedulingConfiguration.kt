package com.model_store.modern.shared.scheduling.infrastructure

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling

/** Runtime selection only; owner applications retain all maintenance rules and transactions. */
@Configuration(proxyBeanMethods = false)
@Profile("modern")
@EnableScheduling
class ModernSchedulingConfiguration
