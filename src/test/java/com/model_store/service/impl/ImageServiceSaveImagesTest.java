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
import com.model_store.service.S3Service.UploadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageServiceSaveImagesTest {

    @Mock ImageRepository imageRepository;
    @Mock S3Service s3Service;
    @Mock ImageMapper imageMapper;
    @Mock ParticipantRepository participantRepository;
    @Mock ProductRepository productRepository;
    @Mock OrderRepository orderRepository;
    @Mock ApplicationProperties applicationProperties;
    @Mock S3ConfigurationProperties s3Properties;
    @Mock FilePart filePart;

    ImageServiceImpl imageService;

    @BeforeEach
    void setUp() {
        imageService = new ImageServiceImpl(
                imageRepository, s3Service, imageMapper,
                participantRepository, productRepository, orderRepository,
                applicationProperties, s3Properties
        );
    }

    @Test
    void saveImages_oneFile_createsOneDbRecord() {
        stubS3Upload();
        stubMapper();
        stubRepositorySave();

        StepVerifier.create(imageService.saveImages(ImageTag.PRODUCT, 42L, List.of(filePart)))
                .expectNextCount(1)
                .verifyComplete();

        verify(imageRepository, times(1)).save(any(Image.class));
    }

    @Test
    void saveImages_twoFiles_createsTwoDbRecords() {
        stubS3Upload();
        stubMapper();
        stubRepositorySave();

        StepVerifier.create(imageService.saveImages(ImageTag.PRODUCT, 42L, List.of(filePart, filePart)))
                .expectNextCount(2)
                .verifyComplete();

        verify(imageRepository, times(2)).save(any(Image.class));
    }

    @Test
    void saveImages_productTag_statusIsTEMPORARY() {
        stubS3Upload();
        stubMapper();
        stubRepositorySave();

        ArgumentCaptor<Image> captor = ArgumentCaptor.forClass(Image.class);

        imageService.saveImages(ImageTag.PRODUCT, 42L, List.of(filePart)).blockLast();

        verify(imageRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ImageStatus.TEMPORARY);
    }

    @Test
    void saveImages_participantTag_statusIsACTIVE() {
        stubS3Upload();
        stubMapper();
        stubRepositorySave();

        ArgumentCaptor<Image> captor = ArgumentCaptor.forClass(Image.class);

        imageService.saveImages(ImageTag.PARTICIPANT, 7L, List.of(filePart)).blockLast();

        verify(imageRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ImageStatus.ACTIVE);
    }

    @Test
    void saveImages_dimensionsAndContentTypePersisted() {
        stubS3Upload();
        stubMapper();
        stubRepositorySave();

        ArgumentCaptor<Image> captor = ArgumentCaptor.forClass(Image.class);

        imageService.saveImages(ImageTag.PRODUCT, 42L, List.of(filePart)).blockLast();

        verify(imageRepository, times(1)).save(captor.capture());
        Image saved = captor.getValue();
        assertThat(saved.getContentType()).isEqualTo("image/jpeg");
        assertThat(saved.getWidth()).isEqualTo(1200);
        assertThat(saved.getHeight()).isEqualTo(800);
    }

    private void stubS3Upload() {
        when(s3Service.uploadFile(any(FilePart.class), any(ImageTag.class)))
                .thenReturn(Mono.just(new UploadResult("uuid_photo.jpg", 1200, 800, "image/jpeg")));
    }

    private void stubMapper() {
        when(imageMapper.toImage(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> Image.builder()
                        .filename(invocation.getArgument(2))
                        .tag(invocation.getArgument(1))
                        .status(invocation.getArgument(3))
                        .contentType(invocation.getArgument(4))
                        .width(invocation.getArgument(5))
                        .height(invocation.getArgument(6))
                        .build());
    }

    private void stubRepositorySave() {
        when(imageRepository.save(any(Image.class))).thenAnswer(invocation -> {
            Image img = invocation.getArgument(0);
            img.setId((long) (Math.random() * 1000 + 1));
            return Mono.just(img);
        });
    }
}
