package com.model_store.model.dto;

public record ImageMetadataDto(
        Long id,
        String originalUrl,
        String mediumUrl,
        String thumbnailUrl,
        Integer width,
        Integer height,
        String contentType
) {}
