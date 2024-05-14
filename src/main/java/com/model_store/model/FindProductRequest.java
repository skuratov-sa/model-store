package com.model_store.model;

import lombok.Data;

@Data
public class FindProductRequest {
    private int productId;
    private String productName;
    private PriceRange priceRange;
}