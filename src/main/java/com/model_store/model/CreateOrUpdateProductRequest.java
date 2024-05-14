package com.model_store.model;

import com.model_store.model.constant.Currency;
import lombok.Data;

import java.time.Instant;

@Data
public class CreateOrUpdateProductRequest {
    private Long id;
    private String name;
    private String description;
    private Double price;
    private Currency currency;
    private String originality;
    private Instant createdAt;
}