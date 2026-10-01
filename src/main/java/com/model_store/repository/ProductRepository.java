package com.model_store.repository;

import com.model_store.model.FindMyProductRequest;
import com.model_store.model.FindProductRequest;
import com.model_store.model.base.Product;
import com.model_store.model.constant.CatalogFilterFlag;
import com.model_store.model.constant.ProductStatus;
import com.model_store.model.constant.SortByType;
import com.model_store.model.page.Pageable;
import com.model_store.model.util.DateRange;
import com.model_store.model.util.PriceRange;
import org.springframework.data.r2dbc.repository.Modifying;
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
            WHERE
                (:includeGiveaways IS TRUE OR p.availability <> 'GIVEAWAY') AND
                (:includeCountEmpty IS TRUE OR p.count is NULL OR p.count > 0) AND
                (:name IS NULL OR p.name ILIKE '%' || :name || '%' OR EXISTS (
                    SELECT 1 FROM product_category name_pc
                    JOIN category name_c ON name_c.id = name_pc.category_id
                    WHERE name_pc.product_id = p.id
                      AND name_c.name ILIKE '%' || :name || '%'
                      AND (:categoryId IS NULL OR name_pc.category_id = :categoryId)
                )) AND
                (:productIds IS NULL OR p.id = ANY(:productIds)) AND
                (:originality IS NULL OR p.originality = :originality) AND
                (:participantId IS NULL OR p.participant_id = :participantId) AND
                (:minPrice IS NULL OR p.price >= :minPrice) AND
                (:maxPrice IS NULL OR p.price <= :maxPrice) AND
                (:dateTimeFrom IS NULL OR p.created_at >= :dateTimeFrom) AND
                (:dateTimeTo IS NULL OR p.created_at <= :dateTimeTo) AND
                (:productStatuses IS NULL OR p.status::product_status = ANY(:productStatuses::product_status[])) AND
                (:categoryId IS NULL OR EXISTS (
                    SELECT 1 FROM product_category category_pc
                    WHERE category_pc.product_id = p.id AND category_pc.category_id = :categoryId
                )) AND
                (:preorderFilter IS NULL OR (p.availability = 'PREORDER') = :preorderFilter) AND
                (:usedFilter IS NULL OR p.used = :usedFilter) AND
                (:includeAdult IS TRUE OR NOT EXISTS (
                    SELECT 1 FROM product_category pc2
                    JOIN category c2 ON pc2.category_id = c2.id
                    WHERE pc2.product_id = p.id AND c2.slug = 'nsfw_adult'
                )) AND
                (
                    -- Условие пагинации для сортировки по дате
                    (:sortBy = 'DATE_DESC' AND (:lastCreatedAt IS NULL OR (p.created_at < :lastCreatedAt OR (p.created_at = :lastCreatedAt AND p.id < :lastId)))) OR
            
                        -- Условие пагинации для сортировки по возрастанию цены
                    (:sortBy = 'PRICE_ASC' AND (:lastPrice IS NULL OR (p.price > :lastPrice OR (p.price = :lastPrice AND p.id > :lastId)))) OR
            
                        -- Условие пагинации для сортировки по убыванию цены
                    (:sortBy = 'PRICE_DESC' AND (:lastPrice IS NULL OR (p.price < :lastPrice OR (p.price = :lastPrice AND p.id < :lastId))))
                )
            ORDER BY
                CASE WHEN :sortBy = 'DATE_DESC' THEN p.created_at END DESC,
                CASE WHEN :sortBy = 'PRICE_ASC' THEN p.price END ASC,
                CASE WHEN :sortBy = 'PRICE_DESC' THEN p.price END DESC,
                p.id DESC
            LIMIT :limit
            """)
    Flux<Product> findByParams(
            Boolean includeGiveaways,
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
            Boolean includeAdult,
            Boolean preorderFilter,
            Boolean usedFilter,
            Integer limit
    );


    @Query("""
            SELECT DISTINCT result.name
            FROM (
                     SELECT p.name
                     FROM product p
                     WHERE (p.name ILIKE '%' || :search || '%'
                        OR similarity(p.name, :search) > 0.25)
                       AND p.status = 'ACTIVE'
                       AND p.availability <> 'GIVEAWAY'
                       AND (p.count IS NULL OR p.count > 0)
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


    default Flux<Product> findByParams(FindProductRequest searchParams, Long[] ids, Boolean includeAdult) {
        int limit = Optional.ofNullable(searchParams.getPageable()).map(Pageable::getSize).orElse(50); // limit

        return findByParams(
                false,
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
                includeAdult,
                preorderFilter(searchParams.getCatalogFlags()),
                usedFilter(searchParams.getUsed(), searchParams.getCatalogFlags()),
                limit
        );
    }

    default Flux<Product> findBasketByParams(FindProductRequest searchParams, Long[] ids, Boolean includeAdult) {
        int limit = Optional.ofNullable(searchParams.getPageable()).map(Pageable::getSize).orElse(50); // limit

        return findByParams(
                false,
                true,
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
                includeAdult,
                preorderFilter(searchParams.getCatalogFlags()),
                usedFilter(searchParams.getUsed(), searchParams.getCatalogFlags()),
                limit
        );
    }
    default Flux<Product> findMyByParams(FindMyProductRequest searchParams, Long participantId, boolean includeGiveaways) {
        int limit = Optional.ofNullable(searchParams.getPageable()).map(Pageable::getSize).orElse(50); // limit

        return findByParams(
                includeGiveaways,
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
                new ProductStatus[]{ProductStatus.ACTIVE, ProductStatus.AWAITING_GIVEAWAY, ProductStatus.BLOCKED, ProductStatus.TIME_EXPIRED},
                Optional.ofNullable(searchParams.getPageable()).map(Pageable::getLastCreatedAt).orElse(null),
                Optional.ofNullable(searchParams.getPageable()).map(Pageable::getLastPrice).orElse(null),
                Optional.ofNullable(searchParams.getPageable()).map(Pageable::getLastId).orElse(0L),
                Optional.ofNullable(searchParams.getPageable()).map(Pageable::getSortBy).orElse(DATE_DESC),
                true,
                preorderFilter(searchParams.getCatalogFlags()),
                usedFilter(searchParams.getUsed(), searchParams.getCatalogFlags()),
                limit
        );
    }

    private static boolean hasFlag(java.util.List<CatalogFilterFlag> flags, CatalogFilterFlag flag) {
        return flags != null && flags.contains(flag);
    }

    private static Boolean preorderFilter(java.util.List<CatalogFilterFlag> flags) {
        boolean preorder = hasFlag(flags, CatalogFilterFlag.PREORDER);
        boolean nonPreorder = hasFlag(flags, CatalogFilterFlag.NON_PREORDER);
        return preorder == nonPreorder ? null : preorder;
    }

    private static Boolean usedFilter(Boolean used, java.util.List<CatalogFilterFlag> flags) {
        return used != null ? used : hasFlag(flags, CatalogFilterFlag.USED) ? true : null;
    }


    Flux<Product> findByParticipantId(Long participantId);

    @Query("SELECT * FROM product WHERE status = 'ACTIVE' AND availability <> 'GIVEAWAY' AND id = :productId")
    Mono<Product> findActualProduct(Long productId);

    @Query("SELECT * FROM product WHERE status = 'ACTIVE' AND availability <> 'GIVEAWAY' AND id = :productId FOR UPDATE")
    Mono<Product> findActualProductForUpdate(Long productId);

    @Query("SELECT * FROM product WHERE id = :productId FOR UPDATE")
    Mono<Product> findByIdForUpdate(Long productId);

    @Query("SELECT * FROM product WHERE status in ('ACTIVE', 'TIME_EXPIRED') AND availability <> 'GIVEAWAY' AND id = :productId FOR UPDATE")
    Mono<Product> findProductForExtendForUpdate(Long productId);

    @Query("SELECT value FROM dictionary WHERE type = 'PRODUCT_AVAILABILITY' AND value = 'GIVEAWAY' FOR UPDATE")
    Mono<String> lockGiveawayActivation();

    @Query("""
            SELECT EXISTS (
                SELECT 1 FROM product
                WHERE availability = 'GIVEAWAY' AND giveaway_enabled
                  AND status IN ('ACTIVE', 'AWAITING_GIVEAWAY')
                  AND id <> :productId
                  AND giveaway_start_at < :endAt AND giveaway_end_at > :startAt
            )
            """)
    Mono<Boolean> existsOverlappingGiveaway(Long productId, Instant startAt, Instant endAt);

    @Query("""
            SELECT * FROM product
            WHERE availability = 'GIVEAWAY' AND status IN ('ACTIVE', 'AWAITING_GIVEAWAY')
              AND giveaway_enabled AND giveaway_end_at > CURRENT_TIMESTAMP
            ORDER BY CASE WHEN giveaway_start_at <= CURRENT_TIMESTAMP THEN 0 ELSE 1 END,
                     giveaway_start_at, id
            LIMIT 1
            """)
    Mono<Product> findActiveGiveaway();

    @Query("""
            SELECT * FROM product
            WHERE id = :productId AND availability = 'GIVEAWAY'
              AND status IN ('ACTIVE', 'AWAITING_GIVEAWAY')
              AND giveaway_enabled AND giveaway_end_at > CURRENT_TIMESTAMP
            """)
    Mono<Product> findPublicGiveawayById(Long productId);

    @Query("""
            SELECT p.* FROM product p
            JOIN participant owner ON owner.id = p.participant_id
            WHERE p.giveaway_end_at IS NOT NULL AND p.status <> 'DELETED'
              AND (p.participant_id = :adminId OR owner.is_agent)
            ORDER BY p.giveaway_end_at DESC, p.id DESC
            LIMIT :limit OFFSET :offset
            """)
    Flux<Product> findGiveawayHistory(Long adminId, int limit, long offset);

    @Modifying
    @Query("""
            UPDATE product SET status = 'TIME_EXPIRED'
            WHERE availability <> 'GIVEAWAY' AND status = 'ACTIVE' AND expiration_date <= CURRENT_TIMESTAMP
            """)
    Mono<Integer> expireDueOrdinaryProducts();

    @Query("SELECT * FROM product WHERE status in ('ACTIVE', 'TIME_EXPIRED') AND availability <> 'GIVEAWAY' AND id = :productId")
    Mono<Product> findProductForExtend(Long productId);

    @Query("SELECT id FROM product WHERE status = 'ACTIVE' AND availability <> 'GIVEAWAY' AND expiration_date < CURRENT_TIMESTAMP")
    Flux<Long> findExpiredActiveProductIds();

    @Query("""
            SELECT p.id FROM product p
            WHERE p.status = 'DELETED'
              AND NOT EXISTS (SELECT 1 FROM image i WHERE i.tag = 'PRODUCT' AND i.entity_id = p.id)
            ORDER BY p.id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """)
    Flux<Long> findDeletedWithoutImages(int limit);

    @Modifying
    @Query("DELETE FROM product_favorite WHERE product_id = ANY(:productIds)")
    Mono<Integer> deleteFavoritesByProductIds(Long[] productIds);

    @Modifying
    @Query("DELETE FROM product_basket WHERE product_id = ANY(:productIds)")
    Mono<Integer> deleteBasketByProductIds(Long[] productIds);

    @Modifying
    @Query("DELETE FROM product_cart WHERE product_id = ANY(:productIds)")
    Mono<Integer> deleteCartByProductIds(Long[] productIds);

    @Modifying
    @Query("""
            DELETE FROM product p
            WHERE p.id = ANY(:productIds) AND p.status = 'DELETED'
              AND NOT EXISTS (SELECT 1 FROM image i WHERE i.tag = 'PRODUCT' AND i.entity_id = p.id)
            """)
    Mono<Integer> deleteDeletedWithoutImages(Long[] productIds);

    @Modifying
    @Query("UPDATE product SET count = count - :amount WHERE id = :id AND status = 'ACTIVE' AND availability = 'PURCHASABLE' AND count >= :amount")
    Mono<Integer> decrementCountIfSufficient(Long id, Integer amount);

    @Modifying
    @Query("UPDATE product SET count = count + :amount WHERE id = :id AND count IS NOT NULL")
    Mono<Integer> incrementCountIfLimited(Long id, Integer amount);
}
