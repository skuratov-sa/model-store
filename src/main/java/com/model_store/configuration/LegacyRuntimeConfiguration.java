package com.model_store.configuration;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.config.EnableWebFlux;

@Configuration(proxyBeanMethods = false)
@Profile("!modern")
@EnableR2dbcRepositories(basePackages = "com.model_store.repository")
@EnableScheduling
@EnableWebFlux
@ConfigurationPropertiesScan("com.model_store.configuration.property")
public class LegacyRuntimeConfiguration {
}
