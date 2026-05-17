package com.model_store.controller;

import com.model_store.configuration.property.ApplicationProperties;
import com.model_store.model.base.Image;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.repository.ImageRepository;
import com.model_store.service.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;

@AutoConfigureWebTestClient
class ImageControllerWebTest extends IntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ImageRepository imageRepository;

    @Autowired
    private ApplicationProperties applicationProperties;

    @Autowired
    private DatabaseClient databaseClient;

    @BeforeEach
    void cleanUp() {
        databaseClient.sql("TRUNCATE TABLE image RESTART IDENTITY CASCADE")
                .fetch().rowsUpdated().block();
    }

    @Test
    void getMetadata_nonExistentId_returns200WithEmptyArray() {
        webTestClient.get()
                .uri("/images/metadata?ids=999999")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").isArray()
                .jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void getMetadata_activeImage_returns200WithUrl() {
        Image saved = imageRepository.save(
                Image.builder()
                        .filename("test_photo.jpg")
                        .tag(ImageTag.PRODUCT)
                        .status(ImageStatus.ACTIVE)
                        .contentType("image/jpeg")
                        .build()
        ).block();

        webTestClient.get()
                .uri("/images/metadata?ids=" + saved.getId())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo(saved.getId())
                .jsonPath("$[0].originalUrl").value(url ->
                        org.assertj.core.api.Assertions.assertThat((String) url)
                                .startsWith(applicationProperties.getCdnBaseUrl())
                                .contains("original/test_photo.jpg")
                )
                .jsonPath("$[0].mediumUrl").value(url ->
                        org.assertj.core.api.Assertions.assertThat((String) url)
                                .contains("medium/test_photo.jpg")
                )
                .jsonPath("$[0].thumbnailUrl").value(url ->
                        org.assertj.core.api.Assertions.assertThat((String) url)
                                .contains("thumbnail/test_photo.jpg")
                );
    }

    @Test
    void getMetadata_temporaryImage_returns200WithEmptyArray() {
        Image saved = imageRepository.save(
                Image.builder()
                        .filename("temp_photo.jpg")
                        .tag(ImageTag.PRODUCT)
                        .status(ImageStatus.TEMPORARY)
                        .build()
        ).block();

        webTestClient.get()
                .uri("/images/metadata?ids=" + saved.getId())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").isArray()
                .jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void getLegacyImages_stillWorks_returns200() {
        webTestClient.get()
                .uri("/images?ids=999999")
                .exchange()
                .expectStatus().isOk();
    }
}
