package com.model_store.model.base;

import com.model_store.model.constant.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "order_status_history")
public class OrderStatusHistory {

    @Id
    private Long id;
    private Long orderId;
    private OrderStatus status;
    private String comment;

    private Instant changedAt;
}