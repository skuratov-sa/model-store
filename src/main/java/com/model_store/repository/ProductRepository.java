package com.model_store.repository;

import com.model_store.model.FindMyProductRequest;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
import com.model_store.model.constant.ProductStatus;
import com.model_store.model.constant.SortByType;
import com.model_store.model.page.Pageable;
import com.model_store.model.util.DateRange;
import com.model_store.model.util.PriceRange;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static com.model_store.model.constant.SortByType.DATE_DESC;

@Repository
public interface ProductRepository extends ReactiveCrudRepository<Product, Long> {

    @Query("""
            SELECT p.*
            FROM product p
                LEFT JOIN public.product_category pc on p.id = pc.product_id
                LEFT JOIN category c ON pc.category_id = c.id
            WHERE
                (:includeCountEmpty IS TRUE OR p.count is NULL OR p.count > 0) AND
                (:name IS NULL OR p.name ILIKE '%' || :name || '%' OR c.name ILIKE '%' || :name || '%') AND
                (:productIds IS NULL OR p.id = ANY(:productIds)) AND
                (:originality IS NULL OR p.originality = :originality) AND
                (:participantId IS NULL OR p.participant_id = :participantId) AND
                (:minPrice IS NULL OR p.price >= :minPrice) AND
                (:maxPrice IS NULL OR p.price <= :maxPrice) AND
                (:dateTimeFrom IS NULL OR p.created_at >= :dateTimeFrom) AND
                (:dateTimeTo IS NULL OR p.created_at <= :dateTimeTo) AND
                (:productStatuses IS NULL OR p.status::product_status = ANY(:productStatuses::product_status[])) AND
                (:categoryId IS NULL OR c.id = :categoryId) AND
                (
                    -- Условие пагинации для сортировки по дате
                    (:sortBy = 'DATE_DESC' AND (:lastCreatedAt IS NULL OR (p.created_at < :lastCreatedAt OR (p.created_at = :lastCreatedAt AND p.id < :lastId)))) OR
            
                        -- Условие пагинации для сортировки по возрастанию цены
                    (:sortBy = 'PRICE_ASC' AND (:lastPrice IS NULL OR (p.price > :lastPrice OR (p.price = :lastPrice AND p.id > :lastId)))) OR
            
                        -- Условие пагинации для сортировки по убыванию цены
                    (:sortBy = 'PRICE_DESC' AND (:lastPrice IS NULL OR (p.price < :lastPrice OR (p.price = :lastPrice AND p.id < :lastId))))
                )
            GROUP BY p.id, p.created_at, p.price
            ORDER BY
                CASE WHEN :sortBy = 'DATE_DESC' THEN p.created_at END DESC,
                CASE WHEN :sortBy = 'PRICE_ASC' THEN p.price END ASC,
                CASE WHEN :sortBy = 'PRICE_DESC' THEN p.price END DESC,
                p.id DESC
            LIMIT :limit
            """)
    Flux<Product> findByParams(
            Boolean includeCountEmpty,
            Long categoryId,
            Long participantId,
            String name,
            String originality,
            Integer minPrice,
            Integer maxPrice,
            LocalDateTime dateTimeFrom,
            LocalDateTime dateTimeTo,
            Long[] productIds,
            ProductStatus[] productStatuses,
            Instant lastCreatedAt,
            Float lastPrice,
            Long lastId,
            SortByType sortBy,
            Integer limit
    );


    @Query("""
            SELECT DISTINCT result.name
            FROM (
                     SELECT p.name
                     FROM product p
                     WHERE p.name ILIKE '%' || :search || '%'
                        OR similarity(p.name, :search) > 0.25
                     UNION
                     SELECT c.name
                     FROM category c
                     WHERE c.name ILIKE '%' || :search || '%'
                        OR similarity(c.name, :search) > 0.25
                 ) AS result
            ORDER BY result.name
            LIMIT 10
            """)
    Flux<String> findNamesBySearch(String search);


    default Flux<Product> findByParams(FindProductRequest searchParams, Long[] ids) {
        int limit = Optional.ofNullable(searchParams.getPageable()).map(Pageable::getSize).orElse(50); // limit

        return findByParams(
                false,
                searchParams.getCategoryId(),
                searchParams.getParticipantId(),
                searchParams.getName(),
                searchParams.getOriginality(),
                Optional.ofNullable(searchParams.getPriceRange()).map(PriceRange::getMinPrice).orElse(null),
                Optional.ofNullable(searchParams.getPriceRange()).map(PriceRange::getMaxPrice).orElse(null),
                Optional.ofNullable(searchParams.getDateRange()).map(DateRange::getStart).orElse(null),
                Optional.ofNullable(searchParams.getDateRange()).map(DateRange::getEnd).orElse(null),
                ids,
                new ProductStatus[]{ProductStatus.ACTIVE},
                Optional.ofNullable(searchParams.getPageable()).map(Pageable::getLastCreatedAt).orElse(null),
                Optional.ofNullable(searchParams.getPageable()).map(Pageable::getLastPrice).orElse(null),
                Optional.ofNullable(searchParams.getPageable()).map(Pageable::getLastId).orElse(0L),
                Optional.ofNullable(searchParams.getPageable()).map(Pageable::getSortBy).orElse(DATE_DESC),
                limit
        );
    }

    default Flux<Product> findMyByParams(FindMyProductRequest searchParams, Long participantId) {
        int limit = Optional.ofNullable(searchParams.getPageable()).map(Pageable::getSize).orElse(50); // limit

        return findByParams(
                true,
                searchParams.getCategoryId(),
                participantId,
                searchParams.getName(),
                searchParams.getOriginality(),
                Optional.ofNullable(searchParams.getPriceRange()).map(PriceRange::getMinPrice).orElse(null),
                Optional.ofNullable(searchParams.getPriceRange()).map(PriceRange::getMaxPrice).orElse(null),
                Optional.ofNullable(searchParams.getDateRange()).map(DateRange::getStart).orElse(null),
                Optional.ofNullable(searchParams.getDateRange()).map(DateRange::getEnd).orElse(null),
                null,
                new ProductStatus[]{ProductStatus.ACTIVE, ProductStatus.BLOCKED, ProductStatus.TIME_EXPIRED},
                Optional.ofNullable(searchParams.getPageable()).map(Pageable::getLastCreatedAt).orElse(null),
                Optional.ofNullable(searchParams.getPageable()).map(Pageable::getLastPrice).orElse(null),
                Optional.ofNullable(searchParams.getPageable()).map(Pageable::getLastId).orElse(0L),
                Optional.ofNullable(searchParams.getPageable()).map(Pageable::getSortBy).orElse(DATE_DESC),
                limit
        );
    }


    Flux<Product> findByParticipantId(Long participantId);

    @Query("SELECT * FROM product WHERE status = 'ACTIVE' AND id = :productId")
    Mono<Product> findActualProduct(Long productId);

    @Query("SELECT * FROM product WHERE status in ('ACTIVE', 'BLOCKED') AND id = :productId")
    Mono<Product> findProductForExtend(Long productId);

    @Query("SELECT * FROM product WHERE status = 'ACTIVE' AND id = :productId")
    Mono<Product> findFullProductInfo(Long productId);

    @Query("SELECT id FROM product WHERE status = 'ACTIVE' AND expiration_date < CURRENT_TIMESTAMP")
    Flux<Long> findExpiredActiveProductIds();
}