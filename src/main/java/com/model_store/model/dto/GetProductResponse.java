package com.model_store.model.dto;

import com.model_store.model.constant.Currency;
import com.model_store.model.constant.ProductStatus;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class GetProductResponse {
    private Long id;
    private String name;
    private String description;
    private Float price;
    private Integer count;
    private Currency currency;
    private String originality;
    private Long participantId;
    private ProductStatus status;
    private CategoryDto category;
    private List<Long> imageIds;
}