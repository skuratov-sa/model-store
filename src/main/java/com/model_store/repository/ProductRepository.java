package com.model_store.repository;

import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
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
            SELECT COUNT(*)
            FROM product p
            WHERE
                (:productName IS NULL OR p.name ILIKE '%' || :productName || '%') AND
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
            LEFT JOIN category c ON p.category_id = c.id
            WHERE
                (:name IS NULL OR p.name ILIKE '%' || :name || '%' OR c.name ILIKE '%' || :name || '%') AND
                (:categoryId IS NULL OR p.category_id = :categoryId) AND
                (:originality IS NULL OR p.originality = :originality) AND
                (:participantId IS NULL OR p.participant_id = :participantId) AND
                (:minPrice IS NULL OR p.price >= :minPrice) AND
                (:maxPrice IS NULL OR p.price <= :maxPrice) AND
                (:dateTimeFrom IS NULL OR p.created_at >= :dateTimeFrom) AND
                (:dateTimeTo IS NULL OR p.created_at <= :dateTimeTo) AND
                (:productIds IS NULL OR p.id = ANY(:productIds)) AND
                (
                    -- Условие пагинации для сортировки по дате
                    (:sortBy = 'DATE_DESC' AND (:lastCreatedAt IS NULL OR (p.created_at < :lastCreatedAt OR (p.created_at = :lastCreatedAt AND p.id < :lastId)))) OR
            
                        -- Условие пагинации для сортировки по возрастанию цены
                    (:sortBy = 'PRICE_ASC' AND (:lastPrice IS NULL OR (p.price > :lastPrice OR (p.price = :lastPrice AND p.id > :lastId)))) OR
            
                        -- Условие пагинации для сортировки по убыванию цены
                    (:sortBy = 'PRICE_DESC' AND (:lastPrice IS NULL OR (p.price < :lastPrice OR (p.price = :lastPrice AND p.id < :lastId))))
                    ) AND
                p.status = 'ACTIVE'
            ORDER BY
                CASE WHEN :sortBy = 'DATE_DESC' THEN p.created_at END DESC,
                CASE WHEN :sortBy = 'PRICE_ASC' THEN p.price END ASC,
                CASE WHEN :sortBy = 'PRICE_DESC' THEN p.price END DESC,
                p.id DESC
            LIMIT :limit
            """)
    Flux<Product> findByParams(
            Long categoryId,
            Long participantId,
            String name,
            String originality,
            Integer minPrice,
            Integer maxPrice,
            LocalDateTime dateTimeFrom,
            LocalDateTime dateTimeTo,
            Long[] productIds,
            Instant lastCreatedAt,
            Float lastPrice,
            Long lastId,
            SortByType sortBy,
            Integer limit
    );

    default Flux<Product> findByParams(FindProductRequest searchParams, Long[] ids) {
        int limit = Optional.ofNullable(searchParams.getPageable()).map(Pageable::getSize).orElse(50); // limit
//        int page = Optional.ofNullable(searchParams.getPageable()).map(Pageable::getPage).orElse(0); // номер страницы
//        int offset = page * limit; // offset = page * limit


        return findByParams(
                searchParams.getCategoryId(),
                searchParams.getParticipantId(),
                searchParams.getName(),
                searchParams.getOriginality(),
                Optional.ofNullable(searchParams.getPriceRange()).map(PriceRange::getMinPrice).orElse(null),
                Optional.ofNullable(searchParams.getPriceRange()).map(PriceRange::getMaxPrice).orElse(null),
                Optional.ofNullable(searchParams.getDateRange()).map(DateRange::getStart).orElse(null),
                Optional.ofNullable(searchParams.getDateRange()).map(DateRange::getEnd).orElse(null),
                ids,
                Optional.ofNullable(searchParams.getPageable()).map(Pageable::getLastCreatedAt).orElse(null),
                Optional.ofNullable(searchParams.getPageable()).map(Pageable::getLastPrice).orElse(null),
                Optional.ofNullable(searchParams.getPageable()).map(Pageable::getLastId).orElse(null),
                Optional.ofNullable(searchParams.getPageable()).map(Pageable::getSortBy).orElse(DATE_DESC),
                limit
        );
    }

    @Query("""
            SELECT p.*
            FROM product p
            WHERE
                (:name IS NULL OR p.name ILIKE '%' || :name || '%') AND
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
    Flux<Product> findBySearchParamsWithPagination(String name,
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
                searchParams.getName(),
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

    @Query("SELECT * FROM product WHERE status = 'ACTIVE' AND id = :productId")
    Mono<Product> findFullProductInfo(Long productId);
}