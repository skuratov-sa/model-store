package com.model_store.configuration.property;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@Slf4j
@ConfigurationProperties("s3")
public class S3ConfigurationProperties {
    private String accessKey;
    private String secretKey;
    private String endpoint;
    private String participantBucketName;
    private String productBucketName;
    private String systemBucketName;
    private String orderBucketName;
    private String errorBucketName;
    private String region;

    @PostConstruct
    public void init() {
        log.info("S3 INIT: accessKey = {} endpoint = {},region = {}, participantBucketName = {}, productBucketName = {}, systemBucketName = {}, orderBucketName = {}",
                accessKey, endpoint, region, participantBucketName, productBucketName, systemBucketName, orderBucketName);
    }
}
