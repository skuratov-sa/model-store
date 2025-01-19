package com.model_store.repository;

import com.model_store.model.base.Image;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ImageRepository extends ReactiveCrudRepository<Image, Long> {

    @Modifying
    @Query("UPDATE image SET status = :status WHERE id = :id")
    Mono<Void> updateStatusById(Long id, ImageStatus status);

    @Modifying
    @Query("UPDATE image SET status = :status WHERE entity_id = :entityId AND tag = :tag::image_tag")
    Mono<Void> updateStatusById(Long entityId, ImageTag tag, ImageStatus status);

    @Query("SELECT id FROM image WHERE entity_id = :entityId AND tag = :tag::image_tag")
    Flux<Long> findIdsByEntityIdAndTag(Long entityId, ImageTag tag);

    @Query("""
            SELECT id
            FROM image
            WHERE entity_id = :entityId
              AND tag = :tag::image_tag
              AND status = 'ACTIVE'
            ORDER BY created_at
            """)
    Flux<Long> findActualIdsByEntity(Long entityId, ImageTag tag);

    @Query("SELECT i.status FROM Image i WHERE i.id = :id AND i.status = 'TEMPORARY'")
    Mono<Boolean> isTemporaryStatusById(Long id);

    @Query("SELECT * FROM image WHERE status in ('DELETE', 'TEMPORARY') AND created_at <= NOW() - INTERVAL '24 HOURS'")
    Flux<Image> findImagesToDelete();
}