package com.model_store.scheduler;

import com.model_store.model.constant.ProductStatus;
import com.model_store.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductUpdateScheduler {

    private final ProductService productService;
    private final TransactionalOperator transactionalOperator;

    // Scheduler запускается каждый день в 01:00
    @Scheduled(cron = "0 0 1 * * *")
    public void expireProducts() {
        log.info("Запуск сброса статуса для товаров с истекшим expiration_date");

        Mono<Void> task = productService.findExpiredActiveProductIds()
                .flatMap(productId ->
                        productService.updateProductStatus(productId, ProductStatus.TIME_EXPIRED)
                                .doOnSuccess(aVoid -> log.info("Истекло время товара: {}", productId))
                                .doOnError(e -> log.error("Ошибка при сбросе актуальности товара {}: {}", productId, e.getMessage())),
                        10
                ).doOnTerminate(() -> log.info("Товары с истекшим временем были переведены в статус TIME_EXPIRED"))
                .doOnError(e -> log.error("Ошибка при сбросе статусов товара: {}", e.getMessage()))
                .then();
        transactionalOperator.transactional(task).subscribe();
    }
}