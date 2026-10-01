package com.model_store.model.base;

import com.model_store.model.constant.OrderStatus;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Data
@Table("order_case")
public class OrderCase {
    @Id private Long id;
    private Long orderId;
    private String kind;
    private String state;
    private Long openedBy;
    private String openingComment;
    private OrderStatus previousOrderStatus;
    private String telegramUrl;
    private Long resolvedBy;
    private String outcome;
    private String resolutionComment;
    private Instant createdAt;
    private Instant resolvedAt;
}
