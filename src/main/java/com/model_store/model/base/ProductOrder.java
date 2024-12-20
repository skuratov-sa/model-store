package com.model_store.model.base;

import com.model_store.model.constant.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "order")
public class ProductOrder {

    @Id
    private int Id;
    private Participant seller;
    private Participant customer;
    private Integer amount;
    private OrderStatus status;

    private Address address;
    private Integer bookingPrice;
}