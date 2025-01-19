package com.model_store.service.impl;

import com.model_store.model.base.OrderStatusHistory;
import com.model_store.repository.OrderStatusHistoryRepository;
import com.model_store.service.OrderStatusHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class OrderStatusHistoryServiceImpl implements OrderStatusHistoryService {
    private final OrderStatusHistoryRepository repository;

    @Override
    public Flux<OrderStatusHistory> findByOrderId(Long orderId) {
        return repository.findByOrderIdOrderByChangedAt(orderId);
    }
}