package com.model_store.service;

import com.model_store.model.base.OrderStatusHistory;
import reactor.core.publisher.Flux;

public interface OrderStatusHistoryService {
    Flux<OrderStatusHistory> findByOrderId(Long orderId);
}