package com.model_store.model.dto;

import com.model_store.model.base.OrderCase;
import com.model_store.model.constant.OrderStatus;

import java.util.List;

public record OrderCaseDetail(
        OrderCase orderCase,
        OrderStatus orderStatus,
        Long buyerId,
        Long sellerId,
        Long productId,
        Integer count,
        Float totalPrice,
        Float prepaymentAmount,
        Long paymentProofImageId,
        List<Long> orderImages,
        List<Long> caseImages
) {}
