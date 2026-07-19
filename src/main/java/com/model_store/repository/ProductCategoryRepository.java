package com.model_store.repository;

import com.model_store.model.base.ProductCategory;
import com.model_store.model.projection.ProductCategoryView;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface ProductCategoryRepository extends ReactiveCrudRepository<ProductCategory, Long> {
    Flux<ProductCategory> findByProductId(Long productId);

    @Query("""
            SELECT pc.product_id, c.id AS category_id, c.name AS category_name
            FROM product_category pc
                JOIN category c ON c.id = pc.category_id
            WHERE pc.product_id = ANY(:productIds)
            ORDER BY pc.product_id, c.id
            """)
    Flux<ProductCategoryView> findCategoryViewsByProductIds(Long[] productIds);
}
