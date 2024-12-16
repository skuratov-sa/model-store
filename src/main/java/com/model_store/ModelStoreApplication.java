package com.model_store;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.aws.context.config.annotation.EnableContextCredentials;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.web.reactive.config.EnableWebFlux;

@EnableWebFlux
@ConfigurationPropertiesScan
@EnableContextCredentials
@SpringBootApplication
@EnableR2dbcRepositories
public class ModelStoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(ModelStoreApplication.class, args);
    }
}
