package com.model_store.service.impl;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.model_store.configuration.property.S3ConfigurationProperties;
import com.model_store.mapper.ImageMapper;
import com.model_store.model.constant.ImageTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3ServiceImplTest {

    @Mock AmazonS3 amazonS3;
    @Mock ImageMapper imageMapper;
    @Mock S3ConfigurationProperties s3Properties;
    @Mock FilePart filePart;

    S3ServiceImpl s3Service;
    private final List<UploadedImage> uploads = new ArrayList<>();

    @BeforeEach
    void setUp() {
        s3Service = new S3ServiceImpl(amazonS3, imageMapper, s3Properties);
        when(s3Properties.getProductBucketName()).thenReturn("product-bucket");
        when(filePart.filename()).thenReturn("photo.jpg");

        // Files are removed after putObject returns; inspect their bytes during the call.
        when(amazonS3.putObject(any(PutObjectRequest.class))).thenAnswer(invocation -> {
            PutObjectRequest request = invocation.getArgument(0);
            BufferedImage image = ImageIO.read(request.getFile());
            assertThat(image).isNotNull();
            uploads.add(new UploadedImage(request.getBucketName(), request.getKey(),
                    image.getWidth(), image.getHeight()));
            return null;
        });
    }

    @Test
    void uploadFile_returnsMetadataAndUploadsThreeSizedVariantsWithSameBasename() {
        stubImage(1800, 1200);
        StepVerifier.create(s3Service.uploadFile(filePart, ImageTag.PRODUCT))
                .assertNext(result -> {
                    assertThat(result.width()).isEqualTo(1800);
                    assertThat(result.height()).isEqualTo(1200);
                    assertThat(result.contentType()).isEqualTo("image/jpeg");
                    assertThat(uploads).containsExactly(
                            new UploadedImage("product-bucket", "original/" + result.filename(), 1800, 1200),
                            new UploadedImage("product-bucket", "medium/" + result.filename(), 1200, 800),
                            new UploadedImage("product-bucket", "thumbnail/" + result.filename(), 400, 267));
                })
                .verifyComplete();
        verify(amazonS3, times(3)).putObject(any(PutObjectRequest.class));
    }

    @Test
    void uploadFile_smallImage_doesNotUpscaleAnyUploadedVariant() {
        stubImage(100, 66);
        StepVerifier.create(s3Service.uploadFile(filePart, ImageTag.PRODUCT))
                .assertNext(result -> {
                    assertThat(result.width()).isEqualTo(100);
                    assertThat(result.height()).isEqualTo(66);
                    assertThat(uploads).containsExactly(
                            new UploadedImage("product-bucket", "original/" + result.filename(), 100, 66),
                            new UploadedImage("product-bucket", "medium/" + result.filename(), 100, 66),
                            new UploadedImage("product-bucket", "thumbnail/" + result.filename(), 100, 66));
                })
                .verifyComplete();
    }

    private void stubImage(int width, int height) {
        when(filePart.transferTo(any(File.class))).thenAnswer(invocation -> {
            File dest = invocation.getArgument(0);
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg", dest);
            return Mono.empty();
        });
    }

    private record UploadedImage(String bucket, String key, int width, int height) {}
}
