package com.model_store.controller;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.model_store.configuration.property.ApplicationProperties;
import com.model_store.model.base.Image;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.repository.ImageRepository;
import com.model_store.service.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.ByteArrayInputStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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

    @Autowired
    private AmazonS3 amazonS3;

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
    void getLegacyImages_activeProductImage_returnsContent() {
        Image saved = imageRepository.save(Image.builder().filename("legacy.jpg")
                .tag(ImageTag.PRODUCT).status(ImageStatus.ACTIVE).build()).block();
        S3Object object = new S3Object();
        object.getObjectMetadata().setContentType("image/jpeg");
        object.setObjectContent(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(amazonS3.getObject(any(GetObjectRequest.class))).thenReturn(object);

        webTestClient.get()
                .uri("/images?ids=" + saved.getId())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].fileName").isEqualTo("original/legacy.jpg")
                .jsonPath("$[0].contentType").isEqualTo("image/jpeg")
                .jsonPath("$[0].imageData").isEqualTo("AQID");
    }

    @Test
    void anonymousCannotReadOrderImage() {
        Image saved = imageRepository.save(Image.builder()
                .filename("payment-proof.jpg")
                .tag(ImageTag.ORDER)
                .status(ImageStatus.ACTIVE)
                .entityId(999999L)
                .build()).block();

        webTestClient.get()
                .uri("/images?ids=" + saved.getId())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").isArray()
                .jsonPath("$.length()").isEqualTo(0);
    }
}
