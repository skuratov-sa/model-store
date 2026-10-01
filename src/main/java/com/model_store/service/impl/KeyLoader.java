package com.model_store.service.impl;

import lombok.experimental.UtilityClass;
import org.springframework.core.io.ClassPathResource;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@UtilityClass
public class KeyLoader {

    public String loadKey(String keyPath) throws IOException {
        try (InputStream inputStream = getInputStream(keyPath)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private InputStream getInputStream(String keyPath) throws IOException {
        Path filePath = Path.of(keyPath);
        if (Files.isRegularFile(filePath)) {
            return Files.newInputStream(filePath);
        }
        if (filePath.isAbsolute()) {
            throw new FileNotFoundException("Key file does not exist: " + keyPath);
        }
        ClassPathResource classPathResource = new ClassPathResource(keyPath);
        if (classPathResource.exists()) {
            return classPathResource.getInputStream();
        }
        Path localResourcePath = Path.of("src", "main", "resources").resolve(filePath);
        if (Files.isRegularFile(localResourcePath)) {
            return Files.newInputStream(localResourcePath);
        }
        throw new FileNotFoundException("Key file does not exist: " + keyPath);
    }
}
