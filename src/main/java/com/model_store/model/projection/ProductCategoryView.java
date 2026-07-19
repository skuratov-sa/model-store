package com.model_store.model.projection;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.relational.core.mapping.Column;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductCategoryView {
    @Column("product_id")
    private Long productId;
    @Column("category_id")
    private Long categoryId;
    @Column("category_name")
    private String categoryName;
}
