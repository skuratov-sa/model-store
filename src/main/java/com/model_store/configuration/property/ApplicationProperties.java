package com.model_store.configuration.property;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@Slf4j
@ConfigurationProperties("app")
public class ApplicationProperties {
    private Integer maxParticipantImages;
    private Integer maxProductImages;
    private Integer productExpirationDays;
    private String privateKeyPath;
    private String publicKeyPath;
    private String emailFrom;
}
