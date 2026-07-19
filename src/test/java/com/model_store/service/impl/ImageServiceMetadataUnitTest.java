package com.model_store.service.impl;

import com.model_store.configuration.property.ApplicationProperties;
import com.model_store.configuration.property.S3ConfigurationProperties;
import com.model_store.mapper.ImageMapper;
import com.model_store.model.base.Image;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.repository.ImageRepository;
import com.model_store.repository.OrderRepository;
import com.model_store.repository.ParticipantRepository;
import com.model_store.repository.ProductRepository;
import com.model_store.service.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageServiceMetadataUnitTest {

    @Mock ImageRepository imageRepository;
    @Mock S3Service s3Service;
    @Mock ImageMapper imageMapper;
    @Mock ParticipantRepository participantRepository;
    @Mock ProductRepository productRepository;
    @Mock OrderRepository orderRepository;
    @Mock ApplicationProperties applicationProperties;
    @Mock S3ConfigurationProperties s3Properties;

    ImageServiceImpl imageService;

    @BeforeEach
    void setUp() {
        imageService = new ImageServiceImpl(
                imageRepository, s3Service, imageMapper,
                participantRepository, productRepository, orderRepository,
                applicationProperties, s3Properties
        );
        when(applicationProperties.getCdnBaseUrl()).thenReturn("https://cdn.test.ru");
        when(s3Properties.getProductBucketName()).thenReturn("product-bucket");
        when(s3Properties.getParticipantBucketName()).thenReturn("participant-bucket");
        when(s3Properties.getOrderBucketName()).thenReturn("order-bucket");
        when(s3Properties.getSystemBucketName()).thenReturn("system-bucket");
    }

    @Test
    void findImageMetadataByIds_activeProductImage_returnsThreeUrls() {
        Image image = Image.builder()
                .id(1L)
                .filename("abc123_photo.jpg")
                .tag(ImageTag.PRODUCT)
                .status(ImageStatus.ACTIVE)
                .contentType("image/jpeg")
                .width(1200)
                .height(800)
                .build();

        when(imageRepository.findActiveByIds(any())).thenReturn(Flux.just(image));

        StepVerifier.create(imageService.findImageMetadataByIds(List.of(1L)))
                .assertNext(dto -> {
                    assertThat(dto.id()).isEqualTo(1L);
                    assertThat(dto.originalUrl()).isEqualTo("https://cdn.test.ru/product-bucket/original/abc123_photo.jpg");
                    assertThat(dto.mediumUrl()).isEqualTo("https://cdn.test.ru/product-bucket/medium/abc123_photo.jpg");
                    assertThat(dto.thumbnailUrl()).isEqualTo("https://cdn.test.ru/product-bucket/thumbnail/abc123_photo.jpg");
                    assertThat(dto.contentType()).isEqualTo("image/jpeg");
                    assertThat(dto.width()).isEqualTo(1200);
                    assertThat(dto.height()).isEqualTo(800);
                })
                .verifyComplete();
    }

    @Test
    void findImageMetadataByIds_imageNotFound_returnsEmptyFlux() {
        when(imageRepository.findActiveByIds(any())).thenReturn(Flux.empty());

        StepVerifier.create(imageService.findImageMetadataByIds(List.of(999L)))
                .verifyComplete();
    }

    @Test
    void findImageMetadataByIds_temporaryImage_notReturned() {
        Image image = Image.builder()
                .id(2L)
                .filename("temp_photo.jpg")
                .tag(ImageTag.PRODUCT)
                .status(ImageStatus.TEMPORARY)
                .build();

        when(imageRepository.findActiveByIds(any())).thenReturn(Flux.empty());

        StepVerifier.create(imageService.findImageMetadataByIds(List.of(2L)))
                .verifyComplete();
    }

    @Test
    void findImageMetadataByIds_deletedImage_notReturned() {
        Image image = Image.builder()
                .id(3L)
                .filename("deleted_photo.jpg")
                .tag(ImageTag.PRODUCT)
                .status(ImageStatus.DELETE)
                .build();

        when(imageRepository.findActiveByIds(any())).thenReturn(Flux.empty());

        StepVerifier.create(imageService.findImageMetadataByIds(List.of(3L)))
                .verifyComplete();
    }

    @Test
    void findImageMetadataByIds_doesNotCallS3() {
        when(imageRepository.findActiveByIds(any())).thenReturn(Flux.empty());

        StepVerifier.create(imageService.findImageMetadataByIds(List.of(1L)))
                .verifyComplete();

        verify(s3Service, never()).getFile(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void findImageMetadataByIds_participantImage_usesParticipantBucket() {
        Image image = Image.builder()
                .id(5L)
                .filename("avatar.png")
                .tag(ImageTag.PARTICIPANT)
                .status(ImageStatus.ACTIVE)
                .build();

        when(imageRepository.findActiveByIds(any())).thenReturn(Flux.just(image));

        StepVerifier.create(imageService.findImageMetadataByIds(List.of(5L)))
                .assertNext(dto -> {
                    assertThat(dto.originalUrl()).isEqualTo("https://cdn.test.ru/participant-bucket/original/avatar.png");
                    assertThat(dto.mediumUrl()).isEqualTo("https://cdn.test.ru/participant-bucket/medium/avatar.png");
                    assertThat(dto.thumbnailUrl()).isEqualTo("https://cdn.test.ru/participant-bucket/thumbnail/avatar.png");
                })
                .verifyComplete();
    }

    @Test
    void findImageMetadataByIds_orderImage_notReturned() {
        Image image = Image.builder()
                .id(6L)
                .filename("receipt.jpg")
                .tag(ImageTag.ORDER)
                .status(ImageStatus.ACTIVE)
                .build();

        when(imageRepository.findActiveByIds(any())).thenReturn(Flux.just(image));

        StepVerifier.create(imageService.findImageMetadataByIds(List.of(6L)))
                .verifyComplete();
    }
}