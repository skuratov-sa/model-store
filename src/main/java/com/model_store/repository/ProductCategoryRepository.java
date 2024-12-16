package com.model_store.repository;


import com.model_store.model.base.ProductCategory;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface ProductCategoryRepository extends ReactiveCrudRepository<ProductCategory, Long> {
    Mono<Void> deleteAllByProductId(Long productId);
}
