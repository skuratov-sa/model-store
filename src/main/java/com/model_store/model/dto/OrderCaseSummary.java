package com.model_store.model.dto;

import com.model_store.model.constant.OrderCaseState;
import com.model_store.model.constant.OrderStatus;

import java.time.Instant;

public record OrderCaseSummary(
        Long caseId,
        Long orderId,
        String kind,
        OrderCaseState state,
        Long openedBy,
        String openingComment,
        Instant createdAt,
        OrderStatus orderStatus
) {}
