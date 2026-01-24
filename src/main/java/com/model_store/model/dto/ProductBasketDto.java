package com.model_store.model.dto;

import lombok.Data;

@Data
public class ProductBasketDto {
    private ProductDto product;
    private Integer count;
}
