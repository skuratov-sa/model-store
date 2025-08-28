package com.model_store.model;

import com.model_store.model.constant.Currency;
import com.model_store.model.constant.ProductAvailabilityType;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrUpdateProductRequest {
    private String name;
    private String description;
    private Float price;
    private Float prepaymentAmount;
    private List<Long> categoryIds;
    private Integer count;
    private Currency currency;
    private String originality;
    private ProductAvailabilityType availability;
    private String externalUrl;
    private List<Long> imageIds;
}