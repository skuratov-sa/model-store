package com.model_store.model.dto;

import com.model_store.model.base.Product;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.SuperBuilder;

import java.util.List;

@Data
@SuperBuilder
public class FindParticipantResponse {
    private int totalCount;
    private List<Product> products;
}