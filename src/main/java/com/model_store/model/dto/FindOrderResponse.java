package com.model_store.model.dto;

import com.model_store.model.constant.OrderStatus;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FindOrderResponse {
    private Long orderId;
    private OrderStatus actualStatus;
    private Float totalPrice;
    private String createdAt;
    private UserInfoDto userInfo;
    private ProductDto product;
    private OrderTransferDto transfer;
    private List<Long> images;
    private List<OrderStatusHistoryDto> histories;
}