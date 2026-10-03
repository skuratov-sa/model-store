package com.model_store.modern.shared.config

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration(proxyBeanMethods = false)
@Profile("modern")
class ModernS3Configuration {
    @Bean
    fun amazonS3(
        @Value("\${s3.access-key}") accessKey: String,
        @Value("\${s3.secret-key}") secretKey: String,
        @Value("\${s3.endpoint}") endpoint: String,
        @Value("\${s3.region}") region: String,
    ): AmazonS3 = AmazonS3ClientBuilder.standard()
        .withEndpointConfiguration(AwsClientBuilder.EndpointConfiguration(endpoint, region))
        .withCredentials(AWSStaticCredentialsProvider(BasicAWSCredentials(accessKey, secretKey)))
        .withPathStyleAccessEnabled(true)
        .build()

    @Bean
    fun verifyS3Connection(s3: AmazonS3) = ApplicationRunner {
        s3.listBuckets()
    }
}
