package com.model_store.model.dto;

import com.model_store.model.constant.OrderStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UpdateOrderRequest {
    private Long orderId;
    private OrderStatus orderStatus;
}