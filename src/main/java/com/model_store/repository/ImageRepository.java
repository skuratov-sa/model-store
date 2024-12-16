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

    @Query("SELECT id FROM image WHERE entity_id = :entityId AND tag = :tag::image_tag")
    Flux<Long> findIdsByEntityIdAndTag(Long entityId, ImageTag tag);

    @Query("SELECT id FROM image WHERE entity_id = :entityId AND tag = :tag::image_tag AND status = 'ACTIVE'")
    Flux<Long> findActualIdsByEntity(Long entityId, ImageTag tag);
}