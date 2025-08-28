package com.model_store.model.base;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "product_category")
public class ProductCategory {

    public ProductCategory(Long productId, Long categoryId) {
        this.productId = productId;
        this.categoryId = categoryId;
    }

    @Id
    private Long id;
    private Long productId;
    private Long categoryId;
}