package com.model_store.model.base;

import com.model_store.model.constant.OrderStatus;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

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

    @OneToMany
    private Address address;
    private Integer bookingPrice;
    private Instant createdAt;
}