package com.model_store.model.dto;

import com.model_store.model.constant.Currency;
import com.model_store.model.constant.ShippingMethodsType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TransferDto {
    private ShippingMethodsType sending;
    private Integer price;
    private Currency currency;
}