package com.model_store.configuration;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.CreateBucketRequest;
import com.model_store.configuration.property.S3ConfigurationProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class S3Config {

    private final S3ConfigurationProperties properties;

    @Bean
    public AmazonS3 amazonS3() {
        BasicAWSCredentials credentials = new BasicAWSCredentials(properties.getAccessKey(), properties.getSecretKey());

        return AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(properties.getEndpoint(), properties.getRegion()))
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .withPathStyleAccessEnabled(true)
                .build();
    }

    @PostConstruct
    public void createBuckets() {
        AmazonS3 s3Client = amazonS3();
        String[] bucketNames = {properties.getParticipantBucketName(), properties.getProductBucketName(), properties.getOrderBucketName(), properties.getSystemBucketName()};

        for (String bucketName : bucketNames) {
            if (!s3Client.doesBucketExistV2(bucketName)) {
                s3Client.createBucket(new CreateBucketRequest(bucketName));
                System.out.println("Создан бакет: " + bucketName);
            }
        }
    }
}
