package com.model_store.model.projection;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.relational.core.mapping.Column;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageMainView {
    @Column("entity_id")
    private Long entityId;
    @Column("image_id")
    private Long imageId;
}
