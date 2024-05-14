package com.model_store.configuration;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties({ R2dbcProperties.class, FlywayProperties.class })
public class BaseConfiguration { }