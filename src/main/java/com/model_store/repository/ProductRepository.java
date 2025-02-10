package com.model_store.repository;

import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
import com.model_store.model.page.Pageable;
import com.model_store.model.util.DateRange;
import com.model_store.model.util.PriceRange;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface ProductRepository extends ReactiveCrudRepository<Product, Long> {

    @Query("""
            SELECT COUNT(*)
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
                (:productIds IS NULL OR p.id = ANY(:productIds)) AND
                p.status = 'ACTIVE'
            """)
    Mono<Integer> countBySearchParams(String productName,
                                      Long categoryId,
                                      String originality,
                                      Long participantId,
                                      Integer minPrice,
                                      Integer maxPrice,
                                      LocalDateTime dateTimeFrom,
                                      LocalDateTime dateTimeTo,
                                      Long[] productIds);

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
                (:productIds IS NULL OR p.id = ANY(:productIds)) AND
                p.status = 'ACTIVE'
            ORDER BY p.created_at DESC
            LIMIT :limit OFFSET :offset;
            """)
    Flux<Product> findByParams(
            Long categoryId,
            Long participantId,
            String productName,
            String originality,
            Integer minPrice,
            Integer maxPrice,
            LocalDateTime dateTimeFrom,
            LocalDateTime dateTimeTo,
            Long[] productIds,
            Integer limit,
            Integer offset);

    default Flux<Product> findByParams(FindProductRequest searchParams, Long[] ids) {
        int limit = Optional.ofNullable(searchParams.getPageable()).map(Pageable::getSize).orElse(50); // limit
        int page = Optional.ofNullable(searchParams.getPageable()).map(Pageable::getPage).orElse(0); // номер страницы
        int offset = page * Math.max(limit, 50); // offset = page * limit

        return findByParams(
                searchParams.getCategoryId(),
                searchParams.getParticipantId(),
                searchParams.getProductName(),
                searchParams.getOriginality(),
                Optional.ofNullable(searchParams.getPriceRange()).map(PriceRange::getMinPrice).orElse(null),
                Optional.ofNullable(searchParams.getPriceRange()).map(PriceRange::getMaxPrice).orElse(null),
                Optional.ofNullable(searchParams.getDateRange()).map(DateRange::getStart).orElse(null),
                Optional.ofNullable(searchParams.getDateRange()).map(DateRange::getEnd).orElse(null),
                ids,
                limit,
                offset
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
            LIMIT :limit OFFSET :offset
            """)
    Flux<Product> findBySearchParamsWithPagination(String productName,
                                                   Long categoryId,
                                                   String originality,
                                                   Long participantId,
                                                   Integer minPrice,
                                                   Integer maxPrice,
                                                   LocalDateTime dateTimeFrom,
                                                   LocalDateTime dateTimeTo,
                                                   Integer limit,
                                                   Integer offset);

    default Mono<Integer> findCountBySearchParams(FindProductRequest searchParams, Long[] ids) {
        // Получаем общее количество элементов
        return countBySearchParams(
                searchParams.getProductName(),
                searchParams.getCategoryId(),
                searchParams.getOriginality(),
                searchParams.getParticipantId(),
                Optional.ofNullable(searchParams.getPriceRange()).map(PriceRange::getMinPrice).orElse(null),
                Optional.ofNullable(searchParams.getPriceRange()).map(PriceRange::getMaxPrice).orElse(null),
                Optional.ofNullable(searchParams.getDateRange()).map(DateRange::getStart).orElse(null),
                Optional.ofNullable(searchParams.getDateRange()).map(DateRange::getEnd).orElse(null),
                null
        );
    }


    Flux<Product> findByParticipantId(Long participantId);

    Mono<Void> deleteAllByParticipantId(Long participantId);

    @Query("SELECT * FROM product WHERE status = 'ACTIVE' AND id = :productId")
    Mono<Product> findActualProduct(Long productId);
}