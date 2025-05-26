package com.model_store.service.impl;

import com.model_store.model.base.OrderStatusHistory;
import com.model_store.model.constant.OrderStatus;
import com.model_store.repository.OrderStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderStatusHistoryServiceImplTest {

    @Mock
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @InjectMocks
    private OrderStatusHistoryServiceImpl orderStatusHistoryService;

    private OrderStatusHistory historyEntry1;
    private OrderStatusHistory historyEntry2;

    @BeforeEach
    void setUp() {
        historyEntry1 = new OrderStatusHistory();
        historyEntry1.setId(1L);
        historyEntry1.setOrderId(10L);
        historyEntry1.setStatus(OrderStatus.BOOKED);
        historyEntry1.setChangedAt(Instant.from(LocalDateTime.now().minusDays(1)));
        historyEntry1.setComment("Order booked");

        historyEntry2 = new OrderStatusHistory();
        historyEntry2.setId(2L);
        historyEntry2.setOrderId(10L);
        historyEntry2.setStatus(OrderStatus.AWAITING_PAYMENT);
        historyEntry2.setChangedAt(Instant.now());
        historyEntry2.setComment("Awaiting payment");
    }

    @Test
    void findByOrderId_shouldReturnFluxOfOrderStatusHistory_whenHistoryExistsForOrder() {
        Long orderId = 10L;
        when(orderStatusHistoryRepository.findByOrderIdOrderByChangedAt(orderId))
                .thenReturn(Flux.just(historyEntry1, historyEntry2));

        StepVerifier.create(orderStatusHistoryService.findByOrderId(orderId))
                .expectNext(historyEntry1)
                .expectNext(historyEntry2)
                .verifyComplete();

        verify(orderStatusHistoryRepository).findByOrderIdOrderByChangedAt(orderId);
    }

    @Test
    void findByOrderId_shouldReturnEmptyFlux_whenNoHistoryExistsForOrder() {
        Long orderId = 20L; // An orderId for which no history exists
        when(orderStatusHistoryRepository.findByOrderIdOrderByChangedAt(orderId))
                .thenReturn(Flux.empty());

        StepVerifier.create(orderStatusHistoryService.findByOrderId(orderId))
                .verifyComplete();

        verify(orderStatusHistoryRepository).findByOrderIdOrderByChangedAt(orderId);
    }
}
