package com.model_store.model.dto;

import com.model_store.model.constant.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FindOrderResponse {
    private Long orderId;
    private OrderStatus actualStatus;
    private Float totalPrice;
    private Float prepaymentAmount;
    private Long sellerId;
    private String sellerLogin;
    private String createdAt;
    private UserInfoDto userInfo;
    private ProductDto product;
    private OrderTransferDto transfer;
    private List<Long> images;
    @Schema(description = "ID подтверждения оплаты; null, если покупатель ещё не заявил оплату")
    private Long paymentProofImageId;
    private String deliveryUrl;
    private List<OrderStatusHistoryDto> histories;
}
