package com.model_store.scheduler;

import com.model_store.model.base.Image;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.service.ImageService;
import com.model_store.service.S3Service;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.*;

class ImageCleanupSchedulerTest {

    private final ImageService imageService = mock(ImageService.class);
    private final S3Service s3Service = mock(S3Service.class);
    private final ImageCleanupScheduler scheduler = new ImageCleanupScheduler(imageService, s3Service);

    @Test
    void deletesFileBeforeDatabaseRow() {
        Image image = image(1L);
        when(imageService.findTemporaryImages()).thenReturn(Flux.just(image));
        when(s3Service.deleteFile(ImageTag.PRODUCT, "photo.jpg")).thenReturn(Mono.empty());
        when(imageService.deleteById(1L)).thenReturn(Mono.empty());

        StepVerifier.create(scheduler.cleanup()).verifyComplete();

        var ordered = inOrder(s3Service, imageService);
        ordered.verify(s3Service).deleteFile(ImageTag.PRODUCT, "photo.jpg");
        ordered.verify(imageService).deleteById(1L);
    }

    @Test
    void minioFailureKeepsRowAndContinuesWithNextImage() {
        Image failed = image(1L);
        Image next = image(2L);
        when(imageService.findTemporaryImages()).thenReturn(Flux.just(failed, next));
        when(s3Service.deleteFile(ImageTag.PRODUCT, "photo.jpg"))
                .thenReturn(Mono.error(new IllegalStateException("MinIO unavailable")), Mono.empty());
        when(imageService.deleteById(2L)).thenReturn(Mono.empty());

        StepVerifier.create(scheduler.cleanup()).verifyComplete();

        verify(imageService, never()).deleteById(1L);
        verify(imageService).deleteById(2L);
    }

    private Image image(Long id) {
        return Image.builder()
                .id(id)
                .tag(ImageTag.PRODUCT)
                .status(ImageStatus.DELETE)
                .filename("photo.jpg")
                .build();
    }
}
