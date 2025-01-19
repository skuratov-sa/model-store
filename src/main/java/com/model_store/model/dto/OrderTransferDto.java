package com.model_store.model.dto;

import com.model_store.model.constant.Currency;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderTransferDto {
    private Long transferId;
    private Long addressId;
    private Long imageId;
    private String address;
    private Float price;
    private Currency currency;
}