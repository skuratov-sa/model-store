package com.model_store.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.model_store.model.constant.Currency;
import com.model_store.model.constant.ProductAvailabilityType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDateTime;

@Data
@Builder
public class ProductDto {
    private Long id;
    private String name;
    private Integer count;
    private Float price;
    private Currency currency;
    private CategoryDto category;
    private Long imageId;
    private Long sellerId;
    private ProductAvailabilityType availability;
    private Instant createdAt;
}