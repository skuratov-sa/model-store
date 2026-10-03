package com.model_store.modern.shared.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import javax.sql.DataSource

/** Keeps the modern persistence graph available to the transition adapters in either runtime mode. */
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(basePackages = ["com.model_store.modern"])
@ConfigurationPropertiesScan("com.model_store.modern")
class JpaPersistenceConfiguration {
    @Bean
    fun dataSource(
        @Value("\${spring.datasource.url}") url: String,
        @Value("\${spring.datasource.username}") username: String,
        @Value("\${spring.datasource.password}") password: String,
        @Value("\${spring.datasource.driver-class-name:org.postgresql.Driver}") driver: String,
    ): DataSource = HikariDataSource().apply {
        jdbcUrl = url
        this.username = username
        this.password = password
        driverClassName = driver
    }
}
