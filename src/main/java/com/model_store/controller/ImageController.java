package com.model_store.controller;

import com.amazonaws.services.kms.model.NotFoundException;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.dto.ImageResponse;
import com.model_store.service.ImageService;
import com.model_store.service.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/images")
@RequiredArgsConstructor
public class ImageController {
    private final ImageService imageService;
    private final JwtService jwtService;

    @Operation(summary = "Получить картинку по ID")
    @GetMapping
    public Flux<ImageResponse> findImages(@RequestParam List<Long> ids) {
        return imageService.findImagesByIds(ids);
    }

    @Operation(summary = "Получить дефолтную картинку")
    @GetMapping("/default")
    public Mono<ImageResponse> findDefaultImage() {
        return imageService.findImageDefault();
    }

    @Operation(summary = "Сохранить картинку")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Flux<Long> createImage(
            @RequestParam ImageTag tag,
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestParam(required = false) Long entityId,
            @RequestPart("files") Flux<FilePart> filesFlux) {

        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);

        return filesFlux.collectList()
                .flatMapMany(files ->
                        imageService.isActualEntity(entityId, tag, participantId)
                                .filter(Boolean::booleanValue)
                                .switchIfEmpty(Mono.error(new NotFoundException("Entity not found")))
                                .flatMapMany(actual -> imageService.saveImages(tag, entityId, files))
                );
    }

    @Operation(summary = "Удалить список картинок")
    @DeleteMapping
    public Mono<Void> deleteImages(@RequestHeader("Authorization") String authorizationHeader, @RequestParam List<Long> ids, @RequestParam ImageTag tag) {
        Long participantId = jwtService.getIdByAccessToken(authorizationHeader);
        return imageService.deleteImages(ids, tag, participantId);
    }
}