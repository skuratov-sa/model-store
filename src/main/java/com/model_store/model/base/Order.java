package com.model_store.model.base;

import com.model_store.model.constant.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "order")
public class Order {
    private Long id;
    private Long sellerId;
    private Long customerId;
    private Integer amount;
    private OrderStatus status;
    private Long addressId;
    private Long bookingPrice;
}
