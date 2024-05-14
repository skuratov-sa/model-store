package com.model_store.model;

import com.model_store.model.constant.ImageTag;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CreateOrUpdateImages {
    private List<String> paths;
    private ImageTag tag;
    private Long entityId;
}