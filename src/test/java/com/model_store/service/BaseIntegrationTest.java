package com.model_store.service;

import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaseIntegrationTest {

    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("model_store")
            .withUsername("root")
            .withPassword("root")
            .waitingFor(
                    Wait.forListeningPort()
                            .withStartupTimeout(Duration.ofSeconds(5))
                            .withStartupTimeout(Duration.ofSeconds(5))
            );

    static {
        // Явно стартуем контейнер до использования
        POSTGRES.start();
        System.out.println("Postgres started at " + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432));
    }

    @DynamicPropertySource
    static void registerPgProperties(DynamicPropertyRegistry registry) {
        // R2DBC URL
        registry.add("spring.r2dbc.url", () ->
                "r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName()
        );
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);

        // JDBC для Flyway
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }
}
