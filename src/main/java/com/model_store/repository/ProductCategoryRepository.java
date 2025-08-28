package com.model_store.repository;

import com.model_store.model.base.ProductCategory;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface ProductCategoryRepository extends ReactiveCrudRepository<ProductCategory, Long> {
    Flux<ProductCategory> findByProductId(Long productId);
}