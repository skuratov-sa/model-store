package com.model_store.model.dto;

import com.model_store.model.constant.ImageTag;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImageDto {
    private String path;
    private ImageTag tag;
    private Long entityId;
}
