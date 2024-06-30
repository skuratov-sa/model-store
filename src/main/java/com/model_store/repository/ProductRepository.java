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
                (:dateTimeFrom IS NULL OR p.createdat >= :dateTimeFrom) AND
                (:dateTimeTo IS NULL OR p.createdat <= :dateTimeTo)
            ORDER BY p.createdat
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
            SELECT *
            FROM product p
            WHERE
                (:productName IS NULL OR p.name = :productName) AND
                (:minPrice IS NULL OR p.price >= :minPrice) AND
                (:maxPrice IS NULL OR p.price <= :maxPrice) AND
                (:originality IS NULL OR p.originality = :originality) AND
                (:dateTimeFrom IS NULL OR p.createdat >= :dateTimeFrom) AND
                (:dateTimeTo IS NULL OR p.createdat <= :dateTimeTo)
            ORDER BY p.createdat
            """)
    Flux<Product> findByParams(String productName,
                               String originality,
                               Integer minPrice,
                               Integer maxPrice,
                               LocalDateTime dateTimeFrom,
                               LocalDateTime dateTimeTo);

    default Flux<Product> findByParams(FindProductRequest searchParams) {
        return findByParams(
                searchParams.getProductName(),
                searchParams.getOriginality(),
                Optional.ofNullable(searchParams.getPriceRange()).map(PriceRange::getMinPrice).orElse(null),
                Optional.ofNullable(searchParams.getPriceRange()).map(PriceRange::getMaxPrice).orElse(null),
                Optional.ofNullable(searchParams.getDateRange()).map(DateRange::getStart).orElse(null),
                Optional.ofNullable(searchParams.getDateRange()).map(DateRange::getEnd).orElse(null)
        );
    }

    Mono<Void> deleteAllByParticipantId(Long participantId);
}