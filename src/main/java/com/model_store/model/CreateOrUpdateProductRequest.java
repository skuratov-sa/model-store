package com.model_store.model;

import com.model_store.model.constant.Currency;
import lombok.Data;

@Data
public class CreateOrUpdateProductRequest {
    private Long id;
    private String name;
    private String description;
    private Double price;
    private Integer count;
    private Currency currency;
    private Long participantId;
    private String originality;
}