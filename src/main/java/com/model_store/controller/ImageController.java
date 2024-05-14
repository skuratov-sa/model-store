package com.model_store.controller;

import com.model_store.model.CreateOrUpdateImages;
import com.model_store.model.base.Image;
import com.model_store.model.constant.ImageTag;
import com.model_store.service.ImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ImageController {
    private final ImageService imageService;

    @GetMapping(path = "/images/{entityId}/{tag}")
    public Flux<Image> getImages(@PathVariable Long entityId, @PathVariable ImageTag tag) {
        return imageService.findImagesByEntity(entityId, tag);
    }

    @PostMapping(path = "/image")
    public Flux<Image> createImage(@RequestBody CreateOrUpdateImages request) {
        return imageService.saveImages(request);
    }

    @DeleteMapping(path = "/image")
    public Mono<Void> deleteImages(@RequestBody List<Long> images) {
        return imageService.deleteImages(images);
    }

}