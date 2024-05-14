package com.model_store.repository;

import com.model_store.model.base.Image;
import com.model_store.model.constant.ImageTag;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface ImageRepository extends ReactiveCrudRepository<Image, Long> {
    Flux<Image> findByEntityIdAndTag(Long entityId, ImageTag tag);
}