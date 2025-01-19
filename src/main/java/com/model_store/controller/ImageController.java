package com.model_store.controller;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.dto.ImageResponse;
import com.model_store.service.ImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ImageController {
    private final ImageService imageService;

    @Operation(summary = "Получить картинку по ID")
    @GetMapping(path = "/images")
    public Flux<ImageResponse> getImages(@RequestParam @Parameter(description = "Идентификаторы картинок", required = true) List<Long> ids) {
        return imageService.findImagesByIds(ids);
    }

    @Operation(summary = "Сохранить картинку")
    @PostMapping(path = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Flux<Long> createImage(
            @RequestParam ImageTag tag,
            @RequestParam(required = false) Long entityId,
            @RequestPart("files") Flux<FilePart> filesFlux) {

        return filesFlux.collectList()
                .flatMapMany(files ->
                        imageService.isActualEntity(entityId, tag)
                                .filter(Boolean::booleanValue)
                                .switchIfEmpty(Mono.error(new NotFoundException("Entity not found")))
                                .flatMapMany(actual -> imageService.saveImages(tag, entityId, files))
                );
    }

    @Operation(summary = "Удалить список картинок")
    @DeleteMapping(path = "/image")
    public Mono<Void> deleteImages(@RequestParam @Parameter(description = "Идентификаторы картинок", required = true) List<Long> ids) {
        return imageService.updateImagesStatus(ids, null, ImageStatus.DELETE, null);
    }
}