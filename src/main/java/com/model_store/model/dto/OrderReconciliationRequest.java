package com.model_store.model.dto;

import java.math.BigDecimal;

public record OrderReconciliationRequest(Long expectedVersion, String carrier, String address,
        BigDecimal goodsAmount, BigDecimal plannedPrepaymentAmount, BigDecimal deliveryAmount,
        BigDecimal confirmedPaidAmount, String reason) { }
