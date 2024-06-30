package com.model_store.service;

import com.model_store.model.CreateOrUpdateImages;
import com.model_store.model.base.Image;
import com.model_store.model.constant.ImageTag;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public interface ImageService {
    Flux<Image> findImagesByEntity(Long entityId, ImageTag tag);

    Flux<Image> saveImages(CreateOrUpdateImages request);

    Mono<Void> deleteImages(List<Long> ids);
}