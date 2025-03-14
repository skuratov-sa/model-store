package com.model_store.service;

import com.model_store.model.base.Image;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.dto.ImageResponse;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public interface ImageService {
    Flux<Long> saveImages(ImageTag tag, Long entityId, List<FilePart> files);

    Flux<ImageResponse> findImagesByIds(List<Long> imageIds);

    Flux<Long> findActualImages(Long entityId, ImageTag tag);

    Mono<Long> findMainImage(Long entityId, ImageTag tag);

    Mono<Void> updateImagesStatus(List<Long> imageIds, Long entityId, ImageStatus status, ImageTag tag);

    Mono<Boolean> isActualEntity(Long entityId, ImageTag tag, Long participantId);

    Flux<Image> findTemporaryImages();

    Mono<Void> deleteImagesByEntityId(Long entityId, ImageTag tag);

    Mono<Void> deleteById(Long id);

    Mono<Void> deleteImages(List<Long> imageIds, ImageTag tag, Long participantId);
}