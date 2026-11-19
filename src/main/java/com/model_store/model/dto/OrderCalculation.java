package com.model_store.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.model_store.model.constant.Currency;
import java.math.BigDecimal;

public record OrderCalculation(boolean known, Currency currency, int scale,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal goodsAmount,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal deliveryAmount,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal confirmedPaidAmount,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal remainingAmount) { }
