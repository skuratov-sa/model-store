package com.model_store.model.base;

import com.model_store.model.constant.ImageStatus;
import com.model_store.model.constant.ImageTag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "image")
public class Image {

    @Id
    private Long id;
    private String filename;
    private ImageTag tag;
    private ImageStatus status;
    private Long entityId;
}