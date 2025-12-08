package com.model_store.scheduler;

import com.model_store.service.ImageService;
import com.model_store.service.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

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
        log.info("Запуск очистки временных файлов из MinIO");

        Mono<Void> task = imageService.findTemporaryImages()
                .flatMap(image -> {
                    // Обработка каждого изображения асинхронно
                    return imageService.deleteById(image.getId()) // Удаляем запись из базы
                            .then(s3Service.deleteFile(image.getTag(), image.getFilename())) // Удаляем файл из MinIO
                            .doOnSuccess(aVoid -> log.info("Удалён файл из MinIO: {}", image.getId())) // Логируем успешное удаление
                            .doOnError(e -> log.error("Ошибка при удалении файла {}: {}", image.getId(), e.getMessage())) // Логируем ошибку
                            .onErrorResume(e -> { // Обрабатываем ошибку удаления файла
                                log.error("Ошибка при обработке файла {}: {}", image.getId(), e.getMessage());
                                return Mono.empty(); // Возвращаем пустое Mono, чтобы продолжить обработку других изображений
                            });
                })
                .doOnTerminate(() -> log.info("Очистка временных файлов завершена")) // Логируем завершение работы
                .doOnError(e -> log.error("Ошибка при очистке временных файлов: {}", e.getMessage())) // Логируем ошибку на уровне всей операции
                .then();

        transactionalOperator.transactional(task).subscribe();
    }

}