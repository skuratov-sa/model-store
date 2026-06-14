package com.model_store.scheduler;

import com.model_store.model.base.Image;
import com.model_store.service.ImageService;
import com.model_store.service.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageCleanupScheduler {

    private final ImageService imageService;
    private final S3Service s3Service;
    private final TransactionalOperator transactionalOperator;

    // Scheduler запускается каждый день в 00:00
    @Scheduled(cron = "0 0 0 * * *")
    public void cleanUpTemporaryImages() {
        log.info("Запуск очистки временных файлов");

        imageService.findTemporaryImages()
                .collectList()
                .filter(images -> !images.isEmpty())
                .flatMap(images -> {
                    List<Long> ids = images.stream().map(Image::getId).toList();

                    Mono<Void> dbTask = imageService.deleteAllByIds(ids)
                            .doOnSuccess(v -> log.info("Удалено {} записей из БД", images.size()));

                    return transactionalOperator.transactional(dbTask)
                            .thenMany(Flux.fromIterable(images))
                            .concatMap(image ->
                                    s3Service.deleteFile(image.getTag(), image.getFilename())
                                            .doOnSuccess(v -> log.info("Удалён из MinIO: {}", image.getId()))
                                            .onErrorResume(e -> {
                                                log.error("Ошибка MinIO {}: {}", image.getId(), e.getMessage());
                                                return Mono.empty();
                                            })
                            )
                            .then();
                })
                .doOnSuccess(v -> log.info("Очистка завершена"))
                .subscribe(null, e -> log.error("Критическая ошибка в cleanup: {}", e.getMessage()));
    }

}
