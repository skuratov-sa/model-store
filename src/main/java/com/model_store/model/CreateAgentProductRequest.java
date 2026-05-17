package com.model_store.model;

import com.model_store.model.constant.Currency;
import lombok.Data;

import java.util.List;

@Data
public class CreateAgentProductRequest {
    private String name;
    private String description;
    private Float price;
    private Currency currency;
    private String originality;
    private String externalUrl;
    private List<Long> categoryIds;
    private List<Long> imageIds;
}
