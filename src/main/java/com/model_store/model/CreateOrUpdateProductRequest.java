package com.model_store.model;

import com.model_store.model.constant.Currency;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrUpdateProductRequest {
    private String name;
    private String description;
    private Float price;
    private Long categoryId;
    private Integer count;
    private Currency currency;
    private Long participantId;
    private String originality;
    private List<Long> imageIds;
}