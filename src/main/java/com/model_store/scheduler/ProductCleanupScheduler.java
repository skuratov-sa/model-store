package com.model_store.scheduler;

import com.model_store.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductCleanupScheduler {

    private static final int BATCH_SIZE = 200;

    private final ProductRepository productRepository;
    private final TransactionalOperator transactionalOperator;

    @Scheduled(cron = "0 0 2 * * *")
    public void cleanUpDeletedProducts() {
        log.info("Запуск очистки удалённых товаров");
        cleanup().subscribe(null, e -> log.error("Ошибка очистки удалённых товаров", e));
    }

    Mono<Void> cleanup() {
        return Mono.defer(() -> transactionalOperator.transactional(deleteBatch()))
                .repeat()
                .takeUntil(selected -> selected < BATCH_SIZE)
                .then();
    }

    private Mono<Integer> deleteBatch() {
        return productRepository.findDeletedWithoutImages(BATCH_SIZE)
                .collectList()
                .flatMap(this::deleteProducts);
    }

    private Mono<Integer> deleteProducts(List<Long> ids) {
        if (ids.isEmpty()) return Mono.just(0);
        Long[] productIds = ids.toArray(Long[]::new);
        return productRepository.deleteFavoritesByProductIds(productIds)
                .then(Mono.defer(() -> productRepository.deleteBasketByProductIds(productIds)))
                .then(Mono.defer(() -> productRepository.deleteCartByProductIds(productIds)))
                .then(Mono.defer(() -> productRepository.deleteDeletedWithoutImages(productIds)))
                .doOnNext(deleted -> log.info("Удалено {} товаров из БД", deleted))
                .thenReturn(ids.size());
    }
}
