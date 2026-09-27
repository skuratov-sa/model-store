package com.model_store.model.base;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Data
@Table("admin_agent_order_action")
public class AdminAgentOrderAction {
    @Id
    private Long id;
    private Long adminId;
    private Long agentId;
    private Long orderId;
    private String action;
    private Instant createdAt;
}
