package com.model_store.repository;

import com.model_store.model.base.Product;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductRepository extends ReactiveCrudRepository<Product, Long> {
//    Flux<Product> findByParams(FindProductRequest searchParams);
}