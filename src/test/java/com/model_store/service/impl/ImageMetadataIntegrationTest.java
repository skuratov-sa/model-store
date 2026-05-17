package com.model_store.service.impl;

import com.model_store.configuration.property.ApplicationProperties;
import com.model_store.model.base.Image;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.repository.ImageRepository;
import com.model_store.service.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class ImageMetadataIntegrationTest extends IntegrationTest {

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
    void findImageMetadataByIds_activeImage_returnsThreeUrls() {
        Image saved = imageRepository.save(
                Image.builder()
                        .filename("e3b0c4_photo.jpg")
                        .tag(ImageTag.PRODUCT)
                        .status(ImageStatus.ACTIVE)
                        .contentType("image/jpeg")
                        .width(1200)
                        .height(800)
                        .build()
        ).block();

        StepVerifier.create(imageService.findImageMetadataByIds(java.util.List.of(saved.getId())))
                .assertNext(dto -> {
                    assertThat(dto.id()).isEqualTo(saved.getId());
                    assertThat(dto.originalUrl()).startsWith(applicationProperties.getCdnBaseUrl());
                    assertThat(dto.originalUrl()).contains("original/e3b0c4_photo.jpg");
                    assertThat(dto.mediumUrl()).contains("medium/e3b0c4_photo.jpg");
                    assertThat(dto.thumbnailUrl()).contains("thumbnail/e3b0c4_photo.jpg");
                    assertThat(dto.contentType()).isEqualTo("image/jpeg");
                    assertThat(dto.width()).isEqualTo(1200);
                    assertThat(dto.height()).isEqualTo(800);
                })
                .verifyComplete();
    }

    @Test
    void findImageMetadataByIds_nonExistentId_returnsEmptyFlux() {
        StepVerifier.create(imageService.findImageMetadataByIds(java.util.List.of(999999L)))
                .verifyComplete();
    }

    @Test
    void findImageMetadataByIds_temporaryImage_notReturned() {
        Image saved = imageRepository.save(
                Image.builder()
                        .filename("temp_photo.jpg")
                        .tag(ImageTag.PRODUCT)
                        .status(ImageStatus.TEMPORARY)
                        .build()
        ).block();

        StepVerifier.create(imageService.findImageMetadataByIds(java.util.List.of(saved.getId())))
                .verifyComplete();
    }

    @Test
    void findImageMetadataByIds_multipleActiveImages_returnsAll() {
        Image first = imageRepository.save(
                Image.builder().filename("first.jpg").tag(ImageTag.PRODUCT).status(ImageStatus.ACTIVE).build()
        ).block();
        Image second = imageRepository.save(
                Image.builder().filename("second.jpg").tag(ImageTag.PRODUCT).status(ImageStatus.ACTIVE).build()
        ).block();

        StepVerifier.create(
                imageService.findImageMetadataByIds(java.util.List.of(first.getId(), second.getId()))
                        .collectList()
        )
                .assertNext(list -> assertThat(list).hasSize(2))
                .verifyComplete();
    }
}
