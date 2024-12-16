package com.model_store.mapper;

import com.model_store.model.base.Image;
import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import com.model_store.model.dto.ImageResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ImageMapper {
    Image toImage(Long entityId, ImageTag tag, String filename, ImageStatus status);

    ImageResponse toImageResponseDto(String fileName, String contentType, byte[] imageData);
}
