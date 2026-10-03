package com.model_store;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import com.model_store.configuration.ModernModeLegacyFilter;

@SpringBootApplication
@ComponentScan(excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM, classes = ModernModeLegacyFilter.class))
public class ModelStoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(ModelStoreApplication.class, args);
    }
}
