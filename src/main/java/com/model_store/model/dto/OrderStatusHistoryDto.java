package com.model_store.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.model_store.model.constant.OrderStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class OrderStatusHistoryDto {
    private OrderStatus status;
    private String comment;

    @JsonFormat(pattern = "dd.MM.yyyy HH:mm:ss", timezone = "Europe/Moscow")
    private Instant changedAt;
}