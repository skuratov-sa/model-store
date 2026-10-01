package com.model_store.scheduler;

import com.model_store.model.base.Image;
import com.model_store.service.ImageService;
import com.model_store.service.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageCleanupScheduler {

    private final ImageService imageService;
    private final S3Service s3Service;

    @Scheduled(cron = "0 0 0 * * *")
    public void cleanUpTemporaryImages() {
        log.info("Запуск очистки временных файлов");
        cleanup()
                .doOnSuccess(v -> log.info("Очистка завершена"))
                .subscribe(null, e -> log.error("Критическая ошибка в cleanup: {}", e.getMessage()));
    }

    Mono<Void> cleanup() {
        return imageService.prepareExpiredImagesForDeletion().collectList()
                .filter(images -> !images.isEmpty())
                .flatMapMany(Flux::fromIterable)
                .concatMap(this::deleteImage)
                .then();
    }

    private Mono<Void> deleteImage(Image image) {
        return s3Service.deleteFile(image.getTag(), image.getFilename())
                .then(Mono.defer(() -> imageService.deleteMarkedImage(image.getId())))
                .doOnSuccess(v -> log.info("Удалено изображение {} из MinIO и БД", image.getId()))
                .onErrorResume(e -> {
                    log.error("Ошибка очистки изображения {}: {}", image.getId(), e.getMessage(), e);
                    return Mono.empty();
                });
    }
}
