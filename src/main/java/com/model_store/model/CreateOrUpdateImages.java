package com.model_store.model;

import com.model_store.model.constant.ImageTag;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateOrUpdateImages {
    private ImageTag tag;
    private Long entityId;
}