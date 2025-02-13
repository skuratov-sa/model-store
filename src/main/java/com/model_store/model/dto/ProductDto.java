package com.model_store.model.dto;

import com.model_store.model.constant.Currency;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProductDto {
    private Long id;
    private String name;
    private Long count;
    private Float price;
    private Currency currency;
    private CategoryDto category;
    private Long imageId;
}