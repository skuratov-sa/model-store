package com.model_store.scheduler;

import com.model_store.repository.ProductRepository;
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

    private final ProductRepository productRepository;
    private final TransactionalOperator transactionalOperator;

    @Scheduled(cron = "0 */5 * * * *")
    public void expireProducts() {
        Mono<Void> task = productRepository.expireDueOrdinaryProducts()
                .flatMap(ordinaryCount -> productRepository.startDueGiveaways()
                        .flatMap(startedCount -> productRepository.expireDueGiveaways()
                                .doOnNext(expiredCount -> log.info(
                                        "Истекли товары: {}, начались розыгрыши: {}, завершились розыгрыши: {}",
                                        ordinaryCount, startedCount, expiredCount))))
                .doOnError(error -> log.error("Ошибка при завершении товаров и розыгрышей", error))
                .then();
        transactionalOperator.transactional(task).subscribe();
    }
}
