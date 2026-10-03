package com.model_store.configuration;

import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;

import java.io.IOException;

/** Keeps the legacy application graph out of the servlet-only modern profile. */
public final class ModernModeLegacyFilter implements TypeFilter, EnvironmentAware {
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory) throws IOException {
        if (!environment.matchesProfiles("modern")) {
            return false;
        }
        String className = metadataReader.getClassMetadata().getClassName();
        return !className.startsWith("com.model_store.modern.");
    }
}
