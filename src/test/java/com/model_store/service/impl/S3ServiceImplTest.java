package com.model_store.service.impl;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.model_store.configuration.property.S3ConfigurationProperties;
import com.model_store.mapper.ImageMapper;
import com.model_store.model.constant.ImageTag;
import com.model_store.service.S3Service.UploadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class S3ServiceImplTest {

    @Mock AmazonS3 amazonS3;
    @Mock ImageMapper imageMapper;
    @Mock S3ConfigurationProperties s3Properties;
    @Mock FilePart filePart;

    S3ServiceImpl s3Service;

    @BeforeEach
    void setUp() {
        s3Service = new S3ServiceImpl(amazonS3, imageMapper, s3Properties);
        when(s3Properties.getProductBucketName()).thenReturn("product-bucket");
        when(filePart.filename()).thenReturn("photo.jpg");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_JPEG);
        when(filePart.headers()).thenReturn(headers);

        when(filePart.transferTo(any(File.class))).thenAnswer(invocation -> {
            File dest = invocation.getArgument(0);
            BufferedImage img = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
            ImageIO.write(img, "jpg", dest);
            return Mono.empty();
        });
    }

    @Test
    void uploadFile_returnsOneResult() {
        StepVerifier.create(s3Service.uploadFile(filePart, ImageTag.PRODUCT))
                .assertNext(result -> assertThat(result).isNotNull())
                .verifyComplete();
    }

    @Test
    void uploadFile_originalDimensions() {
        StepVerifier.create(s3Service.uploadFile(filePart, ImageTag.PRODUCT))
                .assertNext(result -> {
                    assertThat(result.width()).isEqualTo(1200);
                    assertThat(result.height()).isEqualTo(800);
                })
                .verifyComplete();
    }

    @Test
    void uploadFile_contentTypePreserved() {
        StepVerifier.create(s3Service.uploadFile(filePart, ImageTag.PRODUCT))
                .assertNext(result -> assertThat(result.contentType()).isEqualTo("image/jpeg"))
                .verifyComplete();
    }

    @Test
    void uploadFile_uploadsThreeVariantsToS3() {
        s3Service.uploadFile(filePart, ImageTag.PRODUCT).block();

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(amazonS3, atLeast(3)).putObject(captor.capture());

        List<PutObjectRequest> requests = captor.getAllValues();
        assertThat(requests).allMatch(r -> r.getBucketName().equals("product-bucket"));

        List<String> keys = requests.stream().map(PutObjectRequest::getKey).toList();
        assertThat(keys).anyMatch(k -> k.startsWith("original/"));
        assertThat(keys).anyMatch(k -> k.startsWith("medium/"));
        assertThat(keys).anyMatch(k -> k.startsWith("thumbnail/"));
    }

    @Test
    void uploadFile_allVariantsSameBasename() {
        s3Service.uploadFile(filePart, ImageTag.PRODUCT).block();

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(amazonS3, atLeast(3)).putObject(captor.capture());

        List<String> basenames = captor.getAllValues().stream()
                .map(r -> r.getKey().substring(r.getKey().indexOf('/') + 1))
                .distinct()
                .toList();
        assertThat(basenames).hasSize(1);
    }

    @Test
    void uploadFile_smallImage_notUpscaled() {
        when(filePart.transferTo(any(File.class))).thenAnswer(invocation -> {
            File dest = invocation.getArgument(0);
            BufferedImage img = new BufferedImage(100, 66, BufferedImage.TYPE_INT_RGB);
            ImageIO.write(img, "jpg", dest);
            return Mono.empty();
        });

        StepVerifier.create(s3Service.uploadFile(filePart, ImageTag.PRODUCT))
                .assertNext(result -> {
                    assertThat(result.width()).isEqualTo(100);
                    assertThat(result.height()).isEqualTo(66);
                })
                .verifyComplete();
    }
}
