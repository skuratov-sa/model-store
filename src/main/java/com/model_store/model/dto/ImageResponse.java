package com.model_store.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImageResponse {
    private String fileName;
    private String contentType;
    private byte[] imageData;
}
