package com.model_store.scheduler;

import com.model_store.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductUpdateScheduler {

    private final ProductRepository productRepository;

    @Scheduled(cron = "0 */5 * * * *")
    public void expireProducts() {
        productRepository.expireDueOrdinaryProducts()
                .doOnNext(count -> log.info("Истекли товары: {}", count))
                .doOnError(error -> log.error("Ошибка при завершении товаров", error))
                .subscribe();
    }

}
