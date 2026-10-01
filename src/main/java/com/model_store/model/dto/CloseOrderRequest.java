package com.model_store.model.dto;

import lombok.Data;

@Data
public class CloseOrderRequest {
    private String closureReason;
    private String comment;
}
