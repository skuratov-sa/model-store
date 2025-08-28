package com.model_store.model.dto;

import com.model_store.model.constant.Currency;
import com.model_store.model.constant.ProductAvailabilityType;
import com.model_store.model.constant.ProductStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class ProductDto {
    private Long id;
    private String name;
    private Integer count;
    private Float price;
    private Currency currency;
    private List<CategoryDto> categories;
    private Long imageId;
    private Long sellerId;
    private Instant expirationDate;
    private ProductStatus status;
    private ProductAvailabilityType availability;
    private Instant createdAt;
}