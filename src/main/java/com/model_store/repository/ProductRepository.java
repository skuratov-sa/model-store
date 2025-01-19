package com.model_store.repository;

import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
import com.model_store.model.util.DateRange;
import com.model_store.model.util.PriceRange;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends ReactiveCrudRepository<Product, Long> {

    @Query("""
            SELECT *
            FROM product p
            WHERE
                (:productIds IS NULL OR p.id IN (:productIds)) AND
                (:productName IS NULL OR p.name = :productName) AND
                (:minPrice IS NULL OR p.price >= :minPrice) AND
                (:maxPrice IS NULL OR p.price <= :maxPrice) AND
                (:originality IS NULL OR p.originality = :originality) AND
                (:dateTimeFrom IS NULL OR p.created_at >= :dateTimeFrom) AND
                (:dateTimeTo IS NULL OR p.created_at <= :dateTimeTo)
            ORDER BY p.created_at
            """)
    Flux<Product> findByParams(List<Long> productIds,
                               String productName,
                               String originality,
                               Integer minPrice,
                               Integer maxPrice,
                               LocalDateTime dateTimeFrom,
                               LocalDateTime dateTimeTo);

    default Flux<Product> findByParams(FindProductRequest searchParams, List<Long> ids) {
        return findByParams(
                ids,
                searchParams.getProductName(),
                searchParams.getOriginality(),
                Optional.ofNullable(searchParams.getPriceRange()).map(PriceRange::getMinPrice).orElse(null),
                Optional.ofNullable(searchParams.getPriceRange()).map(PriceRange::getMaxPrice).orElse(null),
                Optional.ofNullable(searchParams.getDateRange()).map(DateRange::getStart).orElse(null),
                Optional.ofNullable(searchParams.getDateRange()).map(DateRange::getEnd).orElse(null)
        );
    }

    @Query("""
            SELECT p.*
            FROM product p
            WHERE
                (:productName IS NULL OR p.name LIKE '%' || :productName || '%') AND
                (:categoryId IS NULL OR p.category_id = :categoryId) AND
                (:originality IS NULL OR p.originality = :originality) AND
                (:participantId IS NULL OR p.participant_id = :participantId) AND
                (:minPrice IS NULL OR p.price >= :minPrice) AND
                (:maxPrice IS NULL OR p.price <= :maxPrice) AND
                (:dateTimeFrom IS NULL OR p.created_at >= :dateTimeFrom) AND
                (:dateTimeTo IS NULL OR p.created_at <= :dateTimeTo) AND
                p.status = 'ACTIVE'
            ORDER BY p.created_at DESC
            """)
    Flux<Product> findBySearchParams(String productName,
                                     Long categoryId,
                                     String originality,
                                     Long participantId,
                                     Integer minPrice,
                                     Integer maxPrice,
                                     LocalDateTime dateTimeFrom,
                                     LocalDateTime dateTimeTo);

    default Flux<Product> findByParams(FindProductRequest searchParams) {
        return findBySearchParams(
                searchParams.getProductName(),
                searchParams.getCategoryId(),
                searchParams.getOriginality(),
                searchParams.getParticipantId(),
                Optional.ofNullable(searchParams.getPriceRange()).map(PriceRange::getMinPrice).orElse(null),
                Optional.ofNullable(searchParams.getPriceRange()).map(PriceRange::getMaxPrice).orElse(null),
                Optional.ofNullable(searchParams.getDateRange()).map(DateRange::getStart).orElse(null),
                Optional.ofNullable(searchParams.getDateRange()).map(DateRange::getEnd).orElse(null)
        );
    }

    Flux<Product> findByParticipantId(Long participantId);

    Mono<Void> deleteAllByParticipantId(Long participantId);

    @Query("SELECT * FROM product WHERE status = 'ACTIVE' AND id = :productId")
    Mono<Product> findActualProduct(Long productId);
}