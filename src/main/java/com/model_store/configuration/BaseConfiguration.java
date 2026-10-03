package com.model_store.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!modern")
public class BaseConfiguration {
}
