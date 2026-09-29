package com.model_store.service.impl;

import lombok.experimental.UtilityClass;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

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
        // Попробуем сначала загрузить как файл из файловой системы
        Path filePath = Path.of(keyPath);
        if (Files.exists(filePath)) {
            return new FileSystemResource(keyPath).getInputStream();
        }
        if (filePath.isAbsolute()) {
            throw new FileNotFoundException("Key file does not exist: " + keyPath);
        }
        return new ClassPathResource(keyPath).getInputStream();
    }
}
