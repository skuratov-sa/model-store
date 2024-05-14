package com.model_store.service;

import com.model_store.model.CreateOrUpdateImages;
import com.model_store.model.base.Image;
import com.model_store.model.constant.ImageTag;
import com.model_store.repository.ImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ImageService {
    private final ImageRepository imageRepository;

    public Flux<Image> findImagesByEntity(Long entityId, ImageTag tag) {
        return imageRepository.findByEntityIdAndTag(entityId, tag);
    }

    public Flux<Image> saveImages(CreateOrUpdateImages request) {
        List<Image> images = request.getPaths().stream()
                .map(path -> Image.builder()
                        .entityId(request.getEntityId())
                        .path(path)
                        .tag(request.getTag())
                        .build()
                ).toList();
        return imageRepository.saveAll(images);
    }

    public Mono<Void> deleteImages(List<Long> ids) {
        return imageRepository.deleteAllById(ids);
    }
}